package com.ibabyblue.amedia.preload

import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import org.junit.Assert.assertEquals
import org.junit.Test

class AMediaPreloadPolicyTest {
    @Test
    fun `loads adjacent entries and only selects tracks farther away`() {
        val control = OrderedTargetPreloadStatusControl(
            AMediaPreloadConfiguration(
                forwardLoadedDurationMillis = 4_000L,
                backwardLoadedDurationMillis = 1_000L,
                trackSelectionDistance = 2,
            ),
        ).apply { anchorIndex = 4 }

        assertEquals(
            DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(4_000L),
            control.getTargetPreloadStatus(5),
        )
        assertEquals(
            DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(1_000L),
            control.getTargetPreloadStatus(3),
        )
        assertEquals(
            DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED,
            control.getTargetPreloadStatus(6),
        )
        assertEquals(
            DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED,
            control.getTargetPreloadStatus(7),
        )
    }

    @Test
    fun `disabled control does not preload`() {
        val control = OrderedTargetPreloadStatusControl(AMediaPreloadConfiguration())
            .apply {
                anchorIndex = 1
                enabled = false
            }

        assertEquals(
            DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED,
            control.getTargetPreloadStatus(2),
        )
    }
}
