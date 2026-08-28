package com.ibabyblue.amedia.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.ibabyblue.amedia.core.AMediaController
import com.ibabyblue.amedia.core.AMediaControllerConfiguration
import com.ibabyblue.amedia.core.AMediaControllerFactory
import com.ibabyblue.amedia.core.AMediaAudioAmplitudeConfiguration

/** Creates a foreground controller whose lifetime is owned by the current composition. */
@Composable
public fun rememberAMediaController(
    configuration: AMediaControllerConfiguration = AMediaControllerConfiguration(),
): AMediaController = rememberAMediaController(configuration, null)

/** Creates a foreground controller with opt-in decoded PCM audio amplitude levels. */
@Composable
public fun rememberAMediaController(
    configuration: AMediaControllerConfiguration = AMediaControllerConfiguration(),
    audioAmplitudeConfiguration: AMediaAudioAmplitudeConfiguration?,
): AMediaController {
    val context = LocalContext.current.applicationContext
    val controller = remember(context, configuration, audioAmplitudeConfiguration) {
        AMediaControllerFactory.create(
            context,
            configuration,
            audioAmplitudeConfiguration,
        )
    }
    DisposableEffect(controller) {
        onDispose(controller::release)
    }
    return controller
}
