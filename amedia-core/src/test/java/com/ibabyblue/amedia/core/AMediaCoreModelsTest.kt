package com.ibabyblue.amedia.core

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AMediaCoreModelsTest {
    @Test
    fun queueValidationRejectsDuplicateEntryIdentity() {
        assertEquals(
            AMediaCommandRejection.DuplicateQueueEntryId,
            validateQueueRequest(
                entryIds = listOf("entry", "entry"),
                entryStartPositions = listOf(0L, 0L),
                startIndex = 0,
                startPositionMillis = null,
            ),
        )
    }

    @Test
    fun queueValidationAcceptsRepeatedMediaWithDistinctEntries() {
        assertNull(
            validateQueueRequest(
                entryIds = listOf("first", "second"),
                entryStartPositions = listOf(0L, 0L),
                startIndex = 1,
                startPositionMillis = 500L,
            ),
        )
    }

    @Test
    fun progressFractionIsClampedAndUnsetForUnknownDuration() {
        assertEquals(1f, AMediaProgress(positionMillis = 2_000, durationMillis = 1_000).fraction)
        assertNull(AMediaProgress(positionMillis = 20, durationMillis = null).fraction)
        assertNull(AMediaProgress(positionMillis = 20, durationMillis = 0).fraction)
    }

    @Test
    fun drmConfigurationComparesOfflineKeysByContent() {
        val first = AMediaDrmConfiguration(UUID.randomUUID(), offlineKeySetId = byteArrayOf(1, 2))
        val same = first.copy(offlineKeySetId = byteArrayOf(1, 2))
        val different = first.copy(offlineKeySetId = byteArrayOf(2, 1))

        assertEquals(first, same)
        assertEquals(first.hashCode(), same.hashCode())
        assertNotEquals(first, different)
    }
}
