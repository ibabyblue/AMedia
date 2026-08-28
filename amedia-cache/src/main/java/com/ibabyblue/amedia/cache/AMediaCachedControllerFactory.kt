package com.ibabyblue.amedia.cache

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.MediaSource
import com.ibabyblue.amedia.core.AMediaAudioAmplitudeConfiguration
import com.ibabyblue.amedia.core.AMediaAudioAmplitudeSource
import com.ibabyblue.amedia.core.AMediaAudioPolicy
import com.ibabyblue.amedia.core.AMediaController
import com.ibabyblue.amedia.core.AMediaControllerConfiguration
import com.ibabyblue.amedia.core.AMediaQueueEntry
import com.ibabyblue.amedia.core.ExperimentalAMediaApi
import com.ibabyblue.amedia.core.audioAmplitudeSourceOrNull
import com.ibabyblue.amedia.core.interop.AMediaMedia3Interop
import java.util.concurrent.atomic.AtomicBoolean

public object AMediaCachedControllerFactory {
    @JvmStatic
    @JvmOverloads
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    @OptIn(ExperimentalAMediaApi::class)
    public fun create(
        context: Context,
        cache: AMediaCache,
        configuration: AMediaControllerConfiguration = AMediaControllerConfiguration(),
        userAgent: String = "AMedia/0.1",
    ): AMediaController = createInternal(context, cache, configuration, userAgent, null)

    @JvmStatic
    public fun create(
        context: Context,
        cache: AMediaCache,
        configuration: AMediaControllerConfiguration = AMediaControllerConfiguration(),
        userAgent: String = "AMedia/0.1",
        audioAmplitudeConfiguration: AMediaAudioAmplitudeConfiguration?,
    ): AMediaController = createInternal(
        context,
        cache,
        configuration,
        userAgent,
        audioAmplitudeConfiguration,
    )

    @OptIn(ExperimentalAMediaApi::class)
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    private fun createInternal(
        context: Context,
        cache: AMediaCache,
        configuration: AMediaControllerConfiguration,
        userAgent: String,
        audioAmplitudeConfiguration: AMediaAudioAmplitudeConfiguration?,
    ): AMediaController {
        val appContext = context.applicationContext
        val cacheLease = AMediaCacheMedia3Interop.retain(cache)
        val mediaSourceProvider: (AMediaQueueEntry) -> MediaSource = { entry ->
            AMediaCacheMedia3Interop.mediaSource(appContext, cacheLease, entry, userAgent)
        }
        val amplitudeRenderersFactory: RenderersFactory? = audioAmplitudeConfiguration?.let {
            AMediaMedia3Interop.audioAmplitudeRenderersFactory(appContext, it)
        }
        val handlesAudioFocus = configuration.audioPolicy == AMediaAudioPolicy.Media
        val playerBuilder = ExoPlayer.Builder(appContext)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_UNKNOWN)
                    .build(),
                handlesAudioFocus,
            )
            .setHandleAudioBecomingNoisy(
                handlesAudioFocus && configuration.handleAudioBecomingNoisy,
            )
        amplitudeRenderersFactory?.let(playerBuilder::setRenderersFactory)
        val player = playerBuilder.build()
        val delegate = AMediaMedia3Interop.wrap(
            player,
            configuration,
            releasePlayer = true,
            mediaSourceProvider = mediaSourceProvider,
            audioAmplitudeRenderersFactory = amplitudeRenderersFactory,
        )
        val audioAmplitudeSource = delegate.audioAmplitudeSourceOrNull()
        return if (audioAmplitudeSource == null) {
            CachedAMediaController(delegate, cacheLease)
        } else {
            CachedAudioAmplitudeAMediaController(delegate, cacheLease, audioAmplitudeSource)
        }
    }
}

@OptIn(ExperimentalAMediaApi::class)
private open class CachedAMediaController(
    private val delegate: AMediaController,
    private val cacheLease: AMediaCache,
) : AMediaController by delegate {
    private val released = AtomicBoolean(false)

    open override fun release() {
        if (!released.compareAndSet(false, true)) return
        val looper = AMediaMedia3Interop.player(delegate)?.applicationLooper
        val action = {
            delegate.release()
            cacheLease.close()
        }
        if (looper == null || Looper.myLooper() == looper) action() else Handler(looper).post(action)
    }
}

private class CachedAudioAmplitudeAMediaController(
    delegate: AMediaController,
    cacheLease: AMediaCache,
    source: AMediaAudioAmplitudeSource,
) : CachedAMediaController(delegate, cacheLease), AMediaAudioAmplitudeSource by source
