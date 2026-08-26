package com.ibabyblue.amedia.core

/** A single occurrence of media in a queue. entryId must be unique within that queue. */
public data class AMediaQueueEntry(
    public val entryId: String,
    public val item: AMediaItem,
    public val startPositionMillis: Long = 0L,
) {
    init {
        require(entryId.isNotBlank()) { "entryId must not be blank" }
        require(startPositionMillis >= 0L) { "startPositionMillis must be non-negative" }
    }
}
