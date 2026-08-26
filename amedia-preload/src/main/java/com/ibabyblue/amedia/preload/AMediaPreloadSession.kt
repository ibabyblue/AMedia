package com.ibabyblue.amedia.preload

import com.ibabyblue.amedia.core.AMediaCommandResult
import com.ibabyblue.amedia.core.AMediaController
import com.ibabyblue.amedia.core.AMediaQueueEntry
import kotlinx.coroutines.flow.StateFlow

public data class AMediaPreloadState(
    public val entryIds: List<String> = emptyList(),
    public val anchorEntryId: String? = null,
    public val preloadingEnabled: Boolean = true,
)

/**
 * Owns one playback controller and its matching preloader. Entries are ordered only for preload
 * priority; this interface has no knowledge of pages, feeds, or application models.
 */
public interface AMediaPreloadSession : AutoCloseable {
    /**
     * Transport, state, tracks, and output controller for the session-owned player. Media-set
     * replacement is owned by [update] and [play]; callers use this controller for other commands.
     */
    public val playback: AMediaController
    public val state: StateFlow<AMediaPreloadState>

    /** Incrementally synchronizes the ordered media set while preserving unchanged sources. */
    public fun update(entries: List<AMediaQueueEntry>): AMediaCommandResult

    /** Reprioritizes preloading without changing playback. Pass `null` to clear the hint. */
    public fun hint(entryId: String?): AMediaCommandResult

    /** Plays an entry through its preloaded source when available. */
    public fun play(
        entryId: String,
        startPositionMillis: Long = 0L,
        playWhenReady: Boolean = true,
    ): AMediaCommandResult

    public fun setPreloadingEnabled(enabled: Boolean): AMediaCommandResult
    public fun release()

    override fun close(): Unit = release()
}
