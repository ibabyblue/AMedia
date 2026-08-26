package com.ibabyblue.amedia.session

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.ibabyblue.amedia.core.AMediaController
import com.ibabyblue.amedia.core.AMediaControllerConfiguration
import com.ibabyblue.amedia.core.ExperimentalAMediaApi
import com.ibabyblue.amedia.core.interop.AMediaMedia3Interop
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

public sealed interface AMediaSessionConnectionState {
    public data object Connecting : AMediaSessionConnectionState
    public data class Connected(public val controller: AMediaController) : AMediaSessionConnectionState
    public data class Failed(public val cause: Throwable) : AMediaSessionConnectionState
    public data object Closed : AMediaSessionConnectionState
}

public class AMediaSessionConnection private constructor(
    private val future: ListenableFuture<MediaController>,
    private val controllerConfiguration: AMediaControllerConfiguration,
    executor: java.util.concurrent.Executor,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val mutableState = MutableStateFlow<AMediaSessionConnectionState>(
        AMediaSessionConnectionState.Connecting,
    )
    public val state: StateFlow<AMediaSessionConnectionState> = mutableState.asStateFlow()

    init {
        future.addListener(
            {
                if (closed.get()) return@addListener
                try {
                    mutableState.value = AMediaSessionConnectionState.Connected(
                        wrap(future.get()),
                    )
                } catch (throwable: Throwable) {
                    mutableState.value = AMediaSessionConnectionState.Failed(throwable)
                }
            },
            executor,
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        (mutableState.value as? AMediaSessionConnectionState.Connected)?.controller?.release()
        MediaController.releaseFuture(future)
        mutableState.value = AMediaSessionConnectionState.Closed
    }

    @OptIn(ExperimentalAMediaApi::class)
    private fun wrap(controller: MediaController): AMediaController = AMediaMedia3Interop.wrap(
        player = controller,
        configuration = controllerConfiguration.copy(supportsBackgroundPlayback = true),
        releasePlayer = false,
    )

    public companion object {
        @JvmStatic
        @JvmOverloads
        @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
        public fun connect(
            context: Context,
            serviceClass: Class<out MediaSessionService>,
            controllerConfiguration: AMediaControllerConfiguration = AMediaControllerConfiguration(
                supportsBackgroundPlayback = true,
            ),
        ): AMediaSessionConnection {
            val appContext = context.applicationContext
            val token = SessionToken(appContext, ComponentName(appContext, serviceClass))
            val future = MediaController.Builder(appContext, token).buildAsync()
            return AMediaSessionConnection(
                future = future,
                controllerConfiguration = controllerConfiguration,
                executor = ContextCompat.getMainExecutor(appContext),
            )
        }
    }
}
