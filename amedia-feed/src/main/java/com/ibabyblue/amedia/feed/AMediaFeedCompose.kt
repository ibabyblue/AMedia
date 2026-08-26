package com.ibabyblue.amedia.feed

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import com.ibabyblue.amedia.compose.AMediaLifecycleBinding
import com.ibabyblue.amedia.core.AMediaControllerConfiguration
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
public fun rememberAMediaFeedController(
    configuration: AMediaFeedConfiguration = AMediaFeedConfiguration(),
    controllerConfiguration: AMediaControllerConfiguration = AMediaControllerConfiguration(),
): AMediaFeedController {
    val context = LocalContext.current.applicationContext
    val controller = remember(context, configuration, controllerConfiguration) {
        AMediaFeedControllerFactory.create(context, configuration, controllerConfiguration)
    }
    DisposableEffect(controller) {
        onDispose(controller::release)
    }
    return controller
}

/**
 * Activates video only after [PagerState.settledPage] changes. Mixed-media hosts return `null` for
 * image pages and map video pages to the submitted feed-entry index.
 */
@Composable
public fun AMediaFeedPagerBinding(
    controller: AMediaFeedController,
    pagerState: PagerState,
    screenVisible: Boolean = true,
    businessOverlayVisible: Boolean = false,
    entryIndexForPage: (page: Int) -> Int?,
) {
    val latestIndexMapping = rememberUpdatedState(entryIndexForPage)
    AMediaLifecycleBinding(
        controller = controller.playback,
        screenVisible = screenVisible,
        businessOverlayVisible = businessOverlayVisible,
    )
    LaunchedEffect(controller, pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val entryIndex = latestIndexMapping.value(page)
                if (entryIndex == null) controller.deactivate() else controller.activate(entryIndex)
            }
    }
}
