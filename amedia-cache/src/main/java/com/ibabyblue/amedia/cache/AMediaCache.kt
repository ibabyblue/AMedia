@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.ibabyblue.amedia.cache

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.ibabyblue.amedia.core.AMediaItem
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

public data class AMediaCacheConfiguration(
    public val directoryName: String = "amedia",
    public val maxBytes: Long = 256L * 1024L * 1024L,
) {
    init {
        require(directoryName.isNotBlank())
        require(maxBytes > 0L)
    }
}

public class AMediaCache internal constructor(
    private val handle: SharedCacheHandle,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    internal val cache: SimpleCache
        get() = handle.cache
    public val directory: File
        get() = handle.directory

    public val sizeBytes: Long
        get() = if (closed.get()) 0L else synchronized(handle) { cache.cacheSpace }

    public fun remove(item: AMediaItem) {
        check(!closed.get()) { "AMediaCache is closed" }
        synchronized(handle) {
            cache.removeResource(item.http.cacheKey ?: item.uri.toString())
        }
    }

    public fun clear() {
        check(!closed.get()) { "AMediaCache is closed" }
        synchronized(handle) {
            cache.keys.toList().forEach(cache::removeResource)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) SharedCacheRegistry.release(handle)
    }

    internal fun retain(): AMediaCache {
        check(!closed.get()) { "AMediaCache is closed" }
        return AMediaCache(SharedCacheRegistry.retain(handle))
    }
}

public object AMediaCacheFactory {
    @JvmStatic
    @JvmOverloads
    public fun create(
        context: Context,
        configuration: AMediaCacheConfiguration = AMediaCacheConfiguration(),
    ): AMediaCache {
        val directory = File(context.applicationContext.cacheDir, configuration.directoryName)
        return AMediaCache(
            SharedCacheRegistry.acquire(context.applicationContext, directory, configuration.maxBytes),
        )
    }
}

internal class SharedCacheHandle(
    val key: String,
    val directory: File,
    val maxBytes: Long,
    val cache: SimpleCache,
    var references: Int,
)

private object SharedCacheRegistry {
    private val handles = mutableMapOf<String, SharedCacheHandle>()

    @Synchronized
    fun acquire(context: Context, directory: File, maxBytes: Long): SharedCacheHandle {
        val key = directory.canonicalFile.absolutePath
        handles[key]?.let { existing ->
            require(existing.maxBytes == maxBytes) {
                "Cache directory $key is already open with maxBytes=${existing.maxBytes}"
            }
            existing.references += 1
            return existing
        }
        val handle = SharedCacheHandle(
            key = key,
            directory = directory.canonicalFile,
            maxBytes = maxBytes,
            cache = SimpleCache(
                directory,
                LeastRecentlyUsedCacheEvictor(maxBytes),
                StandaloneDatabaseProvider(context),
            ),
            references = 1,
        )
        handles[key] = handle
        return handle
    }

    @Synchronized
    fun retain(handle: SharedCacheHandle): SharedCacheHandle {
        check(handles[handle.key] === handle) { "AMediaCache is closed" }
        handle.references += 1
        return handle
    }

    @Synchronized
    fun release(handle: SharedCacheHandle) {
        if (handles[handle.key] !== handle) return
        handle.references -= 1
        if (handle.references == 0) {
            handles.remove(handle.key)
            handle.cache.release()
        }
    }
}
