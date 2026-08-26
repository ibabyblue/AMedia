package com.ibabyblue.amedia.compose.material3

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ibabyblue.amedia.compose.AMediaContentScale
import com.ibabyblue.amedia.compose.AMediaSurfaceType
import com.ibabyblue.amedia.compose.AMediaVideo
import com.ibabyblue.amedia.core.AMediaController
import com.ibabyblue.amedia.core.AMediaPlaybackStatus
import com.ibabyblue.amedia.core.AMediaTrackType
import kotlinx.coroutines.delay

@Composable
public fun AMediaMaterialPlayer(
    controller: AMediaController,
    modifier: Modifier = Modifier,
    style: AMediaControlsStyle = AMediaControlsStyle(),
    surfaceType: AMediaSurfaceType = AMediaSurfaceType.SurfaceView,
    contentScale: AMediaContentScale = AMediaContentScale.Fit,
    onFullscreen: (() -> Unit)? = null,
    onPictureInPicture: (() -> Unit)? = null,
    shutter: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().background(Color.Black))
    },
    overlay: @Composable (AMediaController) -> Unit = {},
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val progress by controller.progress.collectAsStateWithLifecycle()
    val accessibilityManager = LocalContext.current
        .getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    var controlsVisible by remember { mutableStateOf(true) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(
        controlsVisible,
        state.isPlaying,
        state.status,
        dragging,
        accessibilityManager.isEnabled,
        style.controlsAutoHideMillis,
    ) {
        val shouldHide = controlsVisible &&
            state.isPlaying &&
            state.status != AMediaPlaybackStatus.Failed &&
            !dragging &&
            !accessibilityManager.isEnabled &&
            style.controlsAutoHideMillis > 0L
        if (shouldHide) {
            delay(style.controlsAutoHideMillis)
            controlsVisible = false
        }
    }

    Box(
        modifier = modifier.pointerInput(controller, style.doubleTapSeekMillis) {
            detectTapGestures(
                onTap = { controlsVisible = !controlsVisible },
                onDoubleTap = { offset ->
                    seekFromDoubleTap(controller, progress.positionMillis, offset, size.width, style)
                    controlsVisible = true
                },
            )
        },
    ) {
        AMediaVideo(
            controller = controller,
            modifier = Modifier.fillMaxSize(),
            surfaceType = surfaceType,
            contentScale = contentScale,
            shutter = shutter,
        )

        AnimatedVisibility(visible = controlsVisible, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(style.overlayColor)) {
                PlaybackCenterControl(
                    controller = controller,
                    status = state.status,
                    isPlaying = state.isPlaying,
                    style = style,
                    modifier = Modifier.align(Alignment.Center),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = style.horizontalPadding, vertical = 8.dp),
                ) {
                    val displayedFraction = if (dragging) dragFraction else progress.fraction ?: 0f
                    val seekDescription = stringResource(R.string.amedia_seek_position)
                    Slider(
                        value = displayedFraction,
                        onValueChange = {
                            dragging = true
                            dragFraction = it
                        },
                        onValueChangeFinished = {
                            val duration = progress.durationMillis
                            if (duration != null) controller.seekTo((duration * dragFraction).toLong())
                            dragging = false
                            controlsVisible = true
                        },
                        enabled = state.capabilities.canSeek && progress.durationMillis != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = seekDescription
                            },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${formatTime(progress.positionMillis)} / ${formatTime(progress.durationMillis)}",
                            color = style.contentColor,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PlaybackSpeedButton(controller, state.speed, style)
                            SubtitleButton(
                                controller,
                                state.subtitlesEnabled,
                                state.tracks.any { it.type == AMediaTrackType.Text },
                                style,
                            )
                            MuteButton(controller, state.isMuted, style)
                            if (onPictureInPicture != null && state.capabilities.supportsPictureInPicture) {
                                IconButton(onClick = onPictureInPicture) {
                                    Icon(
                                        Icons.Default.PictureInPictureAlt,
                                        stringResource(R.string.amedia_picture_in_picture),
                                        tint = style.contentColor,
                                    )
                                }
                            }
                            if (onFullscreen != null) {
                                IconButton(onClick = onFullscreen) {
                                    Icon(
                                        Icons.Default.Fullscreen,
                                        stringResource(R.string.amedia_fullscreen),
                                        tint = style.contentColor,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        overlay(controller)
    }
}

@Composable
private fun PlaybackCenterControl(
    controller: AMediaController,
    status: AMediaPlaybackStatus,
    isPlaying: Boolean,
    style: AMediaControlsStyle,
    modifier: Modifier,
) {
    when (status) {
        AMediaPlaybackStatus.Preparing,
        AMediaPlaybackStatus.Buffering,
        -> CircularProgressIndicator(modifier = modifier, color = style.contentColor)
        AMediaPlaybackStatus.Failed -> IconButton(
            onClick = controller::retry,
            modifier = modifier.size(style.centerIconSize),
        ) {
            Icon(Icons.Default.Refresh, stringResource(R.string.amedia_retry), tint = style.contentColor)
        }
        else -> IconButton(
            onClick = { if (isPlaying) controller.pause() else controller.play() },
            modifier = modifier.size(style.centerIconSize),
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = stringResource(if (isPlaying) R.string.amedia_pause else R.string.amedia_play),
                tint = style.contentColor,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun MuteButton(controller: AMediaController, muted: Boolean, style: AMediaControlsStyle) {
    IconButton(onClick = { controller.setMuted(!muted) }) {
        Icon(
            imageVector = if (muted) {
                Icons.AutoMirrored.Filled.VolumeOff
            } else {
                Icons.AutoMirrored.Filled.VolumeUp
            },
            contentDescription = stringResource(if (muted) R.string.amedia_unmute else R.string.amedia_mute),
            tint = style.contentColor,
        )
    }
}

@Composable
private fun SubtitleButton(
    controller: AMediaController,
    enabled: Boolean,
    available: Boolean,
    style: AMediaControlsStyle,
) {
    if (!available) return
    IconButton(onClick = { controller.setSubtitlesEnabled(!enabled) }) {
        Icon(
            imageVector = Icons.Default.ClosedCaption,
            contentDescription = stringResource(
                if (enabled) R.string.amedia_subtitles_on else R.string.amedia_subtitles_off,
            ),
            tint = if (enabled) style.contentColor else style.disabledContentColor,
        )
    }
}

@Composable
private fun PlaybackSpeedButton(
    controller: AMediaController,
    speed: Float,
    style: AMediaControlsStyle,
) {
    val label = formatSpeed(speed)
    val description = stringResource(R.string.amedia_speed, label)
    IconButton(onClick = { controller.setSpeed(nextSpeed(speed)) }) {
        Text(
            text = label,
            color = style.contentColor,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.semantics {
                contentDescription = description
            },
        )
    }
}

private fun seekFromDoubleTap(
    controller: AMediaController,
    currentPositionMillis: Long,
    offset: Offset,
    width: Int,
    style: AMediaControlsStyle,
) {
    if (width <= 0) return
    val delta = if (offset.x < width / 2f) -style.doubleTapSeekMillis else style.doubleTapSeekMillis
    controller.seekTo((currentPositionMillis + delta).coerceAtLeast(0L))
}

private fun formatTime(milliseconds: Long?): String {
    if (milliseconds == null) return "--:--"
    val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1_000L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}

private fun nextSpeed(current: Float): Float = when {
    current < 1f -> 1f
    current < 1.25f -> 1.25f
    current < 1.5f -> 1.5f
    current < 2f -> 2f
    else -> 1f
}

private fun formatSpeed(speed: Float): String = if (speed % 1f == 0f) {
    "${speed.toInt()}×"
} else {
    "$speed×"
}
