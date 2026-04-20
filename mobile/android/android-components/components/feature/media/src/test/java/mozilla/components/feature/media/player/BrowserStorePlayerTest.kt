/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.media.player

import android.graphics.Bitmap
import android.os.HandlerThread
import android.os.Looper
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.assertNotNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import mozilla.components.browser.state.action.MediaSessionAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.MediaSessionState
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.mediasession.MediaSession
import mozilla.components.feature.media.R
import mozilla.components.support.test.mock
import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class BrowserStorePlayerTest {

    @Test
    fun `GIVEN an active playing tab WHEN state is read THEN playback state is ready and playWhenReady is true`() =
        runTest {
            val controller: MediaSession.Controller = mock()
            val tab =
                createTab(
                    url = "https://www.mozilla.org",
                    title = "Mozilla",
                    mediaSessionState =
                        MediaSessionState(
                            controller = controller,
                            playbackState = MediaSession.PlaybackState.PLAYING,
                        ),
                )
            val store = BrowserStore(BrowserState(tabs = listOf(tab)))

            val player = newPlayer(store)
            drain()

            assertEquals(Player.STATE_READY, player.playbackState)
            assertTrue(player.playWhenReady)
        }

    @Test
    fun `GIVEN an active paused tab WHEN state is read THEN playback state is ready and playWhenReady is false`() =
        runTest {
            val tab =
                createTab(
                    url = "https://www.mozilla.org",
                    mediaSessionState =
                        MediaSessionState(
                            controller = mock(),
                            playbackState = MediaSession.PlaybackState.PAUSED,
                        ),
                )
            val store = BrowserStore(BrowserState(tabs = listOf(tab)))

            val player = newPlayer(store)
            drain()

            assertEquals(Player.STATE_READY, player.playbackState)
            assertFalse(player.playWhenReady)
        }

    @Test
    fun `GIVEN no active media tab WHEN state is read THEN playback state is idle and playlist is empty`() = runTest {
        val store = BrowserStore(BrowserState())

        val player = newPlayer(store)
        drain()

        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertEquals(0, player.mediaItemCount)
    }

    @Test
    fun `GIVEN a stopped media tab WHEN state is read THEN playback state is idle`() = runTest {
        val tab =
            createTab(
                url = "https://www.mozilla.org",
                mediaSessionState =
                    MediaSessionState(
                        controller = mock(),
                        playbackState = MediaSession.PlaybackState.STOPPED,
                    ),
            )
        val store = BrowserStore(BrowserState(tabs = listOf(tab)))

        val player = newPlayer(store)
        drain()

        assertEquals(Player.STATE_IDLE, player.playbackState)
    }

    @Test
    fun `WHEN play is called THEN the active tab's controller play is invoked`() = runTest {
        val controller: MediaSession.Controller = mock()
        val tab =
            createTab(
                url = "https://www.mozilla.org",
                mediaSessionState =
                    MediaSessionState(
                        controller = controller,
                        playbackState = MediaSession.PlaybackState.PAUSED,
                    ),
            )
        val store = BrowserStore(BrowserState(tabs = listOf(tab)))

        val player = newPlayer(store)
        drain()

        player.play()
        drain()

        verify(controller).play()
    }

    @Test
    fun `WHEN pause is called THEN the active tab's controller pause is invoked`() = runTest {
        val controller: MediaSession.Controller = mock()
        val tab =
            createTab(
                url = "https://www.mozilla.org",
                mediaSessionState =
                    MediaSessionState(
                        controller = controller,
                        playbackState = MediaSession.PlaybackState.PLAYING,
                    ),
            )
        val store = BrowserStore(BrowserState(tabs = listOf(tab)))

        val player = newPlayer(store)
        drain()

        player.pause()
        drain()

        verify(controller).pause()
    }

    @Test
    fun `GIVEN an active tab WHEN state is read THEN the expected available commands are advertised`() = runTest {
        val tab =
            createTab(
                url = "https://www.mozilla.org",
                mediaSessionState =
                    MediaSessionState(
                        controller = mock(),
                        playbackState = MediaSession.PlaybackState.PLAYING,
                    ),
            )
        val store = BrowserStore(BrowserState(tabs = listOf(tab)))

        val player = newPlayer(store)
        drain()

        val commands = player.availableCommands
        assertTrue(commands.contains(Player.COMMAND_PLAY_PAUSE))
        assertTrue(commands.contains(Player.COMMAND_RELEASE))
        assertTrue(commands.contains(Player.COMMAND_GET_CURRENT_MEDIA_ITEM))
        assertTrue(commands.contains(Player.COMMAND_GET_METADATA))
        assertTrue(commands.contains(Player.COMMAND_GET_TIMELINE))
        assertFalse(commands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
        assertFalse(commands.contains(Player.COMMAND_SEEK_TO_NEXT))
        assertFalse(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertFalse(commands.contains(Player.COMMAND_SET_MEDIA_ITEM))
    }

    @Test
    fun `GIVEN a tab with no metadata title WHEN state is read THEN the media item title falls back to the page title`() =
        runTest {
            val tab =
                createTab(
                    url = "https://www.mozilla.org",
                    title = "Mozilla",
                    mediaSessionState =
                        MediaSessionState(
                            controller = mock(),
                            playbackState = MediaSession.PlaybackState.PLAYING,
                        ),
                )
            val store = BrowserStore(BrowserState(tabs = listOf(tab)))

            val player = newPlayer(store)
            drain()

            val metadata = player.mediaMetadata
            assertEquals("Mozilla", metadata.title.toString())
            assertEquals("https://www.mozilla.org", metadata.artist.toString())
        }

    @Test
    fun `GIVEN a tab with metadata title WHEN state is read THEN the media item uses the metadata title`() = runTest {
        val tab =
            createTab(
                url = "https://www.mozilla.org",
                title = "Mozilla",
                mediaSessionState =
                    MediaSessionState(
                        controller = mock(),
                        playbackState = MediaSession.PlaybackState.PLAYING,
                        metadata =
                            MediaSession.Metadata(
                                title = "Song",
                                artist = "Artist",
                                album = null,
                                getArtwork = null,
                            ),
                    ),
            )
        val store = BrowserStore(BrowserState(tabs = listOf(tab)))

        val player = newPlayer(store)
        drain()

        val metadata = player.mediaMetadata
        assertEquals("Song", metadata.title.toString())
        assertEquals("Artist", metadata.artist.toString())
    }

    @Test
    fun `GIVEN a private tab WHEN state is read THEN the title is the private mode label and artist is empty`() =
        runTest {
            val tab =
                createTab(
                    url = "https://www.mozilla.org",
                    title = "Secret",
                    private = true,
                    mediaSessionState =
                        MediaSessionState(
                            controller = mock(),
                            playbackState = MediaSession.PlaybackState.PLAYING,
                        ),
                )
            val store = BrowserStore(BrowserState(tabs = listOf(tab)))

            val player = newPlayer(store)
            drain()

            val privateLabel = testContext.getString(R.string.mozac_feature_media_notification_private_mode)
            val metadata = player.mediaMetadata
            assertEquals(privateLabel, metadata.title.toString())
            assertEquals("", metadata.artist.toString())
            assertNull(metadata.artworkData)
        }

    @Test
    fun `GIVEN a playing tab with artwork WHEN artwork is fetched THEN state exposes artwork bytes`() = runTest {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val tab =
            createTab(
                url = "https://www.mozilla.org",
                mediaSessionState =
                    MediaSessionState(
                        controller = mock(),
                        playbackState = MediaSession.PlaybackState.PLAYING,
                        metadata =
                            MediaSession.Metadata(
                                title = "Song",
                                artist = "Artist",
                                album = null,
                                getArtwork = { bitmap },
                            ),
                    ),
            )
        val store = BrowserStore(BrowserState(tabs = listOf(tab)))

        val player = newPlayer(store)
        drain()

        assertNotNull(player.mediaMetadata.artworkData)
    }

    @Test
    fun `GIVEN a private tab with artwork WHEN artwork is fetched THEN state does not expose artwork bytes`() =
        runTest {
            val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            val tab =
                createTab(
                    url = "https://www.mozilla.org",
                    private = true,
                    mediaSessionState =
                        MediaSessionState(
                            controller = mock(),
                            playbackState = MediaSession.PlaybackState.PLAYING,
                            metadata =
                                MediaSession.Metadata(
                                    title = "Song",
                                    artist = "Artist",
                                    album = null,
                                    getArtwork = { bitmap },
                                ),
                        ),
                )
            val store = BrowserStore(BrowserState(tabs = listOf(tab)))

            val player = newPlayer(store)
            drain()

            assertNull(player.mediaMetadata.artworkData)
        }

    @Test
    fun `GIVEN a playing tab with cached artwork WHEN its media session is deactivated THEN the cached artwork is cleared`() =
        runTest {
            val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            val tab =
                createTab(
                    url = "https://www.mozilla.org",
                    mediaSessionState =
                        MediaSessionState(
                            controller = mock(),
                            playbackState = MediaSession.PlaybackState.PLAYING,
                            metadata =
                                MediaSession.Metadata(
                                    title = "Song",
                                    artist = "Artist",
                                    album = null,
                                    getArtwork = { bitmap },
                                ),
                        ),
                )
            val store = BrowserStore(BrowserState(tabs = listOf(tab)))

            val player = newPlayer(store)
            drain()
            assertNotNull(player.mediaMetadata.artworkData)

            store.dispatch(MediaSessionAction.DeactivatedMediaSessionAction(tabId = tab.id))
            drain()

            assertNull(player.cachedArtwork)
            assertNull(player.mediaMetadata.artworkData)
        }

    @Test
    fun `GIVEN a playing tab WHEN its playback state transitions to paused THEN playWhenReady flips to false`() =
        runTest {
            val tab =
                createTab(
                    url = "https://www.mozilla.org",
                    mediaSessionState =
                        MediaSessionState(
                            controller = mock(),
                            playbackState = MediaSession.PlaybackState.PLAYING,
                        ),
                )
            val store = BrowserStore(BrowserState(tabs = listOf(tab)))

            val player = newPlayer(store)
            drain()
            assertTrue(player.playWhenReady)

            store.dispatch(
                MediaSessionAction.UpdateMediaPlaybackStateAction(
                    tabId = tab.id,
                    playbackState = MediaSession.PlaybackState.PAUSED,
                )
            )
            drain()

            assertEquals(Player.STATE_READY, player.playbackState)
            assertFalse(player.playWhenReady)
        }

    @Test
    fun `GIVEN two tabs with artwork WHEN the active tab changes THEN the cached artwork is not shown for the new tab until its fetch completes`() =
        runTest {
            val bitmapA = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            val pendingB = CompletableDeferred<Bitmap?>()
            val tabA =
                createTab(
                    url = "https://a.example",
                    id = "tab-a",
                    mediaSessionState =
                        MediaSessionState(
                            controller = mock(),
                            playbackState = MediaSession.PlaybackState.PLAYING,
                            metadata =
                                MediaSession.Metadata(
                                    title = "A",
                                    artist = null,
                                    album = null,
                                    getArtwork = { bitmapA },
                                ),
                        ),
                )
            val tabB =
                createTab(
                    url = "https://b.example",
                    id = "tab-b",
                    mediaSessionState =
                        MediaSessionState(
                            controller = mock(),
                            playbackState = MediaSession.PlaybackState.PAUSED,
                            metadata =
                                MediaSession.Metadata(
                                    title = "B",
                                    artist = null,
                                    album = null,
                                    getArtwork = { pendingB.await() },
                                ),
                        ),
                )
            val store = BrowserStore(BrowserState(tabs = listOf(tabA, tabB)))

            val player = newPlayer(store)
            drain()
            assertNotNull(player.mediaMetadata.artworkData)

            store.dispatch(
                MediaSessionAction.UpdateMediaPlaybackStateAction(
                    tabId = tabA.id,
                    playbackState = MediaSession.PlaybackState.STOPPED,
                )
            )
            drain()

            assertEquals("B", player.mediaMetadata.title.toString())
            assertNull(player.mediaMetadata.artworkData)
        }

    @Test
    fun `GIVEN no active media tab WHEN play is called THEN no crash and playback state remains idle`() = runTest {
        val store = BrowserStore(BrowserState())

        val player = newPlayer(store)
        drain()

        player.play()
        drain()

        assertEquals(Player.STATE_IDLE, player.playbackState)
    }

    @Test
    fun `GIVEN a paused tab WHEN setPlayWhenReady true is called THEN controller play is invoked`() = runTest {
        val controllerA: MediaSession.Controller = mock()
        val controllerB: MediaSession.Controller = mock()
        val activeTab =
            createTab(
                url = "https://a.example",
                id = "tab-a",
                mediaSessionState =
                    MediaSessionState(
                        controller = controllerA,
                        playbackState = MediaSession.PlaybackState.PLAYING,
                    ),
            )
        val inactiveTab =
            createTab(
                url = "https://b.example",
                id = "tab-b",
                mediaSessionState =
                    MediaSessionState(
                        controller = controllerB,
                        playbackState = MediaSession.PlaybackState.STOPPED,
                    ),
            )
        val store = BrowserStore(BrowserState(tabs = listOf(activeTab, inactiveTab)))

        val player = newPlayer(store)
        drain()

        player.pause()
        drain()

        verify(controllerA).pause()
        verify(controllerB, never()).pause()
    }

    @Test
    fun `WHEN release is called THEN the store flow scope is cancelled`() = runTest {
        val tab =
            createTab(
                url = "https://www.mozilla.org",
                mediaSessionState =
                    MediaSessionState(
                        controller = mock(),
                        playbackState = MediaSession.PlaybackState.PLAYING,
                    ),
            )
        val store = BrowserStore(BrowserState(tabs = listOf(tab)))

        val player = newPlayer(store)
        drain()
        assertTrue(player.scope.isActive)

        player.release()
        drain()

        assertFalse(player.scope.isActive)
    }

    @Test
    fun `GIVEN a looper that is not the current thread WHEN constructed THEN it throws IllegalStateException`() =
        runTest {
            val otherThread = HandlerThread("BrowserStorePlayerTest-other").apply { start() }
            try {
                val store = BrowserStore(BrowserState())
                val dispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
                assertThrows(IllegalStateException::class.java) {
                    BrowserStorePlayer(
                        context = testContext,
                        store = store,
                        mainDispatcher = dispatcher,
                        encodingDispatcher = dispatcher,
                        looper = otherThread.looper,
                    )
                }
            } finally {
                otherThread.quitSafely()
            }
        }

    @Test
    fun `GIVEN a tab with empty title and no metadata WHEN state is read THEN the title falls back to the URL`() =
        runTest {
            val tab =
                createTab(
                    url = "https://www.mozilla.org",
                    title = "",
                    mediaSessionState =
                        MediaSessionState(
                            controller = mock(),
                            playbackState = MediaSession.PlaybackState.PLAYING,
                        ),
                )
            val store = BrowserStore(BrowserState(tabs = listOf(tab)))

            val player = newPlayer(store)
            drain()

            assertEquals("https://www.mozilla.org", player.mediaMetadata.title.toString())
        }

    @Test
    fun `GIVEN a listener is added WHEN playback transitions to paused THEN onIsPlayingChanged fires with false`() =
        runTest {
            val tab =
                createTab(
                    url = "https://www.mozilla.org",
                    mediaSessionState =
                        MediaSessionState(
                            controller = mock(),
                            playbackState = MediaSession.PlaybackState.PLAYING,
                        ),
                )
            val store = BrowserStore(BrowserState(tabs = listOf(tab)))

            val player = newPlayer(store)
            drain()

            val observed = mutableListOf<Boolean>()
            player.addListener(
                object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        observed.add(isPlaying)
                    }
                }
            )

            store.dispatch(
                MediaSessionAction.UpdateMediaPlaybackStateAction(
                    tabId = tab.id,
                    playbackState = MediaSession.PlaybackState.PAUSED,
                )
            )
            drain()

            assertEquals(listOf(false), observed)
        }

    @Test
    fun `GIVEN a playing tab WHEN its metadata is updated THEN artwork is re-fetched and mediaMetadata reflects the new title`() =
        runTest {
            val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            var artworkCalls = 0
            val tab =
                createTab(
                    url = "https://www.mozilla.org",
                    mediaSessionState =
                        MediaSessionState(
                            controller = mock(),
                            playbackState = MediaSession.PlaybackState.PLAYING,
                            metadata =
                                MediaSession.Metadata(
                                    title = "A",
                                    artist = null,
                                    album = null,
                                    getArtwork = {
                                        artworkCalls++
                                        bitmap
                                    },
                                ),
                        ),
                )
            val store = BrowserStore(BrowserState(tabs = listOf(tab)))

            val player = newPlayer(store)
            drain()
            assertEquals(1, artworkCalls)
            assertEquals("A", player.mediaMetadata.title.toString())

            store.dispatch(
                MediaSessionAction.UpdateMediaMetadataAction(
                    tabId = tab.id,
                    metadata =
                        MediaSession.Metadata(
                            title = "B",
                            artist = null,
                            album = null,
                            getArtwork = {
                                artworkCalls++
                                bitmap
                            },
                        ),
                )
            )
            drain()

            assertEquals(2, artworkCalls)
            assertEquals("B", player.mediaMetadata.title.toString())
        }

    @Test
    fun `GIVEN a listener is added WHEN metadata is updated THEN onMediaMetadataChanged fires once with the new title`() =
        runTest {
            val tab =
                createTab(
                    url = "https://www.mozilla.org",
                    mediaSessionState =
                        MediaSessionState(
                            controller = mock(),
                            playbackState = MediaSession.PlaybackState.PLAYING,
                            metadata =
                                MediaSession.Metadata(
                                    title = "A",
                                    artist = null,
                                    album = null,
                                    getArtwork = null,
                                ),
                        ),
                )
            val store = BrowserStore(BrowserState(tabs = listOf(tab)))

            val player = newPlayer(store)
            drain()

            val observed = mutableListOf<String>()
            player.addListener(
                object : Player.Listener {
                    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                        observed.add(mediaMetadata.title.toString())
                    }
                }
            )

            store.dispatch(
                MediaSessionAction.UpdateMediaMetadataAction(
                    tabId = tab.id,
                    metadata =
                        MediaSession.Metadata(
                            title = "B",
                            artist = null,
                            album = null,
                            getArtwork = null,
                        ),
                )
            )
            drain()

            assertEquals(listOf("B"), observed)
        }

    @Test
    fun `GIVEN release has been called WHEN release is called again THEN it does not throw`() = runTest {
        val tab =
            createTab(
                url = "https://www.mozilla.org",
                mediaSessionState =
                    MediaSessionState(
                        controller = mock(),
                        playbackState = MediaSession.PlaybackState.PLAYING,
                    ),
            )
        val store = BrowserStore(BrowserState(tabs = listOf(tab)))

        val player = newPlayer(store)
        drain()

        player.release()
        drain()
        player.release()
        drain()

        assertFalse(player.scope.isActive)
    }

    @Test
    fun `GIVEN a released player WHEN a store action is dispatched THEN Media3 listener cleanup prevents events`() =
        runTest {
            val tab =
                createTab(
                    url = "https://www.mozilla.org",
                    mediaSessionState =
                        MediaSessionState(
                            controller = mock(),
                            playbackState = MediaSession.PlaybackState.PLAYING,
                        ),
                )
            val store = BrowserStore(BrowserState(tabs = listOf(tab)))

            val player = newPlayer(store)
            drain()

            val events = mutableListOf<String>()
            player.addListener(
                object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        events.add("isPlaying=$isPlaying")
                    }

                    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                        events.add("metadata=${mediaMetadata.title}")
                    }
                }
            )

            player.release()
            drain()

            store.dispatch(
                MediaSessionAction.UpdateMediaPlaybackStateAction(
                    tabId = tab.id,
                    playbackState = MediaSession.PlaybackState.PAUSED,
                )
            )
            drain()

            assertTrue(events.isEmpty())
        }

    @Test
    fun `GIVEN the active media tab changes WHEN setPlayWhenReady is called THEN the new active tab's controller is invoked`() =
        runTest {
            val controllerA: MediaSession.Controller = mock()
            val controllerB: MediaSession.Controller = mock()
            val tabA =
                createTab(
                    url = "https://a.example",
                    id = "tab-a",
                    mediaSessionState =
                        MediaSessionState(
                            controller = controllerA,
                            playbackState = MediaSession.PlaybackState.PLAYING,
                        ),
                )
            val tabB =
                createTab(
                    url = "https://b.example",
                    id = "tab-b",
                    mediaSessionState =
                        MediaSessionState(
                            controller = controllerB,
                            playbackState = MediaSession.PlaybackState.UNKNOWN,
                        ),
                )
            val store = BrowserStore(BrowserState(tabs = listOf(tabA, tabB)))

            val player = newPlayer(store)
            drain()

            store.dispatch(
                MediaSessionAction.UpdateMediaPlaybackStateAction(
                    tabId = tabA.id,
                    playbackState = MediaSession.PlaybackState.UNKNOWN,
                )
            )
            store.dispatch(
                MediaSessionAction.UpdateMediaPlaybackStateAction(
                    tabId = tabB.id,
                    playbackState = MediaSession.PlaybackState.PAUSED,
                )
            )
            drain()

            player.play()
            drain()

            verify(controllerB).play()
            verify(controllerA, never()).play()
        }

    private fun kotlinx.coroutines.test.TestScope.newPlayer(store: BrowserStore): BrowserStorePlayer {
        val dispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        return BrowserStorePlayer(
            context = testContext,
            store = store,
            mainDispatcher = dispatcher,
            encodingDispatcher = dispatcher,
            looper = Looper.getMainLooper(),
        )
    }

    private fun kotlinx.coroutines.test.TestScope.drain() {
        testScheduler.advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `GIVEN a tab advertising NEXT_TRACK THEN only the next-track seek command is advertised`() = runTest {
        val tab =
            createTab(
                url = "https://www.mozilla.org",
                mediaSessionState =
                    MediaSessionState(
                        controller = mock(),
                        playbackState = MediaSession.PlaybackState.PLAYING,
                        features = MediaSession.Feature(MediaSession.Feature.NEXT_TRACK),
                    ),
            )
        val player = newPlayer(BrowserStore(BrowserState(tabs = listOf(tab))))
        drain()

        assertTrue(player.availableCommands.contains(Player.COMMAND_SEEK_TO_NEXT))
        assertFalse(player.availableCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
    }

    @Test
    fun `GIVEN a tab with no track features THEN no skip commands are advertised`() = runTest {
        val tab =
            createTab(
                url = "https://www.mozilla.org",
                mediaSessionState =
                    MediaSessionState(
                        controller = mock(),
                        playbackState = MediaSession.PlaybackState.PLAYING,
                    ),
            )
        val player = newPlayer(BrowserStore(BrowserState(tabs = listOf(tab))))
        drain()

        assertFalse(player.availableCommands.contains(Player.COMMAND_SEEK_TO_NEXT))
        assertFalse(player.availableCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
    }

    @Test
    fun `GIVEN a single-item playlist WHEN seeking to next or previous THEN the active tab's controller is used`() =
        runTest {
            val controller: MediaSession.Controller = mock()
            val tab =
                createTab(
                    url = "https://www.mozilla.org",
                    mediaSessionState =
                        MediaSessionState(
                            controller = controller,
                            playbackState = MediaSession.PlaybackState.PLAYING,
                            features =
                                MediaSession.Feature(
                                    MediaSession.Feature.NEXT_TRACK or MediaSession.Feature.PREVIOUS_TRACK
                                ),
                        ),
                )
            val player = newPlayer(BrowserStore(BrowserState(tabs = listOf(tab))))
            drain()

            player.seekToNext()
            drain()
            player.seekToPrevious()
            drain()

            verify(controller).nextTrack()
            verify(controller).previousTrack()
        }
}
