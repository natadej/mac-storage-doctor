package com.natadej.storagedoctor.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

class PlanTest {
    private lateinit var root: File

    @Before fun setUp() { root = Files.createTempDirectory("plan").toFile() }
    @After fun tearDown() { root.deleteRecursively() }

    /** Sparse file: reports its full length without using real disk space. */
    private fun sparse(rel: String, size: Long) {
        val f = File(root, rel).apply { parentFile!!.mkdirs() }
        RandomAccessFile(f, "rw").use { it.setLength(size) }
    }

    @Test fun neededBytesMath() {
        assertEquals(20 * GB, neededBytes(200 * GB, 120 * GB, 50))
        assertEquals(0L, neededBytes(200 * GB, 80 * GB, 50))
    }

    @Test fun planLocksProtectedItemsAndRecommendsSafeOnes() {
        sparse("Download/old.apk", 300 * MB)
        sparse("Download/huge.zip", 900 * MB)
        sparse("Movies/.thumbnails/t.bin", 200 * MB)
        sparse("Android/media/com.whatsapp/WhatsApp/Media/v.mp4", 2 * GB)
        sparse("DCIM/Camera/VID_1.mp4", 1 * GB)
        val r = Scanner(root).run()
        val plan = buildPlan(r, Protection(root.path))
        val by = plan.associateBy { it.label }
        assertFalse(by.getValue("WhatsApp media").canDelete)
        assertFalse(by.getValue("Camera photos & videos").canDelete)
        assertTrue(by.getValue("App installer").canDelete)
        assertEquals(Safety.SAFE, plan.first().safety)
        // Large files inside a finding are not listed twice.
        assertEquals(1, plan.count { it.path.endsWith("VID_1.mp4") || it.label == "Camera photos & videos" })

        val pick = recommended(plan, 250 * MB)
        assertEquals(setOf(File(root, "Download/old.apk").path), pick)
        val all = recommended(plan, 10 * GB)
        assertEquals(2, all.size)
        assertTrue(all.none { it.endsWith("huge.zip") })
    }

    @Test fun removalUpdatesTotals() {
        sparse("Download/old.apk", 300 * MB)
        sparse("Pictures/big.mp4", 400 * MB)
        val r = Scanner(root).run()
        val before = r.scanned
        r.applyRemoval(File(root, "Download/old.apk").path, 300 * MB, "downloads", toTrash = false)
        assertEquals(0L, r.catTotals["downloads"])
        assertEquals(before - 300 * MB, r.scanned)
        assertTrue(r.findings.none { it.path.endsWith("old.apk") })
        assertEquals(0L, r.tree.dirs.first { it.name == "Download" }.size)
        r.applyRemoval(File(root, "Pictures/big.mp4").path, 400 * MB, "media", toTrash = true)
        assertEquals(400 * MB, r.catTotals["trash"])
        assertEquals(before - 300 * MB, r.scanned)
        assertTrue(r.largeFiles.isEmpty())
    }
}
