package com.ibabyblue.amedia.compose.material3

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
public data class AMediaControlsStyle(
    public val contentColor: Color = Color.White,
    public val disabledContentColor: Color = Color.White.copy(alpha = 0.38f),
    public val overlayColor: Color = Color.Black.copy(alpha = 0.42f),
    public val progressColor: Color = Color.White,
    public val bufferedProgressColor: Color = Color.White.copy(alpha = 0.45f),
    public val iconSize: Dp = 28.dp,
    public val centerIconSize: Dp = 54.dp,
    public val horizontalPadding: Dp = 16.dp,
    public val controlsAutoHideMillis: Long = 3_000L,
    public val doubleTapSeekMillis: Long = 10_000L,
) {
    init {
        require(controlsAutoHideMillis >= 0L)
        require(doubleTapSeekMillis >= 0L)
    }
}
