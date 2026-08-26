package com.ibabyblue.amedia.session

import com.ibabyblue.amedia.core.AMediaQueueEntry

public data class AMediaPlaybackResumption(
    public val entries: List<AMediaQueueEntry>,
    public val startIndex: Int = 0,
    public val startPositionMillis: Long = 0L,
) {
    init {
        require(entries.isNotEmpty()) { "entries must not be empty" }
        require(startIndex in entries.indices) { "startIndex must address an entry" }
        require(startPositionMillis >= 0L) { "startPositionMillis must be non-negative" }
    }
}

public fun interface AMediaPlaybackResumptionProvider {
    /** Loads the most recently persisted queue. Return null when no playback can be resumed. */
    public suspend fun load(): AMediaPlaybackResumption?
}

public enum class AMediaIdleNotificationPolicy {
    Always,
    AfterStopOrError,
    Never,
}
