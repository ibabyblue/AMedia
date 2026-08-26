package com.ibabyblue.amedia.core

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

public interface AMediaController : AutoCloseable {
    public val state: StateFlow<AMediaState>
    public val progress: StateFlow<AMediaProgress>
    public val events: SharedFlow<AMediaEvent>

    public fun setMedia(
        item: AMediaItem,
        startPositionMillis: Long = 0L,
        playWhenReady: Boolean? = null,
    ): AMediaCommandResult

    public fun setQueue(
        entries: List<AMediaQueueEntry>,
        startIndex: Int = 0,
        startPositionMillis: Long? = null,
        playWhenReady: Boolean? = null,
    ): AMediaCommandResult

    public fun add(entry: AMediaQueueEntry, index: Int = state.value.queue.size): AMediaCommandResult
    public fun move(entryId: String, toIndex: Int): AMediaCommandResult
    public fun remove(entryId: String): AMediaCommandResult
    public fun prepare(): AMediaCommandResult
    public fun play(): AMediaCommandResult
    public fun pause(): AMediaCommandResult
    public fun stop(): AMediaCommandResult
    public fun clear(): AMediaCommandResult
    public fun retry(): AMediaCommandResult
    public fun seekTo(positionMillis: Long): AMediaCommandResult
    public fun seekToEntry(entryId: String, positionMillis: Long = 0L): AMediaCommandResult
    public fun skipToPrevious(): AMediaCommandResult
    public fun skipToNext(): AMediaCommandResult
    public fun setMuted(muted: Boolean): AMediaCommandResult
    public fun setVolume(volume: Float): AMediaCommandResult
    public fun setSpeed(speed: Float): AMediaCommandResult
    public fun setRepeatMode(repeatMode: AMediaRepeatMode): AMediaCommandResult
    public fun setSubtitlesEnabled(enabled: Boolean): AMediaCommandResult
    public fun selectTrack(trackId: String?): AMediaCommandResult
    public fun addSuppression(reason: AMediaSuppressionReason): AMediaCommandResult
    public fun removeSuppression(reason: AMediaSuppressionReason): AMediaCommandResult
    public fun release()

    override fun close(): Unit = release()
}
