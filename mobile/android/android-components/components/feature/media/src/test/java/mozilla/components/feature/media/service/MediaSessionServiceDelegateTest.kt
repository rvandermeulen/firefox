/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.media.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.MediaSessionState
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.base.crash.CrashReporting
import mozilla.components.concept.engine.mediasession.MediaSession as MozMediaSession
import mozilla.components.concept.engine.mediasession.MediaSession.PlaybackState
import mozilla.components.feature.media.facts.MediaFacts
import mozilla.components.feature.media.notification.MediaNotification
import mozilla.components.feature.media.player.BrowserStorePlayer
import mozilla.components.support.base.Component
import mozilla.components.support.base.android.NotificationsDelegate
import mozilla.components.support.base.facts.Action
import mozilla.components.support.base.facts.processor.CollectionProcessor
import mozilla.components.support.base.ids.SharedIdsHelper
import mozilla.components.support.test.any
import mozilla.components.support.test.argumentCaptor
import mozilla.components.support.test.coMock
import mozilla.components.support.test.eq
import mozilla.components.support.test.mock
import mozilla.components.support.test.robolectric.testContext
import mozilla.components.support.test.whenever
import mozilla.components.support.utils.ext.stopForegroundCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class MediaSessionServiceDelegateTest {

    private val notificationId = SharedIdsHelper.getIdForTag(testContext, AbstractMediaSessionService.NOTIFICATION_TAG)

    private val createdSessions = mutableListOf<MediaSession>()
    private val createdPlayers = mutableListOf<BrowserStorePlayer>()

    @After
    fun tearDown() {
        // MediaSession.release() throws IllegalStateException on a second call; swallow so tearDown
        // can unconditionally release sessions whose production code already released them (e.g.
        // shutdown-path tests).
        createdSessions.forEach { runCatching { it.release() } }
        createdPlayers.forEach { runCatching { it.release() } }
    }

    private fun createDelegate(
        context: Context = testContext,
        service: AbstractMediaSessionService = mock(),
        store: BrowserStore = BrowserStore(),
        crashReporter: CrashReporting? = mock(),
        notificationsDelegate: NotificationsDelegate = mock(),
        scope: CoroutineScope = MainScope(),
    ): MediaSessionServiceDelegate {
        val delegate =
            MediaSessionServiceDelegate(
                context,
                service,
                store,
                crashReporter,
                notificationsDelegate,
                scope,
            )
        createdSessions += delegate.mediaSession
        createdPlayers += delegate.player
        return delegate
    }

    @Test
    fun `WHEN the service is created THEN initialize the notification scope`() = runTest {
        val delegate = createDelegate(scope = this)

        delegate.onCreate()

        assertNotNull(delegate.notificationScope)
    }

    @Test
    fun `WHEN the service is destroyed THEN stop notification updates and abandon audio focus`() = runTest {
        val delegate = createDelegate(scope = this)
        delegate.audioFocus = mock()

        delegate.onDestroy()

        verify(delegate.audioFocus)!!.abandon()
        verify(delegate.service, never()).stopSelf()
        assertNull(delegate.notificationScope)
    }

    @Test
    fun `GIVEN media playing started WHEN a new play command is received THEN resume media and emit telemetry`() =
        runTest {
            val delegate = createDelegate(scope = this)
            delegate.player = mock()

            CollectionProcessor.withFactCollection { facts ->
                delegate.onStartCommand(Intent(AbstractMediaSessionService.ACTION_PLAY))

                verify(delegate.player).play()
                assertEquals(1, facts.size)
                with(facts[0]) {
                    assertEquals(Component.FEATURE_MEDIA, component)
                    assertEquals(Action.PLAY, action)
                    assertEquals(MediaFacts.Items.NOTIFICATION, item)
                }
            }
        }

    @Test
    fun `GIVEN media playing started WHEN a new pause command is received THEN pause media and emit telemetry`() =
        runTest {
            val delegate = createDelegate(scope = this)
            delegate.player = mock()

            CollectionProcessor.withFactCollection { facts ->
                delegate.onStartCommand(Intent(AbstractMediaSessionService.ACTION_PAUSE))

                verify(delegate.player).pause()
                assertEquals(1, facts.size)
                with(facts[0]) {
                    assertEquals(Component.FEATURE_MEDIA, component)
                    assertEquals(Action.PAUSE, action)
                    assertEquals(MediaFacts.Items.NOTIFICATION, item)
                }
            }
        }

    @Test
    fun `GIVEN an active paused tab WHEN ACTION_PLAY is received THEN the tab's controller is played`() = runTest {
        val controller: MozMediaSession.Controller = mock()
        val store =
            BrowserStore(
                BrowserState(
                    tabs =
                        listOf(
                            createTab(
                                "https://www.mozilla.org",
                                mediaSessionState = MediaSessionState(controller, playbackState = PlaybackState.PAUSED),
                            )
                        )
                )
            )
        val delegate = createDelegate(store = store, scope = this)
        // Intentionally do not replace delegate.player: we want the real BrowserStorePlayer
        // so play() routes through handleSetPlayWhenReady to the controller.

        delegate.onStartCommand(Intent(AbstractMediaSessionService.ACTION_PLAY))
        shadowOf(Looper.getMainLooper()).idle()

        verify(controller).play()
    }

    @Test
    fun `GIVEN an active playing tab WHEN ACTION_PAUSE is received THEN the tab's controller is paused`() = runTest {
        val controller: MozMediaSession.Controller = mock()
        val store =
            BrowserStore(
                BrowserState(
                    tabs =
                        listOf(
                            createTab(
                                "https://www.mozilla.org",
                                mediaSessionState =
                                    MediaSessionState(controller, playbackState = PlaybackState.PLAYING),
                            )
                        )
                )
            )
        val delegate = createDelegate(store = store, scope = this)

        delegate.onStartCommand(Intent(AbstractMediaSessionService.ACTION_PAUSE))
        shadowOf(Looper.getMainLooper()).idle()

        verify(controller).pause()
    }

    @Test
    fun `GIVEN an active playing tab WHEN ACTION_NEXT_TRACK is received THEN the tab's controller advances and telemetry is emitted`() =
        runTest {
            val controller: MozMediaSession.Controller = mock()
            val store =
                BrowserStore(
                    BrowserState(
                        tabs =
                            listOf(
                                createTab(
                                    "https://www.mozilla.org",
                                    mediaSessionState =
                                        MediaSessionState(controller, playbackState = PlaybackState.PLAYING),
                                )
                            )
                    )
                )
            val delegate = createDelegate(store = store, scope = this)

            CollectionProcessor.withFactCollection { facts ->
                delegate.onStartCommand(Intent(AbstractMediaSessionService.ACTION_NEXT_TRACK))
                shadowOf(Looper.getMainLooper()).idle()

                verify(controller).nextTrack()
                assertEquals(1, facts.size)
                with(facts[0]) {
                    assertEquals(Component.FEATURE_MEDIA, component)
                    assertEquals(Action.NEXT, action)
                    assertEquals(MediaFacts.Items.NOTIFICATION, item)
                }
            }
        }

    @Test
    fun `GIVEN an active playing tab WHEN ACTION_PREV_TRACK is received THEN the tab's controller rewinds and telemetry is emitted`() =
        runTest {
            val controller: MozMediaSession.Controller = mock()
            val store =
                BrowserStore(
                    BrowserState(
                        tabs =
                            listOf(
                                createTab(
                                    "https://www.mozilla.org",
                                    mediaSessionState =
                                        MediaSessionState(controller, playbackState = PlaybackState.PLAYING),
                                )
                            )
                    )
                )
            val delegate = createDelegate(store = store, scope = this)

            CollectionProcessor.withFactCollection { facts ->
                delegate.onStartCommand(Intent(AbstractMediaSessionService.ACTION_PREV_TRACK))
                shadowOf(Looper.getMainLooper()).idle()

                verify(controller).previousTrack()
                assertEquals(1, facts.size)
                with(facts[0]) {
                    assertEquals(Component.FEATURE_MEDIA, component)
                    assertEquals(Action.PREVIOUS, action)
                    assertEquals(MediaFacts.Items.NOTIFICATION, item)
                }
            }
        }

    @Test
    fun `WHEN the task is removed THEN stop media in all tabs and shutdown`() = runTest {
        val notificationManagerCompat: NotificationManagerCompat = mock()
        val notificationsDelegate: NotificationsDelegate = mock()
        whenever(notificationsDelegate.notificationManagerCompat).thenReturn(notificationManagerCompat)

        val mediaTab1 = getMediaTab()
        val mediaTab2 = getMediaTab(PlaybackState.PAUSED)
        val store = BrowserStore(BrowserState(tabs = listOf(mediaTab1, mediaTab2)))
        val delegate = createDelegate(store = store, notificationsDelegate = notificationsDelegate)
        delegate.mediaSession = mock()
        delegate.player = mock()
        delegate.audioFocus = mock()

        delegate.onTaskRemoved()

        verify(mediaTab1.mediaSessionState!!.controller).stop()
        verify(mediaTab2.mediaSessionState!!.controller).stop()
        verify(delegate.mediaSession).release()
        verify(delegate.player).release()
        verify(delegate.audioFocus).abandon()
        verify(delegate.service).stopSelf()
    }

    @Test
    fun `WHEN handling playing media THEN emit telemetry`() = runTest {
        val mediaTab = getMediaTab()
        val delegate = createDelegate(scope = this)

        CollectionProcessor.withFactCollection { facts ->
            delegate.handleMediaPlaying(mediaTab)

            assertEquals(1, facts.size)
            with(facts[0]) {
                assertEquals(Component.FEATURE_MEDIA, component)
                assertEquals(Action.PLAY, action)
                assertEquals(MediaFacts.Items.STATE, item)
            }
        }
    }

    @Test
    fun `WHEN handling playing media THEN register becoming-noisy listener`() = runTest {
        val mediaTab = getMediaTab()
        val delegate = spy(createDelegate())

        delegate.handleMediaPlaying(mediaTab)

        verify(delegate).registerBecomingNoisyListenerIfNeeded(mediaTab)
    }

    @Test
    fun `GIVEN the service is already in foreground WHEN handling playing media THEN request audio focus and update notification`() =
        runTest {
            val mediaTab = getMediaTab()
            val notificationsDelegate: NotificationsDelegate = mock()
            val delegate = createDelegate(notificationsDelegate = notificationsDelegate, scope = this)
            delegate.onCreate()
            delegate.audioFocus = mock()
            delegate.isForegroundService = true

            delegate.handleMediaPlaying(mediaTab)
            testScheduler.advanceUntilIdle()

            verify(delegate.audioFocus).request(eq(mediaTab.id), any())
            verify(notificationsDelegate).notify(any(), eq(delegate.notificationId), any(), any(), any(), eq(false))
        }

    // The delegate reads the audio-session type from the session state at
    // request time, and the feature re-runs this on every mediaSessionState
    // change, so a type that arrives after playback started is picked up on the
    // next request rather than being stuck at the default. Every type must be
    // forwarded to the focus request exactly once, with no default request made
    // first. One case per type so each runs in a fresh test environment.
    private fun TestScope.assertFocusRequestUsesType(type: MozMediaSession.AudioSessionType) {
        val mediaTab =
            createTab(
                url = "https://www.mozilla.org",
                mediaSessionState =
                    MediaSessionState(
                        mock(),
                        playbackState = PlaybackState.PLAYING,
                        audioSessionType = type,
                    ),
            )
        val delegate = MediaSessionServiceDelegate(testContext, mock(), BrowserStore(), mock(), mock(), this)
        delegate.onCreate()
        delegate.audioFocus = mock()
        delegate.isForegroundService = true

        delegate.handleMediaPlaying(mediaTab)
        testScheduler.advanceUntilIdle()

        verify(delegate.audioFocus, times(1)).request(mediaTab.id, type)
        verifyNoMoreInteractions(delegate.audioFocus)
    }

    @Test
    fun `GIVEN a foreground service WHEN playing media with auto type THEN focus is requested as auto`() = runTest {
        assertFocusRequestUsesType(MozMediaSession.AudioSessionType.AUTO)
    }

    @Test
    fun `GIVEN a foreground service WHEN playing media with playback type THEN focus is requested as playback`() =
        runTest {
            assertFocusRequestUsesType(MozMediaSession.AudioSessionType.PLAYBACK)
        }

    @Test
    fun `GIVEN a foreground service WHEN playing media with transient type THEN focus is requested as transient`() =
        runTest {
            assertFocusRequestUsesType(MozMediaSession.AudioSessionType.TRANSIENT)
        }

    @Test
    fun `GIVEN a foreground service WHEN playing media with transient-solo type THEN focus is requested as transient-solo`() =
        runTest {
            assertFocusRequestUsesType(MozMediaSession.AudioSessionType.TRANSIENT_SOLO)
        }

    @Test
    fun `GIVEN a foreground service WHEN playing media with ambient type THEN focus is requested as ambient`() =
        runTest {
            assertFocusRequestUsesType(MozMediaSession.AudioSessionType.AMBIENT)
        }

    @Test
    fun `GIVEN a foreground service WHEN playing media with play-and-record type THEN focus is requested as play-and-record`() =
        runTest {
            assertFocusRequestUsesType(MozMediaSession.AudioSessionType.PLAY_AND_RECORD)
        }

    @Test
    fun `GIVEN the service is not in foreground WHEN handling playing media THEN start the media service as foreground`() =
        runTest {
            val mediaTab = getMediaTab()
            val delegate = createDelegate(scope = this)
            delegate.onCreate()
            delegate.audioFocus = mock()
            delegate.isForegroundService = false

            delegate.handleMediaPlaying(mediaTab)
            testScheduler.advanceUntilIdle()

            verify(delegate.service).startForeground(eq(delegate.notificationId), any())
            assertTrue(delegate.isForegroundService)
        }

    @Test
    fun `GIVEN the service is not in foreground WHEN handling playing media THEN audio focus is requested after foreground service is started`() =
        runTest {
            val mediaTab = getMediaTab()
            val delegate = createDelegate(scope = this)
            delegate.onCreate()
            delegate.audioFocus = mock()
            delegate.isForegroundService = false

            delegate.handleMediaPlaying(mediaTab)
            testScheduler.advanceUntilIdle()

            val inOrder = org.mockito.Mockito.inOrder(delegate.service, delegate.audioFocus)
            inOrder.verify(delegate.service).startForeground(eq(delegate.notificationId), any())
            inOrder.verify(delegate.audioFocus).request(eq(mediaTab.id), any())
        }

    @Test
    fun `WHEN updating the notification for a new media state THEN post a new notification`() = runTest {
        val mediaTab = getMediaTab()
        val notificationsDelegate: NotificationsDelegate = mock()
        val delegate = createDelegate(notificationsDelegate = notificationsDelegate, scope = this)
        delegate.onCreate()
        val notification: Notification = mock()
        delegate.notificationHelper = coMock {
            doReturn(notification).`when`(this).create(mediaTab, delegate.mediaSession)
        }

        delegate.updateNotification(mediaTab)
        testScheduler.advanceUntilIdle()

        verify(notificationsDelegate)
            .notify(any(), eq(delegate.notificationId), eq(notification), any(), any(), eq(false))
    }

    @Test
    fun `WHEN starting the service as foreground THEN use start with a new notification for the current media state`() =
        runTest {
            val mediaTab = getMediaTab()
            val delegate = createDelegate(scope = this)
            delegate.onCreate()
            delegate.audioFocus = mock()
            val notification: Notification = mock()
            delegate.notificationHelper = coMock {
                doReturn(notification).`when`(this).create(mediaTab, delegate.mediaSession)
            }

            delegate.startForeground(mediaTab, coroutineContext)
            testScheduler.advanceUntilIdle()

            verify(delegate.service).startForeground(eq(delegate.notificationId), eq(notification))
            assertTrue(delegate.isForegroundService)
            verify(delegate.audioFocus).request(eq(mediaTab.id), any())
        }

    @Test
    fun `WHEN handling paused media THEN emit telemetry`() = runTest {
        val mediaTab = getMediaTab(PlaybackState.PAUSED)
        val delegate = createDelegate(scope = this)

        CollectionProcessor.withFactCollection { facts ->
            delegate.handleMediaPaused(mediaTab)

            assertEquals(1, facts.size)
            with(facts[0]) {
                assertEquals(Component.FEATURE_MEDIA, component)
                assertEquals(Action.PAUSE, action)
                assertEquals(MediaFacts.Items.STATE, item)
            }
        }
    }

    @Test
    fun `WHEN handling paused media THEN update internal state and notification and stop the service`() = runTest {
        val mediaTab = getMediaTab(PlaybackState.PAUSED)
        val notificationManagerCompat = spy(NotificationManagerCompat.from(testContext))
        val notificationsDelegate = spy(NotificationsDelegate(notificationManagerCompat))
        doReturn(true).`when`(notificationManagerCompat).areNotificationsEnabled()

        val notificationHelper: MediaNotification = mock()
        val notification: Notification = mock()
        val notificationId = SharedIdsHelper.getIdForTag(testContext, AbstractMediaSessionService.NOTIFICATION_TAG)

        val delegate = spy(createDelegate(notificationsDelegate = notificationsDelegate, scope = this))
        delegate.isForegroundService = true
        delegate.notificationHelper = notificationHelper
        delegate.audioFocus = mock()
        delegate.isTransientAudioFocusLoss = false
        // Extract outside the stubbing: accessing the spy's field mid-stub trips UnfinishedStubbingException.
        val session = delegate.mediaSession

        doReturn(notification).`when`(notificationHelper).create(mediaTab, session)

        delegate.onCreate()

        delegate.handleMediaPaused(mediaTab)
        testScheduler.advanceUntilIdle()

        verify(delegate).unregisterBecomingNoisyListenerIfNeeded()
        verify(delegate.service).stopForegroundCompat(false)
        verify(notificationsDelegate).notify(null, notificationId, notification)
        assertFalse(delegate.isForegroundService)
    }

    @Test
    fun `GIVEN transient audio focus loss WHEN handling paused media THEN keep foreground service running`() = runTest {
        val mediaTab = getMediaTab(PlaybackState.PAUSED)
        val notificationManagerCompat = spy(NotificationManagerCompat.from(testContext))
        val notificationsDelegate = spy(NotificationsDelegate(notificationManagerCompat))
        doReturn(true).`when`(notificationManagerCompat).areNotificationsEnabled()

        val notificationHelper: MediaNotification = mock()
        val notification: Notification = mock()

        val delegate = spy(createDelegate(notificationsDelegate = notificationsDelegate, scope = this))
        delegate.isForegroundService = true
        delegate.notificationHelper = notificationHelper
        delegate.audioFocus = mock()
        delegate.isTransientAudioFocusLoss = true
        // Extract outside the stubbing: accessing the spy's field mid-stub trips UnfinishedStubbingException.
        val session = delegate.mediaSession

        doReturn(notification).`when`(notificationHelper).create(mediaTab, session)

        delegate.onCreate()

        delegate.handleMediaPaused(mediaTab)
        testScheduler.advanceUntilIdle()

        verify(delegate, never()).unregisterBecomingNoisyListenerIfNeeded()
        verify(delegate.service, never()).stopForegroundCompat(false)
        assertTrue(delegate.isForegroundService)
        assertFalse(delegate.isTransientAudioFocusLoss)
    }

    @Test
    fun `GIVEN transient audio focus loss WHEN handling paused media twice THEN only the second call tears down the foreground service`() =
        runTest {
            val mediaTab = getMediaTab(PlaybackState.PAUSED)
            val notificationManagerCompat = spy(NotificationManagerCompat.from(testContext))
            val notificationsDelegate = spy(NotificationsDelegate(notificationManagerCompat))
            doReturn(true).`when`(notificationManagerCompat).areNotificationsEnabled()

            val notificationHelper: MediaNotification = mock()
            val notification: Notification = mock()

            val delegate = spy(createDelegate(notificationsDelegate = notificationsDelegate, scope = this))
            delegate.isForegroundService = true
            delegate.notificationHelper = notificationHelper
            delegate.audioFocus = mock()
            delegate.isTransientAudioFocusLoss = true
            val session = delegate.mediaSession

            doReturn(notification).`when`(notificationHelper).create(mediaTab, session)

            delegate.onCreate()

            // First call (transient loss): service stays alive, flag clears.
            delegate.handleMediaPaused(mediaTab)
            testScheduler.advanceUntilIdle()

            verify(delegate, never()).unregisterBecomingNoisyListenerIfNeeded()
            verify(delegate.service, never()).stopForegroundCompat(false)
            assertTrue(delegate.isForegroundService)
            assertFalse(delegate.isTransientAudioFocusLoss)

            // Second call (flag is now false): service tears down normally.
            delegate.handleMediaPaused(mediaTab)
            testScheduler.advanceUntilIdle()

            verify(delegate).unregisterBecomingNoisyListenerIfNeeded()
            verify(delegate.service).stopForegroundCompat(false)
            assertFalse(delegate.isForegroundService)
        }

    @Test
    fun `WHEN handling stopped media THEN emit telemetry`() = runTest {
        val mediaTab = getMediaTab(PlaybackState.STOPPED)
        val delegate = createDelegate(scope = this)

        CollectionProcessor.withFactCollection { facts ->
            delegate.handleMediaStopped(mediaTab)

            assertEquals(1, facts.size)
            with(facts[0]) {
                assertEquals(Component.FEATURE_MEDIA, component)
                assertEquals(Action.STOP, action)
                assertEquals(MediaFacts.Items.STATE, item)
            }
        }
    }

    @Test
    fun `WHEN handling stopped media THEN update internal state and cancel notification and stop the service`() =
        runTest {
            val mediaTab = getMediaTab(PlaybackState.STOPPED)
            val notificationManagerCompat = spy(NotificationManagerCompat.from(testContext))
            val notificationsDelegate = spy(NotificationsDelegate(notificationManagerCompat))
            doReturn(true).`when`(notificationManagerCompat).areNotificationsEnabled()

            val delegate = spy(createDelegate(notificationsDelegate = notificationsDelegate, scope = this))
            delegate.isForegroundService = true
            delegate.audioFocus = mock()
            delegate.notificationHelper = mock()
            delegate.onCreate()

            delegate.handleMediaStopped(mediaTab)
            testScheduler.advanceUntilIdle()

            verify(delegate).unregisterBecomingNoisyListenerIfNeeded()
            verify(delegate.service).stopForegroundCompat(false)
            verify(delegate.audioFocus).abandon()
            verify(notificationManagerCompat).cancel(eq(notificationId))
            assertFalse(delegate.isForegroundService)
        }

    @Test
    fun `WHEN there is no media playing THEN stop the media service`() = runTest {
        val notificationManagerCompat: NotificationManagerCompat = mock()
        val notificationsDelegate: NotificationsDelegate = mock()
        whenever(notificationsDelegate.notificationManagerCompat).thenReturn(notificationManagerCompat)

        val delegate = createDelegate(notificationsDelegate = notificationsDelegate)
        delegate.audioFocus = mock()
        delegate.mediaSession = mock()
        delegate.player = mock()

        delegate.handleNoMedia()

        verify(delegate.mediaSession).release()
        verify(delegate.player).release()
        verify(delegate.audioFocus).abandon()
        verify(delegate.service).stopSelf()
    }

    @Test
    fun `WHEN stopping running in foreground THEN stop the foreground service`() = runTest {
        val delegate = createDelegate(scope = this)
        delegate.isForegroundService = true

        delegate.stopForeground()

        verify(delegate.service).stopForegroundCompat(false)
        assertFalse(delegate.isForegroundService)
    }

    @Test
    fun `GIVEN a audio noisy receiver is already registered WHEN trying to register a new one THEN return early`() =
        runTest {
            val context = spy(testContext)
            val delegate = createDelegate(context = context)
            delegate.noisyAudioStreamReceiver = mock()

            delegate.registerBecomingNoisyListenerIfNeeded(mock())

            verify(context, never()).registerReceiver(any(), any(), eq(Context.RECEIVER_NOT_EXPORTED))
        }

    @Test
    fun `GIVEN a audio noisy receiver is not already registered WHEN trying to register a new one THEN register it`() =
        runTest {
            val delegate = spy(createDelegate())
            val receiverCaptor = argumentCaptor<BroadcastReceiver>()

            delegate.registerBecomingNoisyListenerIfNeeded(mock())

            verify(delegate).registerBecomingNoisyListener(receiverCaptor.capture())
            assertEquals(BecomingNoisyReceiver::class.java, receiverCaptor.value.javaClass)
        }

    @Test
    fun `GIVEN a audio noisy receiver is already registered WHEN trying to unregister one THEN unregister it`() =
        runTest {
            val context = spy(testContext)
            val delegate = createDelegate(context = context, store = BrowserStore(mock()), scope = this)
            delegate.noisyAudioStreamReceiver = mock()
            context.registerReceiver(
                delegate.noisyAudioStreamReceiver,
                delegate.intentFilter,
                Context.RECEIVER_NOT_EXPORTED,
            )
            val receiverCaptor = argumentCaptor<BroadcastReceiver>()

            delegate.unregisterBecomingNoisyListenerIfNeeded()

            verify(context).unregisterReceiver(receiverCaptor.capture())
            assertEquals(BecomingNoisyReceiver::class.java, receiverCaptor.value.javaClass)
            assertNull(delegate.noisyAudioStreamReceiver)
        }

    @Test
    fun `GIVEN a audio noisy receiver is not already registered WHEN trying to unregister one THEN return early`() =
        runTest {
            val context = spy(testContext)
            val delegate = createDelegate(context = context)

            delegate.unregisterBecomingNoisyListenerIfNeeded()

            verify(context, never()).unregisterReceiver(any())
        }

    @Test
    fun `WHEN the delegate is shutdown THEN cleanup resources and stop the media service`() = runTest {
        val notificationManagerCompat: NotificationManagerCompat = mock()
        val notificationsDelegate: NotificationsDelegate = mock()
        whenever(notificationsDelegate.notificationManagerCompat).thenReturn(notificationManagerCompat)

        val delegate = createDelegate(notificationsDelegate = notificationsDelegate)
        delegate.mediaSession = mock()
        delegate.player = mock()
        delegate.audioFocus = mock()

        delegate.shutdown()

        verify(delegate.mediaSession).release()
        verify(delegate.player).release()
        verify(delegate.audioFocus).abandon()
        verify(delegate.service).stopSelf()
        assertNull(delegate.noisyAudioStreamReceiver)
    }

    @Test
    fun `GIVEN an in-flight notification coroutine WHEN shutdown is called THEN the notification is not posted`() =
        runTest {
            val notificationManagerCompat: NotificationManagerCompat = mock()
            val notificationsDelegate: NotificationsDelegate = mock()
            whenever(notificationsDelegate.notificationManagerCompat).thenReturn(notificationManagerCompat)

            val delegate = createDelegate(notificationsDelegate = notificationsDelegate, scope = this)
            delegate.onCreate()
            delegate.mediaSession = mock()
            delegate.player = mock()
            val notification: Notification = mock()
            delegate.notificationHelper = coMock {
                doReturn(notification).`when`(this).create(any(), any())
            }

            delegate.updateNotification(getMediaTab(PlaybackState.PAUSED))
            delegate.shutdown()
            testScheduler.advanceUntilIdle()

            verify(notificationsDelegate, never()).notify(any(), anyInt(), any(), any(), any(), anyBoolean())
        }

    @Test
    fun `GIVEN the delegate has been shutdown WHEN handleMediaPlaying is called THEN no notification is posted and startForeground is not called`() =
        runTest {
            val notificationManagerCompat: NotificationManagerCompat = mock()
            val notificationsDelegate: NotificationsDelegate = mock()
            whenever(notificationsDelegate.notificationManagerCompat).thenReturn(notificationManagerCompat)

            val delegate = spy(createDelegate(notificationsDelegate = notificationsDelegate, scope = this))
            // Avoid registering a real BroadcastReceiver on testContext after shutdown.
            doNothing().`when`(delegate).registerBecomingNoisyListenerIfNeeded(any())
            delegate.onCreate()
            delegate.mediaSession = mock()
            delegate.player = mock()
            delegate.audioFocus = mock()

            delegate.shutdown()
            testScheduler.advanceUntilIdle()

            delegate.handleMediaPlaying(getMediaTab())
            testScheduler.advanceUntilIdle()

            verify(notificationsDelegate, never()).notify(any(), anyInt(), any(), any(), any(), anyBoolean())
            verify(delegate.service, never()).startForeground(anyInt(), any())
        }

    @Test
    fun `GIVEN no active media tab WHEN ACTION_PAUSE is received THEN the command is handled without crashing and notification telemetry still fires`() =
        runTest {
            val delegate = createDelegate(store = BrowserStore(), scope = this)

            CollectionProcessor.withFactCollection { facts ->
                delegate.onStartCommand(Intent(AbstractMediaSessionService.ACTION_PAUSE))
                shadowOf(Looper.getMainLooper()).idle()

                assertEquals(1, facts.size)
                with(facts[0]) {
                    assertEquals(Component.FEATURE_MEDIA, component)
                    assertEquals(Action.PAUSE, action)
                    assertEquals(MediaFacts.Items.NOTIFICATION, item)
                }
            }
        }

    @Test
    fun `WHEN the delegate is created THEN its mediaSession is backed by the delegate's BrowserStorePlayer`() =
        runTest {
            val delegate = createDelegate(scope = this)

            // Media3 routes framework media-button events (lockscreen, Bluetooth, Android Auto) to
            // the Player bound to the MediaSession. Proving that wiring here lets BrowserStorePlayerTest
            // own the metadata/command assertions without duplicating them through the delegate.
            assertSame(delegate.player, delegate.mediaSession.player)
        }

    @Test
    fun `when device is becoming noisy, playback is paused`() = runTest {
        val controller: MozMediaSession.Controller = mock()
        val initialState =
            BrowserState(
                tabs =
                    listOf(
                        createTab(
                            "https://www.mozilla.org",
                            mediaSessionState = MediaSessionState(controller, playbackState = PlaybackState.PLAYING),
                        )
                    )
            )
        val store = BrowserStore(initialState)
        val service: AbstractMediaSessionService = mock()
        val delegate = createDelegate(service = service, store = store)
        delegate.onCreate()
        delegate.audioFocus = mock()
        delegate.handleMediaPlaying(initialState.tabs[0])

        delegate.deviceBecomingNoisy(testContext)

        verify(controller).pause()
    }

    @Test
    @Config(sdk = [31])
    fun `GIVEN device is at least API level 31 WHEN startForeground throws an exception THEN catch and pass the exception to the crash reporter`() =
        runTest {
            val crashReporter: CrashReporting = mock()
            val service: AbstractMediaSessionService = mock()
            val delegate = createDelegate(service = service, crashReporter = crashReporter, scope = this)
            delegate.onCreate()
            delegate.audioFocus = mock()
            val notification: Notification = mock()
            delegate.notificationHelper = coMock {
                doReturn(notification).`when`(this).create(mock(), delegate.mediaSession)
            }

            val exception = ForegroundServiceStartNotAllowedException("Test thrown exception")
            doThrow(exception).`when`(service).startForeground(anyInt(), any())

            delegate.startForeground(mock(), coroutineContext)
            testScheduler.advanceUntilIdle()

            verify(crashReporter).submitCaughtException(exception)
            verify(delegate.audioFocus, never()).request(any(), any())
        }

    @Test
    @Config(sdk = [30])
    fun `GIVEN device is less than 31 WHEN startForeground throws an exception THEN the exception is not swallowed and crash reporter is not notified`() =
        runTest {
            var caught: Throwable? = null
            val exceptionHandler = CoroutineExceptionHandler { _, t -> caught = t }

            val crashReporter: CrashReporting = mock()
            val service: AbstractMediaSessionService = mock()
            val delegate = createDelegate(service = service, crashReporter = crashReporter, scope = this)
            delegate.onCreate()
            delegate.audioFocus = mock()
            val notification: Notification = mock()
            delegate.notificationHelper = coMock {
                doReturn(notification).`when`(this).create(mock(), delegate.mediaSession)
            }

            val exception = RuntimeException("Test thrown exception")
            doThrow(exception).`when`(service).startForeground(anyInt(), any())

            delegate.startForeground(mock(), exceptionHandler)
            testScheduler.advanceUntilIdle()

            assertSame(exception, caught)
            verify(crashReporter, never()).submitCaughtException(any())
            verify(delegate.audioFocus, never()).request(any(), any())
        }

    private fun getMediaTab(playbackState: PlaybackState = PlaybackState.PLAYING) =
        createTab(
            title = "Mozilla",
            url = "https://www.mozilla.org",
            mediaSessionState = MediaSessionState(mock(), playbackState = playbackState),
        )
}
