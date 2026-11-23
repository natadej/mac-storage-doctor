package com.natadej.storagedoctor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CancellationException

class DuplicatesCancellationTest {
    @get:Rule val folder = TemporaryFolder()

    private fun pair(size: Int, prefix: String): List<Pair<String, Long>> = listOf("a", "b").map {
        val file = folder.newFile("$prefix-$it.bin")
        file.writeBytes(ByteArray(size))
        file.path to size.toLong()
    }

    @Test fun emptyInputHasNoProgress() {
        var updates = 0
        assertTrue(findDuplicates(emptyList(), progress = { _, _ -> updates++ }).isEmpty())
        assertEquals(0, updates)
    }

    @Test fun cancellationBeforeStartingPublishesNoProgress() {
        var updates = 0
        assertThrows(CancellationException::class.java) {
            findDuplicates(pair(100, "small"), progress = { _, _ -> updates++ },
                checkCancelled = { throw CancellationException() })
        }
        assertEquals(0, updates)
    }

    @Test fun cancellationDuringHashingIsNotSwallowed() {
        val files = pair(8 * 1024 * 1024, "large")
        var checks = 0
        var updates = 0
        assertThrows(CancellationException::class.java) {
            findDuplicates(files, progress = { _, _ -> updates++ }, checkCancelled = {
                checks++
                if (checks >= 20) throw CancellationException()
            })
        }
        assertEquals(20, checks)
        assertEquals(0, updates)
    }

    @Test fun cancellationAfterProgressStopsFurtherUpdates() {
        val files = pair(100, "first") + pair(200, "second")
        var cancelled = false
        val progress = mutableListOf<Int>()
        assertThrows(CancellationException::class.java) {
            findDuplicates(files, progress = { done, _ -> progress += done; cancelled = true },
                checkCancelled = { if (cancelled) throw CancellationException() })
        }
        assertEquals(listOf(2), progress)
    }

    @Test fun unreadableFilesAreSkippedWithoutFailingComparison() {
        val paths = listOf("missing-a", "missing-b").map { folder.root.resolve(it).path to 100L }
        assertTrue(findDuplicates(paths).isEmpty())
    }

    @Test fun normalComparisonStillReportsDuplicatesAndProgress() {
        val progress = mutableListOf<Int>()
        val result = findDuplicates(pair(300000, "normal"), progress = { done, _ -> progress += done })
        assertEquals(2, result.single().files.size)
        assertEquals(listOf(2), progress)
    }
}
