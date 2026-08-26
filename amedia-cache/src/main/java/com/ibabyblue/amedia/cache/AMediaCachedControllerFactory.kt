package com.ibabyblue.amedia.cache

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.ibabyblue.amedia.core.AMediaAudioPolicy
import com.ibabyblue.amedia.core.AMediaCachePolicy
import com.ibabyblue.amedia.core.AMediaController
import com.ibabyblue.amedia.core.AMediaControllerConfiguration
import com.ibabyblue.amedia.core.AMediaQueueEntry
import com.ibabyblue.amedia.core.ExperimentalAMediaApi
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
    ): AMediaController {
        val appContext = context.applicationContext
        val cacheLease = cache.retain()
        val mediaSourceProvider: (AMediaQueueEntry) -> MediaSource = { entry ->
            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setDefaultRequestProperties(entry.item.http.headers)
            val upstreamFactory = DefaultDataSource.Factory(appContext, httpFactory)
            val dataSourceFactory: DataSource.Factory =
                if (entry.item.cachePolicy == AMediaCachePolicy.ReadThrough) {
                    CacheDataSource.Factory()
                        .setCache(cacheLease.cache)
                        .setUpstreamDataSourceFactory(upstreamFactory)
                        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                } else {
                    upstreamFactory
                }
            DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(
                AMediaMedia3Interop.mediaItem(entry),
            )
        }
        val handlesAudioFocus = configuration.audioPolicy == AMediaAudioPolicy.Media
        val player = ExoPlayer.Builder(appContext)
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
            .build()
        val delegate = AMediaMedia3Interop.wrap(
            player,
            configuration,
            releasePlayer = true,
            mediaSourceProvider = mediaSourceProvider,
        )
        return CachedAMediaController(delegate, cacheLease)
    }
}

@OptIn(ExperimentalAMediaApi::class)
private class CachedAMediaController(
    private val delegate: AMediaController,
    private val cacheLease: AMediaCache,
) : AMediaController by delegate {
    private val released = AtomicBoolean(false)

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        val looper = AMediaMedia3Interop.player(delegate)?.applicationLooper
        val action = {
            delegate.release()
            cacheLease.close()
        }
        if (looper == null || Looper.myLooper() == looper) action() else Handler(looper).post(action)
    }
}
