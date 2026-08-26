package com.ibabyblue.amedia.core.interop

import androidx.media3.common.Player
import com.ibabyblue.amedia.core.AMediaController
import com.ibabyblue.amedia.core.AMediaControllerConfiguration
import com.ibabyblue.amedia.core.ExperimentalAMediaApi
import com.ibabyblue.amedia.core.Media3AMediaController

/** Escape hatch for integrations that must own or inspect the underlying Media3 player. */
@ExperimentalAMediaApi
public object AMediaMedia3Interop {
    @JvmStatic
    public fun wrap(
        player: Player,
        configuration: AMediaControllerConfiguration = AMediaControllerConfiguration(),
        releasePlayer: Boolean = false,
    ): AMediaController = Media3AMediaController(player, configuration, releasePlayer)

    @JvmStatic
    public fun player(controller: AMediaController): Player? =
        (controller as? Media3AMediaController)?.player
}
