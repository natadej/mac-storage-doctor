package com.natadej.storagedoctor.core

import java.io.File
import java.nio.file.Files

const val LARGE_FILE_MIN = 100 * MB
const val DUP_CANDIDATE_MIN = 1 * MB
const val RECENT_DAYS = 7
const val RUNAWAY_MIN_FILES = 300
const val RUNAWAY_MIN_SHARE = 0.5
const val BURST_MIN_RECENT_FILES = 3000
const val NEST_REPEAT = 4
const val SNAPSHOT_MIN = 20 * MB

private val UUID_RE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", RegexOption.IGNORE_CASE)
private val HEX_RE = Regex("(?<![a-z])[0-9a-f]{12,}(?![a-z])", RegexOption.IGNORE_CASE)
private val DIGITS_RE = Regex("\\d+")

/** Collapse the variable parts of a file name: "out_0012.png" -> "out_#.png". */
fun namePattern(name: String): String =
    DIGITS_RE.replace(HEX_RE.replace(UUID_RE.replace(name, "{id}"), "{id}"), "#")

class Node(val name: String, val path: String) {
    var size = 0L
    var files = 0
    var recentFiles = 0
    var recentBytes = 0L
    var newest = 0L
    var maxChildRecent = 0
    val dirs = mutableListOf<Node>()
}

data class Finding(
    val path: String, val label: String, val category: String, val size: Long, val files: Int,
    val newest: Long, val safety: Safety, val advice: String, val deletable: Boolean, val isDir: Boolean,
)

data class FileEntry(val path: String, val name: String, val size: Long, val mtime: Long, val category: String, val inFinding: Boolean)

data class Runaway(
    val type: String, val path: String, val files: Int, val size: Long, val recentFiles: Int,
    val newest: Long, val detail: String, val sample: String = "", val score: Double = 0.0,
)

data class Hotspot(val path: String, val recentBytes: Long, val recentFiles: Int, val size: Long)

class ScanResult(
    val root: String,
    val time: Long,
    val tree: Node,
    val catTotals: MutableMap<String, Long>,
    val findings: MutableList<Finding>,
    val largeFiles: MutableList<FileEntry>,
    val dupCandidates: List<Pair<String, Long>>,
    val runaway: List<Runaway>,
    val hotspots: List<Hotspot>,
    val snapshot: Map<String, Long>,
    val files: Int,
    val denied: Int,
) {
    val scanned get() = tree.size
}

class Scanner(rootDir: File, private val now: Long = System.currentTimeMillis(), rules: RuleSet = RuleSet()) {
    private val root = rootDir.absoluteFile.path.trimEnd('/')
    private val rules = rules
    private val recentCutoff = now - RECENT_DAYS * 86_400_000L
    @Volatile var cancelled = false
    @Volatile var filesSeen = 0
    @Volatile var bytesSeen = 0L
    @Volatile var current = ""

    private val catTotals = mutableMapOf<String, Long>()
    private val findings = mutableListOf<Finding>()
    private val largeFiles = mutableListOf<FileEntry>()
    private val dupCandidates = mutableListOf<Pair<String, Long>>()
    private val runaway = mutableListOf<Runaway>()
    private var denied = 0

    fun rel(path: String) = path.removePrefix(root).trimStart('/')

    fun run(): ScanResult {
        val tree = Node("Internal storage", root)
        scanDir(File(root), tree, cat = "other", inStop = false, inFinding = false, quiet = false)
        findings.sortByDescending { it.size }
        largeFiles.sortByDescending { it.size }
        return ScanResult(root, now, tree, catTotals, findings, largeFiles, dupCandidates,
            runaway.sortedByDescending { it.score }, hotspots(tree), snapshot(tree), filesSeen, denied)
    }

    private fun add(cat: String, bytes: Long) { catTotals[cat] = (catTotals[cat] ?: 0L) + bytes }

