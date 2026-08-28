@file:androidx.annotation.OptIn(
    markerClass = [androidx.media3.common.util.UnstableApi::class],
)

package com.ibabyblue.amedia.core

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer

public object AMediaControllerFactory {
    @JvmStatic
    @JvmOverloads
    public fun create(
        context: Context,
        configuration: AMediaControllerConfiguration = AMediaControllerConfiguration(),
    ): AMediaController = createInternal(context, configuration, null)

    @JvmStatic
    public fun create(
        context: Context,
        configuration: AMediaControllerConfiguration = AMediaControllerConfiguration(),
        audioAmplitudeConfiguration: AMediaAudioAmplitudeConfiguration?,
    ): AMediaController = createInternal(
        context,
        configuration,
        audioAmplitudeConfiguration,
    )

    private fun createInternal(
        context: Context,
        configuration: AMediaControllerConfiguration,
        audioAmplitudeConfiguration: AMediaAudioAmplitudeConfiguration?,
    ): AMediaController {
        val appContext = context.applicationContext
        val amplitudeRenderersFactory = audioAmplitudeConfiguration?.let { configuration ->
            AMediaAudioAmplitudeRenderersFactory(appContext, configuration)
        }
        val handlesAudioFocus = configuration.audioPolicy == AMediaAudioPolicy.Media
        val playerBuilder = ExoPlayer.Builder(appContext)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(
                        if (configuration.audioPolicy == AMediaAudioPolicy.Ambient) {
                            C.AUDIO_CONTENT_TYPE_MUSIC
                        } else {
                            C.AUDIO_CONTENT_TYPE_UNKNOWN
                        },
                    )
                    .build(),
                handlesAudioFocus,
            )
            .setHandleAudioBecomingNoisy(
                handlesAudioFocus && configuration.handleAudioBecomingNoisy,
            )
        amplitudeRenderersFactory?.let(playerBuilder::setRenderersFactory)
        val player = playerBuilder.build()

        return Media3AMediaController(
            player = player,
            configuration = configuration,
            releasePlayer = true,
            audioAmplitudeSource = amplitudeRenderersFactory?.runtime,
            mediaSourceProvider = { entry ->
                createDefaultMediaSource(appContext, entry)
            },
        )
    }
}
