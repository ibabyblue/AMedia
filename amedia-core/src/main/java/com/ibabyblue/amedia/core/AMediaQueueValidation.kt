package com.ibabyblue.amedia.core

internal fun validateQueueRequest(
    entryIds: List<String>,
    entryStartPositions: List<Long>,
    startIndex: Int,
    startPositionMillis: Long?,
): AMediaCommandRejection? = when {
    entryIds.isEmpty() -> null
    entryIds.toSet().size != entryIds.size ->
        AMediaCommandRejection.DuplicateQueueEntryId
    startIndex !in entryIds.indices -> AMediaCommandRejection.InvalidArgument
    (startPositionMillis ?: entryStartPositions[startIndex]) < 0L ->
        AMediaCommandRejection.InvalidArgument
    else -> null
}
