@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.ibabyblue.amedia.cache

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** A logical cache space. Named spaces share one global capacity while isolating resource keys. */
public sealed interface AMediaCacheSpace {
    /** Backwards-compatible space used by [AMediaCacheFactory]. */
    public data object Default : AMediaCacheSpace

    /** A 1-64 character ASCII identifier containing letters, digits, `.`, `_`, or `-`. */
    public data class Named(public val name: String) : AMediaCacheSpace {
        init {
            require(NAME_PATTERN.matches(name)) {
                "Cache space names must match ${NAME_PATTERN.pattern}"
            }
        }
    }

    private companion object {
        val NAME_PATTERN: Regex = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    }
}

/** Selects spaces for a cross-space maintenance operation. */
public sealed interface AMediaCacheScope {
    public data object All : AMediaCacheScope

    public data class Spaces(public val spaces: Set<AMediaCacheSpace>) : AMediaCacheScope {
        init {
            require(spaces.isNotEmpty())
        }
    }
}

public data class AMediaCacheSpaceSnapshot(
    public val space: AMediaCacheSpace,
    public val sizeBytes: Long,
    public val resourceCount: Int,
)

public data class AMediaCacheSnapshot(
    public val sizeBytes: Long,
    public val maxBytes: Long,
    public val spaces: List<AMediaCacheSpaceSnapshot>,
)

/**
 * Owns a shared cache store. Opened spaces have independent keys and maintenance while automatic
 * LRU eviction is enforced across the store's single [maxBytes] budget.
 */
public class AMediaCacheManager internal constructor(
    private val handle: SharedCacheHandle,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    public val directory: File
        get() = handle.directory
    public val maxBytes: Long
        get() = handle.maxBytes

    /** The caller owns and must close the returned cache handle. */
    @JvmOverloads
    public fun open(space: AMediaCacheSpace = AMediaCacheSpace.Default): AMediaCache {
        check(!closed.get()) { "AMediaCacheManager is closed" }
        return AMediaCache(SharedCacheRegistry.retain(handle), space)
    }

    /**
     * Takes a synchronous, point-in-time snapshot of occupied spaces. Run large inspections away
     * from the main thread.
     */
    public fun snapshot(): AMediaCacheSnapshot {
        check(!closed.get()) { "AMediaCacheManager is closed" }
        return synchronized(handle) {
            val snapshots = handle.cache.keys
                .groupBy(AMediaCacheKeyCodec::spaceOf)
                .map { (space, storageKeys) ->
                    AMediaCacheSpaceSnapshot(
                        space = space,
                        sizeBytes = storageKeys.sumOf { storageKey ->
                            handle.cache.getCachedSpans(storageKey).sumOf { span -> span.length }
                        },
                        resourceCount = storageKeys.size,
                    )
                }
                .sortedWith(
                    compareBy<AMediaCacheSpaceSnapshot> { it.space !is AMediaCacheSpace.Default }
                        .thenBy { (it.space as? AMediaCacheSpace.Named)?.name.orEmpty() },
                )
            AMediaCacheSnapshot(
                sizeBytes = snapshots.sumOf(AMediaCacheSpaceSnapshot::sizeBytes),
                maxBytes = handle.maxBytes,
                spaces = snapshots,
            )
        }
    }

    /** Applies one policy across all or selected spaces and returns the bytes removed. */
    @JvmOverloads
    public fun clear(
        scope: AMediaCacheScope = AMediaCacheScope.All,
        policy: AMediaCacheClearPolicy = AMediaCacheClearPolicy.All,
    ): Long {
        check(!closed.get()) { "AMediaCacheManager is closed" }
        val spaces = when (scope) {
            AMediaCacheScope.All -> null
            is AMediaCacheScope.Spaces -> scope.spaces
        }
        return handle.clear(spaces, policy)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) SharedCacheRegistry.release(handle)
    }
}

public object AMediaCacheManagerFactory {
    @JvmStatic
    @JvmOverloads
    public fun create(
        context: Context,
        configuration: AMediaCacheConfiguration = AMediaCacheConfiguration(),
    ): AMediaCacheManager {
        val directory = File(context.applicationContext.cacheDir, configuration.directoryName)
        return AMediaCacheManager(
            SharedCacheRegistry.acquire(context.applicationContext, directory, configuration.maxBytes),
        )
    }
}

internal object AMediaCacheKeyCodec {
    private const val NAMED_PREFIX = "__amedia_space_v1__/"
    private const val DEFAULT_ESCAPE_PREFIX = "__amedia_default_v1__/"

    fun storageKey(space: AMediaCacheSpace, rawKey: String): String = when (space) {
        AMediaCacheSpace.Default -> if (
            rawKey.startsWith(NAMED_PREFIX) || rawKey.startsWith(DEFAULT_ESCAPE_PREFIX)
        ) {
            DEFAULT_ESCAPE_PREFIX + rawKey
        } else {
            rawKey
        }
        is AMediaCacheSpace.Named -> "$NAMED_PREFIX${space.name}/$rawKey"
    }

    fun rawKey(space: AMediaCacheSpace, storageKey: String): String? = when (space) {
        AMediaCacheSpace.Default -> when {
            storageKey.startsWith(DEFAULT_ESCAPE_PREFIX) ->
                storageKey.removePrefix(DEFAULT_ESCAPE_PREFIX)
            spaceOf(storageKey) == AMediaCacheSpace.Default -> storageKey
            else -> null
        }
        is AMediaCacheSpace.Named -> storageKey.removePrefix(prefix(space)).takeIf {
            storageKey.startsWith(prefix(space))
        }
    }

    fun belongsTo(space: AMediaCacheSpace, storageKey: String): Boolean = when (space) {
        AMediaCacheSpace.Default -> spaceOf(storageKey) == AMediaCacheSpace.Default
        is AMediaCacheSpace.Named -> storageKey.startsWith(prefix(space))
    }

    fun spaceOf(storageKey: String): AMediaCacheSpace {
        if (storageKey.startsWith(DEFAULT_ESCAPE_PREFIX)) return AMediaCacheSpace.Default
        if (!isNamed(storageKey)) return AMediaCacheSpace.Default
        val name = storageKey.removePrefix(NAMED_PREFIX).substringBefore('/')
        return runCatching { AMediaCacheSpace.Named(name) }.getOrDefault(AMediaCacheSpace.Default)
    }

    private fun prefix(space: AMediaCacheSpace.Named): String = "$NAMED_PREFIX${space.name}/"
    private fun isNamed(storageKey: String): Boolean = storageKey.startsWith(NAMED_PREFIX)
}
