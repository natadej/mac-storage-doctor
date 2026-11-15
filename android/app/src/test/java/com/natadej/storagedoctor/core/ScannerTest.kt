package com.natadej.storagedoctor.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

class ScannerTest {
    private lateinit var root: File

    @Before fun setUp() { root = Files.createTempDirectory("sd").toFile() }
    @After fun tearDown() { root.deleteRecursively() }

    private fun write(rel: String, size: Int = 0, ageDays: Int = 0, bytes: ByteArray? = null): File {
        val f = File(root, rel)
        f.parentFile!!.mkdirs()
        f.writeBytes(bytes ?: Random.nextBytes(size))
        if (ageDays > 0) f.setLastModified(System.currentTimeMillis() - ageDays * 86_400_000L)
        return f
    }

    private fun scan() = Scanner(root).run()

    @Test fun namePatternCollapsesVariableParts() {
        assertEquals("step_#.png", namePattern("step_00012.png"))
        assertEquals("{id}.json", namePattern("3f2a9c1e-1b2c-4d5e-8f90-123456789abc.json"))
        assertEquals("readme.md", namePattern("readme.md"))
    }

    @Test fun classifiesSharedStorage() {
        write("DCIM/Camera/IMG_1.jpg", 2000)
        write("Android/media/com.whatsapp/WhatsApp/Media/VID-1.mp4", 3000)
        write("Download/app.apk", 1000)
        write("Download/models/llama.gguf", 5000)
        write("Music/song.mp3", 700)
        write("random/notes.pdf", 400)
        write("random/blob.bin", 100)
        write("Android/data/com.app/secret.bin", 9999)
        val r = scan()
        assertEquals(2000L, r.catTotals["media"])
        assertEquals(3000L, r.catTotals["messaging"])
        assertEquals(1000L, r.catTotals["downloads"])
        assertEquals(5000L, r.catTotals["ai"])
        assertEquals(700L, r.catTotals["audio"])
        assertEquals(400L, r.catTotals["docs"])
        assertEquals(100L, r.catTotals["other"])
        assertEquals("Android/data is skipped", 12200L, r.scanned)
    }

    @Test fun findingsCarrySafetyAndDeletability() {
        write("DCIM/Camera/IMG_1.jpg", 60 * 1000 * 1000 / 1000) // small file, folder below min size
        write("Pictures/.thumbnails/t1.jpg", 11 * 1000 * 1000)
        write("Download/big.apk", 11 * 1000 * 1000)
        val r = scan()
        val thumbs = r.findings.first { it.label == "Gallery thumbnail cache" }
        assertEquals(Safety.SAFE, thumbs.safety)
        assertTrue(thumbs.isDir)
        val apk = r.findings.first { it.label == "App installer" }
        assertFalse(apk.isDir)
        assertTrue(r.findings.none { it.label == "Camera photos & videos" })
    }

    @Test fun detectsRepeatedNamesBurstAndNesting() {
        repeat(350) { write("Pictures/AgentBot/shot_%05d.png".format(it), 10) }
        repeat(3100) { write("Documents/runs/run-${it / 100}/out_$it.json", 1) }
        File(root, "build/out/build/out/build/out/build/out/build").mkdirs()
        val r = scan()
        assertEquals(3, r.runaway.size)
        val types = r.runaway.associateBy { it.type }
        assertTrue(types.getValue("pattern").path.endsWith("Pictures/AgentBot"))
        assertEquals("shot_#.png", types.getValue("pattern").detail.substringAfter("'").substringBefore("'"))
        assertTrue(types.getValue("burst").path.endsWith("Documents/runs"))
        assertTrue(types.getValue("nesting").path.contains("build/out/build"))
    }

    @Test fun cameraFolderIsNotARunawaySuspect() {
        repeat(400) { write("DCIM/Camera/IMG_20240101_%06d.jpg".format(it), 10) }
        repeat(400) { write("Notes/note-$it.txt", 10, ageDays = 30) }
        assertTrue(scan().runaway.isEmpty())
    }

    @Test fun largeFilesAndSnapshot() {
        write("Movies/trip.mp4", (LARGE_FILE_MIN + 1).toInt())
        val r = scan()
        assertEquals("trip.mp4", r.largeFiles.single().name)
        assertEquals("media", r.largeFiles.single().category)
        assertTrue(r.snapshot.keys.any { it.endsWith("/Movies") })
    }

    @Test fun findsDuplicateContent() {
        val data = Random.nextBytes(2_000_000)
        write("Download/a.mp4", bytes = data)
        write("DCIM/b.mp4", bytes = data)
        write("Download/c.mp4", bytes = Random.nextBytes(2_000_000))
        val groups = findDuplicates(scan().dupCandidates)
        assertEquals(1, groups.size)
        assertEquals(setOf("a.mp4", "b.mp4"), groups[0].files.map { it.name }.toSet())
        assertEquals(2_000_000L, groups[0].wasted)
    }

    @Test fun growthOfFolderMissingFromBaselineIsALowerBound() {
        val prev = ScanRecord(0, 0, mapOf("/s/a" to 200 * MB), mapOf("com.x" to 100 * MB))
        val g = growthSince(prev, mapOf("/s/a" to 400 * MB, "/s/runs" to 175 * MB), mapOf("com.x" to 400 * MB))!!
        val by = g.folders.associateBy { it.key }
        assertEquals(200 * MB, by.getValue("/s/a").growth)
        assertEquals(155 * MB, by.getValue("/s/runs").growth)
        assertNull(by.getValue("/s/runs").was)
        assertEquals(300 * MB, g.apps.single().growth)
    }

    @Test fun protectedPaths() {
        val c = Protection(root.path)
        assertFalse(c.canDelete(root.path))
        assertFalse(c.canDelete(File(root, "DCIM").path))
        assertFalse(c.canDelete(File(root, "Android/media/com.whatsapp").path))
        assertFalse(c.canDelete(File(root, "Android/media/com.whatsapp/x.jpg").path))
        assertFalse(c.canDelete(File(root, "DCIM/Camera").path))
        assertTrue(c.canDelete(File(root, "DCIM/Camera/IMG_1.jpg").path))
        assertTrue(c.canDelete(File(root, "Download/old.zip").path))
        assertTrue(c.canDelete(File(root, "Android/media/org.telegram.messenger").path))
        assertFalse(c.canDelete(File(root, "Android/media").path))
        assertFalse(c.canDelete(File(root, "Android/data/com.x/files/a.bin").path))
        assertFalse(c.canDelete("/data/data/x"))
    }
}
