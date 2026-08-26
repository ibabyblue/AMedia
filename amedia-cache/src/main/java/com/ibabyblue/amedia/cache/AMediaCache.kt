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

/** Explicit maintenance operations in addition to automatic LRU eviction. */
public sealed interface AMediaCacheClearPolicy {
    /** Remove every cached resource. */
    public data object All : AMediaCacheClearPolicy

    /** Remove cache spans whose last access is older than [cutoffEpochMillis]. */
    public data class OlderThan(public val cutoffEpochMillis: Long) : AMediaCacheClearPolicy {
        init {
            require(cutoffEpochMillis >= 0L)
        }
    }

    /** Remove least-recently-used spans until the cache occupies at most [maxBytes]. */
    public data class TrimToBytes(public val maxBytes: Long) : AMediaCacheClearPolicy {
        init {
            require(maxBytes >= 0L)
        }
    }
}

public class AMediaCache internal constructor(
    private val handle: SharedCacheHandle,
    public val space: AMediaCacheSpace = AMediaCacheSpace.Default,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    internal val cache: SimpleCache
        get() = handle.cache
    public val directory: File
        get() = handle.directory
    /** Global byte limit of the physical store shared by this space. */
    public val maxBytes: Long
        get() = handle.maxBytes

    public val sizeBytes: Long
        get() = if (closed.get()) 0L else synchronized(handle) {
            handle.storageKeys(space).sumOf { storageKey ->
                cache.getCachedSpans(storageKey).sumOf { span -> span.length }
            }
        }

    public val keys: Set<String>
        get() = if (closed.get()) emptySet() else synchronized(handle) {
            handle.storageKeys(space)
                .mapNotNullTo(linkedSetOf()) { AMediaCacheKeyCodec.rawKey(space, it) }
        }

    public fun cachedBytes(item: AMediaItem): Long = cachedBytes(cacheKey(item))

    public fun cachedBytes(cacheKey: String): Long {
        require(cacheKey.isNotBlank())
        check(!closed.get()) { "AMediaCache is closed" }
        return synchronized(handle) {
            cache.getCachedSpans(storageKey(cacheKey)).sumOf { span -> span.length }
        }
    }

    public fun remove(item: AMediaItem) {
        remove(cacheKey(item))
    }

    public fun remove(cacheKey: String) {
        require(cacheKey.isNotBlank())
        check(!closed.get()) { "AMediaCache is closed" }
        synchronized(handle) {
            cache.removeResource(storageKey(cacheKey))
        }
    }

    /** Removes the selected resources atomically with respect to other AMedia maintenance calls. */
    public fun remove(cacheKeys: Collection<String>): Long {
        require(cacheKeys.all(String::isNotBlank))
        check(!closed.get()) { "AMediaCache is closed" }
        return synchronized(handle) {
            val before = sizeBytes
            cacheKeys.distinct().forEach { cacheKey ->
                cache.removeResource(storageKey(cacheKey))
            }
            (before - sizeBytes).coerceAtLeast(0L)
        }
    }

    public fun removeItems(items: Collection<AMediaItem>): Long =
        remove(items.map(::cacheKey))

    public fun clear() {
        clear(AMediaCacheClearPolicy.All)
    }

    /**
     * Applies [policy] synchronously and returns the number of bytes removed. Callers should run
     * large maintenance operations away from the main thread.
     */
    public fun clear(policy: AMediaCacheClearPolicy): Long {
        check(!closed.get()) { "AMediaCache is closed" }
        return handle.clear(setOf(space), policy)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) SharedCacheRegistry.release(handle)
    }

    internal fun retain(): AMediaCache {
        check(!closed.get()) { "AMediaCache is closed" }
        return AMediaCache(SharedCacheRegistry.retain(handle), space)
    }

    private fun cacheKey(item: AMediaItem): String = item.http.cacheKey ?: item.uri.toString()

    internal fun storageKey(cacheKey: String): String = AMediaCacheKeyCodec.storageKey(space, cacheKey)
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
            AMediaCacheSpace.Default,
        )
    }
}

internal class SharedCacheHandle(
    val key: String,
    val directory: File,
    val maxBytes: Long,
    val cache: SimpleCache,
    var references: Int,
) {
    fun storageKeys(space: AMediaCacheSpace): List<String> =
        cache.keys.filter { AMediaCacheKeyCodec.belongsTo(space, it) }

    fun clear(spaces: Set<AMediaCacheSpace>?, policy: AMediaCacheClearPolicy): Long = synchronized(this) {
        val selectedKeys = cache.keys.filter { storageKey ->
            spaces == null || AMediaCacheKeyCodec.spaceOf(storageKey) in spaces
        }
        val before = selectedKeys.sumOf { storageKey ->
            cache.getCachedSpans(storageKey).sumOf { span -> span.length }
        }
        when (policy) {
            AMediaCacheClearPolicy.All -> selectedKeys.forEach(cache::removeResource)
            is AMediaCacheClearPolicy.OlderThan -> selectedKeys
                .flatMap(cache::getCachedSpans)
                .filter { span -> span.lastTouchTimestamp < policy.cutoffEpochMillis }
                .forEach(cache::removeSpan)
            is AMediaCacheClearPolicy.TrimToBytes -> {
                var remainingBytes = before
                selectedKeys
                    .flatMap(cache::getCachedSpans)
                    .sortedBy { span -> span.lastTouchTimestamp }
                    .forEach { span ->
                        if (remainingBytes > policy.maxBytes) {
                            cache.removeSpan(span)
                            remainingBytes -= span.length
                        }
                    }
            }
        }
        val after = selectedKeys.sumOf { storageKey ->
            cache.getCachedSpans(storageKey).sumOf { span -> span.length }
        }
        (before - after).coerceAtLeast(0L)
    }
}

internal object SharedCacheRegistry {
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
