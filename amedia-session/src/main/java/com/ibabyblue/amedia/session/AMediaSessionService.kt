@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.ibabyblue.amedia.session

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import com.ibabyblue.amedia.core.AMediaController
import com.ibabyblue.amedia.core.AMediaBackgroundPlaybackPolicy
import com.ibabyblue.amedia.core.AMediaContentType
import com.ibabyblue.amedia.core.AMediaControllerConfiguration
import com.ibabyblue.amedia.core.AMediaControllerFactory
import com.ibabyblue.amedia.core.ExperimentalAMediaApi
import com.ibabyblue.amedia.core.interop.AMediaMedia3Interop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future

/**
 * Base service for background AMedia playback. Applications declare their concrete subclass in the
 * manifest and may replace the controller configuration and session callback.
 */
public abstract class AMediaSessionService : MediaSessionService() {
    private var session: MediaSession? = null
    private var sessionPlayer: Player? = null
    private var resumeOnForeground = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    protected lateinit var amediaController: AMediaController
        private set
    private val processLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            if (resumeOnForeground) {
                resumeOnForeground = false
                sessionPlayer?.play()
            }
        }

        override fun onStop(owner: LifecycleOwner) {
            applyBackgroundPlaybackPolicy()
        }
    }

    protected open fun controllerConfiguration(): AMediaControllerConfiguration =
        AMediaControllerConfiguration(supportsBackgroundPlayback = true)

    protected open fun createController(): AMediaController =
        AMediaControllerFactory.create(this, controllerConfiguration())

    protected open fun createSessionCallback(): MediaSession.Callback = object : MediaSession.Callback {}

    protected open fun playbackResumptionProvider(): AMediaPlaybackResumptionProvider? = null

    protected open fun mediaNotificationProvider(): MediaNotification.Provider? = null

    protected open fun idleNotificationPolicy(): AMediaIdleNotificationPolicy =
        AMediaIdleNotificationPolicy.AfterStopOrError

    protected open fun configureSession(builder: MediaSession.Builder): MediaSession.Builder = builder

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    @OptIn(ExperimentalAMediaApi::class)
    override fun onCreate() {
        super.onCreate()
        setShowNotificationForIdlePlayer(idleNotificationPolicy().toMedia3Policy())
        mediaNotificationProvider()?.let(::setMediaNotificationProvider)
        amediaController = createController()
        val player = checkNotNull(AMediaMedia3Interop.player(amediaController)) {
            "AMediaSessionService requires a Media3-backed AMediaController"
        }
        sessionPlayer = player
        val appCallback = createSessionCallback()
        val provider = playbackResumptionProvider()
        val callback = if (provider == null) {
            appCallback
        } else {
            ResumptionCallback(appCallback, provider, serviceScope)
        }
        session = configureSession(MediaSession.Builder(this, player).setCallback(callback)).build()
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
        session?.release()
        session = null
        sessionPlayer = null
        if (::amediaController.isInitialized) amediaController.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    @OptIn(ExperimentalAMediaApi::class)
    private fun applyBackgroundPlaybackPolicy() {
        val player = sessionPlayer ?: return
        val item = player.currentMediaItem ?: return
        val policy = AMediaMedia3Interop.backgroundPlaybackPolicy(item)
        val shouldPause = policy == AMediaBackgroundPlaybackPolicy.Pause ||
            policy == AMediaBackgroundPlaybackPolicy.Default &&
            AMediaMedia3Interop.contentType(item) == AMediaContentType.Video
        if (shouldPause && player.playWhenReady) {
            resumeOnForeground = true
            player.pause()
        } else if (policy == AMediaBackgroundPlaybackPolicy.ContinueAudio) {
            player.clearVideoSurface()
        }
    }
}

@OptIn(ExperimentalAMediaApi::class)
private class ResumptionCallback(
    private val delegate: MediaSession.Callback,
    private val provider: AMediaPlaybackResumptionProvider,
    private val scope: CoroutineScope,
) : MediaSession.Callback by delegate {
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = load()

    private fun load(): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
        val resumption = provider.load()
            ?: throw IllegalStateException("No persisted AMedia playback is available")
        MediaSession.MediaItemsWithStartPosition(
            resumption.entries.map(AMediaMedia3Interop::mediaItem),
            resumption.startIndex,
            resumption.startPositionMillis,
        )
    }
}

private fun AMediaIdleNotificationPolicy.toMedia3Policy(): Int = when (this) {
    AMediaIdleNotificationPolicy.Always -> MediaSessionService.SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS
    AMediaIdleNotificationPolicy.AfterStopOrError ->
        MediaSessionService.SHOW_NOTIFICATION_FOR_IDLE_PLAYER_AFTER_STOP_OR_ERROR
    AMediaIdleNotificationPolicy.Never -> MediaSessionService.SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER
}
