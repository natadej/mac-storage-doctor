package com.natadej.storagedoctor.core

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

data class DupGroup(val size: Long, val files: List<FileEntry>) {
    val wasted get() = size * (files.size - 1)
}

private const val CHUNK = 64 * 1024

private fun partialHash(f: File, size: Long, checkCancelled: () -> Unit): String {
    val md = MessageDigest.getInstance("SHA-1")
    RandomAccessFile(f, "r").use { raf ->
        val buf = ByteArray(CHUNK)
        for (pos in listOf(0L, size / 2, maxOf(0L, size - CHUNK))) {
            checkCancelled()
            raf.seek(pos)
            val n = raf.read(buf)
            if (n > 0) md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

private fun fullHash(f: File, checkCancelled: () -> Unit): String {
    val md = MessageDigest.getInstance("SHA-1")
    f.inputStream().buffered(1 shl 20).use { s ->
        val buf = ByteArray(1 shl 20)
        while (true) {
            checkCancelled()
            val n = s.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** Identical files by size, then a partial hash, then a full hash. */
fun findDuplicates(candidates: List<Pair<String, Long>>, progress: (Int, Int) -> Unit = { _, _ -> }, limit: Int = 200,
                   checkCancelled: () -> Unit = {}): List<DupGroup> {
    checkCancelled()
    val bySize = candidates.groupBy({ it.second }, { it.first }).filter { it.value.size > 1 }
    val total = bySize.values.sumOf { it.size }
    var done = 0
    val groups = mutableListOf<DupGroup>()
    for ((size, paths) in bySize) {
        checkCancelled()
        val byPartial = paths.groupBy { p ->
            checkCancelled()
            readableHash { partialHash(File(p), size, checkCancelled) }
        }
        for ((ph, same) in byPartial) {
            checkCancelled()
            if (ph == null || same.size < 2) continue
            val byFull = same.groupBy { p ->
                checkCancelled()
                if (size <= 3L * CHUNK) ph else readableHash { fullHash(File(p), checkCancelled) }
            }
            for ((fh, dup) in byFull) {
                checkCancelled()
                if (fh == null || dup.size < 2) continue
                groups += DupGroup(size, dup.map { p ->
                    val f = File(p)
                    FileEntry(p, f.name, size, f.lastModified(), categoryByExtension(f.name) ?: "other", false)
                }.sortedBy { it.mtime })
            }
        }
        done += paths.size
        checkCancelled()
        progress(done, total)
    }
    checkCancelled()
    return groups.sortedByDescending { it.wasted }.take(limit)
}

private fun readableHash(hash: () -> String): String? = try {
    hash()
} catch (_: IOException) {
    null
} catch (_: SecurityException) {
    null
}