    private fun scanDir(dir: File, node: Node, cat: String, inStop: Boolean, inFinding: Boolean, quiet: Boolean) {
        val path = node.path
        val sepName = "/${node.name}/"
        if (!quiet && node.name.isNotEmpty() && "$path/".windowed(sepName.length).count { it == sepName } == NEST_REPEAT &&
            runaway.none { it.type == "nesting" && path.startsWith(it.path + "/") }) {
            runaway += Runaway("nesting", path, 0, 0, 0, 0, "Folder name '${node.name}' repeats $NEST_REPEAT+ times in this path", score = 50.0)
        }
        val entries = dir.listFiles()
        if (entries == null) { denied++; return }
        current = path
        var directFiles = 0
        var directSize = 0L
        var directRecent = 0
        val patterns = HashMap<String, Int>()
        val samples = HashMap<String, String>()

        for (f in entries) {
            if (cancelled) return
            val fpath = f.path
            if (Files.isSymbolicLink(f.toPath())) continue
            if (f.isDirectory) {
                val r = rel(fpath)
                val rule = if (inStop) null else rules.matchDir(r, f.name)
                if (rule?.skip == true) continue
                val child = Node(f.name, fpath)
                val isFinding = rule != null && !rule.container
                val childCat = rule?.cat ?: cat
                scanDir(f, child, childCat, inStop || isFinding, inFinding || isFinding, quiet || rule?.quiet == true)
                if (isFinding && child.size >= rule!!.minSize) {
                    findings += Finding(fpath, rule.label ?: f.name, rule.cat, child.size, child.files, child.newest,
                        rule.safety, rule.advice, rule.deletable, true)
                }
                node.size += child.size; node.files += child.files
                node.recentFiles += child.recentFiles; node.recentBytes += child.recentBytes
                if (child.newest > node.newest) node.newest = child.newest
                if (child.recentFiles > node.maxChildRecent) node.maxChildRecent = child.recentFiles
                if (child.size >= MB) node.dirs += child
            } else if (f.isFile) {
                val size = f.length()
                val mtime = f.lastModified()
                filesSeen++; bytesSeen += size
                directFiles++; directSize += size
                node.files++; node.size += size
                if (mtime > node.newest) node.newest = mtime
                if (mtime >= recentCutoff) { node.recentFiles++; node.recentBytes += size; directRecent++ }
                if (!quiet) {
                    val p = namePattern(f.name)
                    patterns[p] = (patterns[p] ?: 0) + 1
                    samples.putIfAbsent(p, f.name)
                }
                val frule = if (inStop) null else rules.matchFile(f.name)
                val fcat = frule?.cat ?: if (cat == "other") categoryByExtension(f.name) ?: cat else cat
                add(fcat, size)
                if (frule != null && size >= frule.minSize) {
                    findings += Finding(fpath, frule.label ?: f.name, frule.cat, size, 1, mtime, frule.safety, frule.advice, frule.deletable, false)
                }
                if (size >= LARGE_FILE_MIN) largeFiles += FileEntry(fpath, f.name, size, mtime, fcat, inFinding || frule != null)
                if (size >= DUP_CANDIDATE_MIN && frule?.cat != "trash") dupCandidates += fpath to size
            }
        }

        if (!quiet && directFiles >= RUNAWAY_MIN_FILES && patterns.isNotEmpty()) {
            val (pat, cnt) = patterns.maxBy { it.value }
            val share = cnt.toDouble() / directFiles
            val generated = '#' in pat || "{id}" in pat
            if (generated && share >= RUNAWAY_MIN_SHARE && (directRecent >= 100 || directSize >= GB)) {
                runaway += Runaway("pattern", path, directFiles, directSize, directRecent, node.newest,
                    "%,d files named like '%s'".format(cnt, pat), samples[pat] ?: "",
                    directRecent * 2.0 + directFiles + directSize / MB)
            }
        }
        if (!quiet && node.recentFiles >= BURST_MIN_RECENT_FILES && node.maxChildRecent < 0.7 * node.recentFiles) {
            runaway += Runaway("burst", path, node.files, node.size, node.recentFiles, node.newest,
                "%,d files created or changed in the last %d days".format(node.recentFiles, RECENT_DAYS),
                score = node.recentFiles * 2.0)
        }
    }

    private fun hotspots(tree: Node, minBytes: Long = 200 * MB, limit: Int = 20): List<Hotspot> {
        val out = mutableListOf<Hotspot>()
        fun walk(n: Node, isRoot: Boolean) {
            if (n.recentBytes < minBytes) return
            val strong = n.dirs.filter { it.recentBytes >= 0.7 * n.recentBytes }
            if (strong.isNotEmpty() || isRoot) n.dirs.forEach { walk(it, false) }
            else out += Hotspot(n.path, n.recentBytes, n.recentFiles, n.size)
        }
        walk(tree, true)
        return out.sortedByDescending { it.recentBytes }.take(limit)
    }

    private fun snapshot(tree: Node, maxDepth: Int = 8): Map<String, Long> {
        val snap = HashMap<String, Long>()
        fun walk(n: Node, d: Int) {
            if (n.size < SNAPSHOT_MIN || d > maxDepth) return
            if (d > 0) snap[n.path] = n.size
            n.dirs.forEach { walk(it, d + 1) }
        }
        walk(tree, 0)
        return snap
    }
}
