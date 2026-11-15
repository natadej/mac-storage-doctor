package com.natadej.storagedoctor.core

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

data class DupGroup(val size: Long, val files: List<FileEntry>) {
    val wasted get() = size * (files.size - 1)
}

private const val CHUNK = 64 * 1024

private fun partialHash(f: File, size: Long): String {
    val md = MessageDigest.getInstance("SHA-1")
    RandomAccessFile(f, "r").use { raf ->
        val buf = ByteArray(CHUNK)
        for (pos in listOf(0L, size / 2, maxOf(0L, size - CHUNK))) {
            raf.seek(pos)
            val n = raf.read(buf)
            if (n > 0) md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

private fun fullHash(f: File): String {
    val md = MessageDigest.getInstance("SHA-1")
    f.inputStream().buffered(1 shl 20).use { s ->
        val buf = ByteArray(1 shl 20)
        while (true) { val n = s.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** Identical files by size, then a partial hash, then a full hash. */
fun findDuplicates(candidates: List<Pair<String, Long>>, progress: (Int, Int) -> Unit = { _, _ -> }, limit: Int = 200): List<DupGroup> {
    val bySize = candidates.groupBy({ it.second }, { it.first }).filter { it.value.size > 1 }
    val total = bySize.values.sumOf { it.size }
    var done = 0
    val groups = mutableListOf<DupGroup>()
    for ((size, paths) in bySize) {
        val byPartial = paths.groupBy { p -> runCatching { partialHash(File(p), size) }.getOrNull() }
        for ((ph, same) in byPartial) {
            if (ph == null || same.size < 2) continue
            val byFull = same.groupBy { p -> if (size <= 3L * CHUNK) ph else runCatching { fullHash(File(p)) }.getOrNull() }
            for ((fh, dup) in byFull) {
                if (fh == null || dup.size < 2) continue
                groups += DupGroup(size, dup.map { p ->
                    val f = File(p)
                    FileEntry(p, f.name, size, f.lastModified(), categoryByExtension(f.name) ?: "other", false)
                }.sortedBy { it.mtime })
            }
        }
        done += paths.size
        progress(done, total)
    }
    return groups.sortedByDescending { it.wasted }.take(limit)
}
