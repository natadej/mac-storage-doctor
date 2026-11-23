package com.natadej.storagedoctor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaRequestBatchesTest {
    @Test fun batchBoundariesPreserveEveryItemInOrder() {
        for ((count, expectedSizes) in listOf(
            0 to emptyList(), 1 to listOf(1), 2000 to listOf(2000),
            2001 to listOf(2000, 1), 4500 to listOf(2000, 2000, 500),
        )) {
            val items = (0 until count).toList()
            val queue = MediaRequestBatches(items) { 10L }
            val confirmed = mutableListOf<Int>()
            val sizes = mutableListOf<Int>()
            while (queue.current.isNotEmpty()) {
                sizes += queue.current.size
                assertTrue(queue.current.size <= MEDIA_REQUEST_LIMIT)
                confirmed += queue.confirmCurrent()
            }
            assertEquals(expectedSizes, sizes)
            assertEquals(items, confirmed)
            assertEquals(count, queue.completedCount)
            assertEquals(count * 10L, queue.completedBytes)
            assertEquals(0, queue.remainingCount)
            assertTrue(queue.confirmCurrent().isEmpty())
        }
    }

    @Test fun cancellationBeforeFirstConfirmationChangesNothing() {
        val queue = MediaRequestBatches((1..4500).toList()) { it.toLong() }
        queue.stop()
        assertTrue(queue.current.isEmpty())
        assertTrue(queue.confirmCurrent().isEmpty())
        assertEquals(0, queue.completedCount)
        assertEquals(0L, queue.completedBytes)
        assertEquals(4500, queue.remainingCount)
    }

    @Test fun cancellationPreservesOnlyEarlierConfirmedBatches() {
        val queue = MediaRequestBatches((1..4500).toList()) { 2L }
        assertEquals((1..2000).toList(), queue.confirmCurrent())
        queue.stop()
        assertTrue(queue.current.isEmpty())
        assertTrue(queue.confirmCurrent().isEmpty())
        assertEquals(2000, queue.completedCount)
        assertEquals(4000L, queue.completedBytes)
        assertEquals(2500, queue.remainingCount)
    }

    @Test fun requestCreationFailureStopsWithoutCountingTheFailedBatch() {
        val queue = MediaRequestBatches((1..4500).toList()) { 3L }
        queue.confirmCurrent()
        queue.confirmCurrent()
        queue.stop()
        assertTrue(queue.current.isEmpty())
        assertEquals(4000, queue.completedCount)
        assertEquals(12000L, queue.completedBytes)
        assertEquals(500, queue.remainingCount)
    }
}
