@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.ibabyblue.amedia.cache

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.ibabyblue.amedia.core.AMediaCachePolicy
import com.ibabyblue.amedia.core.AMediaQueueEntry
import com.ibabyblue.amedia.core.ExperimentalAMediaApi
import com.ibabyblue.amedia.core.interop.AMediaMedia3Interop

/** Media3 seam used by AMedia implementation modules. Applications normally do not need this. */
@ExperimentalAMediaApi
public object AMediaCacheMedia3Interop {
    @JvmStatic
    public fun retain(cache: AMediaCache): AMediaCache = cache.retain()

    @JvmStatic
    @JvmOverloads
    public fun mediaSource(
        context: Context,
        cache: AMediaCache,
        entry: AMediaQueueEntry,
        userAgent: String = "AMedia/0.1",
    ): MediaSource {
        val storageEntry = entry.withStorageCacheKey(cache)
        return DefaultMediaSourceFactory(
            createDataSourceFactory(context, cache, entry, userAgent),
        ).createMediaSource(AMediaMedia3Interop.mediaItem(storageEntry))
    }

    private fun createDataSourceFactory(
        context: Context,
        cache: AMediaCache,
        entry: AMediaQueueEntry,
        userAgent: String,
    ): DataSource.Factory {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(entry.item.http.headers)
        val upstreamFactory = DefaultDataSource.Factory(context.applicationContext, httpFactory)
        return when (entry.item.cachePolicy) {
            AMediaCachePolicy.None -> upstreamFactory
            AMediaCachePolicy.ReadOnly -> CacheDataSource.Factory()
                .setCache(cache.cache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setCacheWriteDataSinkFactory(null)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            AMediaCachePolicy.ReadThrough -> CacheDataSource.Factory()
                .setCache(cache.cache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            AMediaCachePolicy.CacheOnly -> CacheDataSource.Factory()
                .setCache(cache.cache)
                .setUpstreamDataSourceFactory(null)
                .setCacheWriteDataSinkFactory(null)
        }
    }

    private fun AMediaQueueEntry.withStorageCacheKey(cache: AMediaCache): AMediaQueueEntry {
        if (item.cachePolicy == AMediaCachePolicy.None) return this
        val publicCacheKey = item.http.cacheKey ?: item.uri.toString()
        return copy(
            item = item.copy(
                http = item.http.copy(cacheKey = cache.storageKey(publicCacheKey)),
            ),
        )
    }
}
