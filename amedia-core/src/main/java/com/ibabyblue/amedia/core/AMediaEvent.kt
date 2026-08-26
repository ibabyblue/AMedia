package com.ibabyblue.amedia.core

public sealed interface AMediaEvent {
    public val entryId: String?
    public val generation: Long

    public data class MediaChanged(
        override val entryId: String?,
        override val generation: Long,
    ) : AMediaEvent

    public data class FirstFrameRendered(
        override val entryId: String,
        override val generation: Long,
    ) : AMediaEvent

    public data class PlaybackEnded(
        override val entryId: String,
        override val generation: Long,
    ) : AMediaEvent

    public data class SeekCompleted(
        override val entryId: String,
        override val generation: Long,
        val positionMillis: Long,
    ) : AMediaEvent

    public data class RetryScheduled(
        override val entryId: String,
        override val generation: Long,
        val attempt: Int,
        val delayMillis: Long,
    ) : AMediaEvent

    public data class Failed(
        override val entryId: String?,
        override val generation: Long,
        val error: AMediaError,
    ) : AMediaEvent
}

public sealed interface AMediaCommandResult {
    public data object Accepted : AMediaCommandResult
    public data class Rejected(public val reason: AMediaCommandRejection) : AMediaCommandResult
}

public enum class AMediaCommandRejection {
    Released,
    Unsupported,
    InvalidArgument,
    EmptyQueue,
    DuplicateQueueEntryId,
    EntryNotFound,
}
