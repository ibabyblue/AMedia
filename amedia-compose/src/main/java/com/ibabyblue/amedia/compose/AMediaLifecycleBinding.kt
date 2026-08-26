package com.ibabyblue.amedia.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ibabyblue.amedia.core.AMediaController
import com.ibabyblue.amedia.core.AMediaBackgroundPlaybackPolicy
import com.ibabyblue.amedia.core.AMediaContentType
import com.ibabyblue.amedia.core.AMediaSuppressionReason
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Explicitly binds a controller's temporary playback suppressions to a visible screen. It does not
 * own or release the controller.
 */
@Composable
public fun AMediaLifecycleBinding(
    controller: AMediaController,
    screenVisible: Boolean = true,
    businessOverlayVisible: Boolean = false,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by controller.state.collectAsStateWithLifecycle()
    val backgroundPolicy = state.currentEntry?.item?.backgroundPlaybackPolicy
        ?: AMediaBackgroundPlaybackPolicy.Default
    val contentType = state.currentEntry?.item?.metadata?.contentType ?: AMediaContentType.Unknown

    LaunchedEffect(controller, screenVisible) {
        if (screenVisible) {
            controller.removeSuppression(AMediaSuppressionReason.ScreenHidden)
        } else {
            controller.addSuppression(AMediaSuppressionReason.ScreenHidden)
        }
    }
    LaunchedEffect(controller, businessOverlayVisible) {
        if (businessOverlayVisible) {
            controller.addSuppression(AMediaSuppressionReason.BusinessOverlay)
        } else {
            controller.removeSuppression(AMediaSuppressionReason.BusinessOverlay)
        }
    }

    DisposableEffect(
        controller,
        lifecycle,
        backgroundPolicy,
        contentType,
        state.capabilities.supportsBackgroundPlayback,
    ) {
        fun synchronizeLifecycle() {
            val canContinueInBackground = when (backgroundPolicy) {
                AMediaBackgroundPlaybackPolicy.Pause -> false
                AMediaBackgroundPlaybackPolicy.Continue,
                AMediaBackgroundPlaybackPolicy.ContinueAudio,
                -> true
                AMediaBackgroundPlaybackPolicy.Default ->
                    state.capabilities.supportsBackgroundPlayback &&
                        contentType != AMediaContentType.Video
            }
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || canContinueInBackground) {
                controller.removeSuppression(AMediaSuppressionReason.Lifecycle)
            } else {
                controller.addSuppression(AMediaSuppressionReason.Lifecycle)
            }
        }

        val observer = LifecycleEventObserver { _, _ -> synchronizeLifecycle() }
        lifecycle.addObserver(observer)
        synchronizeLifecycle()
        onDispose {
            lifecycle.removeObserver(observer)
            controller.addSuppression(AMediaSuppressionReason.Lifecycle)
        }
    }
}
