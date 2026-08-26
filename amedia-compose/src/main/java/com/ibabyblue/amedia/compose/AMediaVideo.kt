package com.ibabyblue.amedia.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.ibabyblue.amedia.core.AMediaController
import com.ibabyblue.amedia.core.ExperimentalAMediaApi
import com.ibabyblue.amedia.core.interop.AMediaMedia3Interop

public enum class AMediaSurfaceType {
    SurfaceView,
    TextureView,
}

public enum class AMediaContentScale {
    Fit,
    Crop,
    Fill,
    FixedWidth,
    FixedHeight,
}

/**
 * Renders the video output owned by [controller]. This composable never releases an externally
 * supplied controller. The surface always remains in the composition so first-frame delivery
 * cannot deadlock behind a conditional placeholder.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@OptIn(ExperimentalAMediaApi::class)
@Composable
public fun AMediaVideo(
    controller: AMediaController,
    modifier: Modifier = Modifier,
    surfaceType: AMediaSurfaceType = AMediaSurfaceType.SurfaceView,
    contentScale: AMediaContentScale = AMediaContentScale.Fit,
    keepContentOnReset: Boolean = false,
    keepScreenOnWhilePlaying: Boolean = true,
    shutter: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().background(Color.Black))
    },
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val player = AMediaMedia3Interop.player(controller)
    val view = LocalView.current

    DisposableEffect(view, state.isPlaying, state.capabilities.hasVideoOutput, keepScreenOnWhilePlaying) {
        val previous = view.keepScreenOn
        view.keepScreenOn = keepScreenOnWhilePlaying &&
            state.isPlaying &&
            state.capabilities.hasVideoOutput
        onDispose { view.keepScreenOn = previous }
    }

    ContentFrame(
        player = player,
        modifier = modifier,
        surfaceType = surfaceType.toMedia3SurfaceType(),
        contentScale = contentScale.toComposeContentScale(),
        keepContentOnReset = keepContentOnReset,
        shutter = shutter,
    )
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
private fun AMediaSurfaceType.toMedia3SurfaceType(): Int = when (this) {
    AMediaSurfaceType.SurfaceView -> SURFACE_TYPE_SURFACE_VIEW
    AMediaSurfaceType.TextureView -> SURFACE_TYPE_TEXTURE_VIEW
}

private fun AMediaContentScale.toComposeContentScale(): ContentScale = when (this) {
    AMediaContentScale.Fit -> ContentScale.Fit
    AMediaContentScale.Crop -> ContentScale.Crop
    AMediaContentScale.Fill -> ContentScale.FillBounds
    AMediaContentScale.FixedWidth -> ContentScale.FillWidth
    AMediaContentScale.FixedHeight -> ContentScale.FillHeight
}
