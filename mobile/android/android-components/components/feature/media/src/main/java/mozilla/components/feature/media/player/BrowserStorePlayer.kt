/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.media.player

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import androidx.annotation.VisibleForTesting
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.mediasession.MediaSession as MozMediaSession
import mozilla.components.feature.media.ext.findActiveMediaTab
import mozilla.components.feature.media.ext.getArtistOrUrl
import mozilla.components.feature.media.ext.getNonPrivateIcon
import mozilla.components.feature.media.ext.getTitleOrUrl
import mozilla.components.lib.state.ext.flowScoped
import mozilla.components.support.base.log.logger.Logger

/**
 * Bridge between the Mozilla [BrowserStore] and Media3. Mirrors the playback state and metadata of the active media tab
 * onto a [SimpleBasePlayer], and routes Media3 play/pause commands back to that tab's [MozMediaSession.Controller].
 *
 * This player does not play audio itself. Actual playback happens in web content inside GeckoView; this class only
 * exposes that state to Android's MediaSession infrastructure (lockscreen, Bluetooth headsets, Android Auto, etc.).
 *
 * @param context Application-scoped [Context] used for resolving fallback strings (e.g. the "Private mode" label shown
 *   instead of metadata from private tabs).
 * @param store The [BrowserStore] whose active media tab drives player state.
 * @param mainDispatcher Dispatcher for store observation; must be a main-thread dispatcher.
 * @param encodingDispatcher Dispatcher used to off-load artwork PNG encoding off the main thread.
 * @param looper Looper for [SimpleBasePlayer]; must be the same thread backing [mainDispatcher].
 */
@UnstableApi
internal class BrowserStorePlayer(
    private val context: Context,
    private val store: BrowserStore,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val encodingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    looper: Looper = Looper.getMainLooper(),
) : SimpleBasePlayer(looper) {

    private val logger = Logger("BrowserStorePlayer")

    @VisibleForTesting internal var cachedArtwork: CachedArtwork? = null

    @VisibleForTesting internal var artworkJob: Job? = null

    private var lastArtworkKey: Pair<String, MozMediaSession.Metadata?>? = null

    // `scope` starts the store-observing coroutine as part of its initializer; the looper
    // check must run first. Keep this `init` block above the declaration of `scope`.
    init {
        check(Looper.myLooper() == looper) {
            "BrowserStorePlayer must be constructed on its looper's thread"
        }
    }

    @VisibleForTesting
    internal val scope: CoroutineScope =
        store.flowScoped(dispatcher = mainDispatcher) { flow ->
            flow.collect { state ->
                refreshArtwork(state.findActiveMediaTab())
                invalidateState()
            }
        }

    override fun getState(): State {
        val tab = store.state.findActiveMediaTab()
        val playbackState = tab?.mediaSessionState?.playbackState

        val media3PlaybackState =
            when (playbackState) {
                MozMediaSession.PlaybackState.PLAYING,
                MozMediaSession.PlaybackState.PAUSED -> Player.STATE_READY
                // STOPPED and UNKNOWN both map to IDLE rather than ENDED. Our surfaces
                // (lockscreen, Bluetooth, becoming-noisy) react to playWhenReady, and ENDED
                // would trigger Media3's automatic playlist-advance and replay behavior that
                // we don't support.
                else -> Player.STATE_IDLE
            }
        val playWhenReady = playbackState == MozMediaSession.PlaybackState.PLAYING

        val builder =
            State.Builder()
                .setAvailableCommands(commandsFor(tab))
                .setPlaybackState(media3PlaybackState)
                .setPlayWhenReady(
                    playWhenReady,
                    Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
                )

        if (tab != null) {
            builder.setPlaylist(listOf(buildMediaItemData(tab)))
        }
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val controller = store.state.findActiveMediaTab()?.mediaSessionState?.controller
        if (controller == null) {
            logger.debug("setPlayWhenReady($playWhenReady) dropped: no active media tab/controller")
            return Futures.immediateVoidFuture()
        }
        if (playWhenReady) {
            controller.play()
        } else {
            controller.pause()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        val controller = store.state.findActiveMediaTab()?.mediaSessionState?.controller
        if (controller == null) {
            logger.debug("seek($seekCommand) dropped: no active media tab/controller")
            return Futures.immediateVoidFuture()
        }
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> controller.nextTrack()
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> controller.previousTrack()
            else -> logger.debug("seek command $seekCommand ignored")
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return Futures.immediateVoidFuture()
    }

    // Skip commands are advertised only when web content declares the matching
    // MediaSession feature, so the system surfaces next/previous exactly when the
    // page can act on them.
    private fun commandsFor(tab: SessionState?): Player.Commands {
        val features = tab?.mediaSessionState?.features
        return Player.Commands.Builder()
            .addAll(BASE_COMMANDS)
            .addIf(
                Player.COMMAND_SEEK_TO_NEXT,
                features?.contains(MozMediaSession.Feature.NEXT_TRACK) == true,
            )
            .addIf(
                Player.COMMAND_SEEK_TO_PREVIOUS,
                features?.contains(MozMediaSession.Feature.PREVIOUS_TRACK) == true,
            )
            .build()
    }

    private fun buildMediaItemData(tab: SessionState): MediaItemData {
        val meta = tab.mediaSessionState?.metadata
        val builder =
            MediaMetadata.Builder()
                .setTitle(tab.getTitleOrUrl(context, meta?.title))
                .setArtist(tab.getArtistOrUrl(meta?.artist))
        cachedArtwork
            ?.takeIf { it.tabId == tab.id }
            ?.let { builder.setArtworkData(it.data, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }

        return MediaItemData.Builder(tab.id)
            .setMediaItem(MediaItem.Builder().setMediaId(tab.id).build())
            .setMediaMetadata(builder.build())
            .build()
    }

    private fun refreshArtwork(tab: SessionState?) {
        if (tab == null) {
            artworkJob?.cancel()
            artworkJob = null
            cachedArtwork = null
            lastArtworkKey = null
            return
        }
        val metadata = tab.mediaSessionState?.metadata
        val key = tab.id to metadata
        if (key == lastArtworkKey) return
        lastArtworkKey = key
        artworkJob?.cancel()
        val tabId = tab.id
        artworkJob = scope.launch {
            val bitmap = tab.getNonPrivateIcon(metadata?.getArtwork)
            if (store.state.findActiveMediaTab()?.id != tabId) return@launch
            val bytes = bitmap?.let { withContext(encodingDispatcher) { it.toPngBytes() } }
            cachedArtwork = bytes?.let { CachedArtwork(tabId, it) }
            invalidateState()
        }
    }

    @VisibleForTesting internal class CachedArtwork(val tabId: String, val data: ByteArray)

    private companion object {
        private val BASE_COMMANDS =
            Player.Commands.Builder()
                .add(Player.COMMAND_PLAY_PAUSE)
                .add(Player.COMMAND_RELEASE)
                .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_GET_METADATA)
                .add(Player.COMMAND_GET_TIMELINE)
                .build()
    }
}

// Ignored by PNG compression (lossless); required by the Bitmap.compress signature.
private const val BITMAP_COMPRESSION_QUALITY = 100

private fun Bitmap.toPngBytes(): ByteArray {
    val stream = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.PNG, BITMAP_COMPRESSION_QUALITY, stream)
    return stream.toByteArray()
}
