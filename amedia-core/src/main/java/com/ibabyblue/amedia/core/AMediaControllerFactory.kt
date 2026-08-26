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
    ): AMediaController {
        val handlesAudioFocus = configuration.audioPolicy == AMediaAudioPolicy.Media
        val player = ExoPlayer.Builder(context.applicationContext)
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
            .build()

        return Media3AMediaController(
            player = player,
            configuration = configuration,
            releasePlayer = true,
        )
    }
}
