package com.natadej.storagedoctor.core

data class PlanItem(
    val path: String, val label: String, val category: String, val size: Long, val safety: Safety,
    val advice: String, val isDir: Boolean, val canDelete: Boolean,
) {
    val isMedia get() = !isDir && isMediaFile(path)
}

/** Bytes to free so that used <= targetPct% of total. */
fun neededBytes(total: Long, used: Long, targetPct: Int): Long = maxOf(0L, used - total * targetPct / 100)

/** Cleanup candidates: rule findings plus large files that no rule covers, biggest first. */
fun buildPlan(r: ScanResult, protection: Protection): List<PlanItem> {
    val items = r.findings.map {
        PlanItem(it.path, it.label, it.category, it.size, it.safety, it.advice, it.isDir,
            it.deletable && protection.canDelete(it.path))
    } + r.largeFiles.filter { !it.inFinding }.map {
        PlanItem(it.path, it.name, it.category, it.size, Safety.REVIEW, "Large file. Check whether you still need it.",
            false, protection.canDelete(it.path))
    }
    return items.sortedWith(compareBy<PlanItem> { it.safety.ordinal }.thenByDescending { it.size })
}

/** Pick SAFE deletable items, biggest first, until the target is reached. */
fun recommended(items: List<PlanItem>, needed: Long): Set<String> {
    val picked = mutableSetOf<String>()
    var sum = 0L
    for (it in items.filter { it.safety == Safety.SAFE && it.canDelete }.sortedByDescending { it.size }) {
        if (sum >= needed && needed > 0) break
        picked += it.path
        sum += it.size
    }
    return picked
}

private fun under(path: String, root: String) = path == root || path.startsWith("$root/")

/** Update the result after files were deleted (toTrash = moved to the system trash, still using space). */
fun ScanResult.applyRemoval(path: String, size: Long, category: String, toTrash: Boolean) {
    catTotals[category] = maxOf(0L, (catTotals[category] ?: 0L) - size)
    if (toTrash) catTotals["trash"] = (catTotals["trash"] ?: 0L) + size
    else {
        var node = tree
        while (true) {
            node.size = maxOf(0L, node.size - size)
            val next = node.dirs.firstOrNull { under(path, it.path) } ?: break
            if (next.path == path) { node.dirs.remove(next); break }
            node = next
        }
    }
    findings.removeAll { under(it.path, path) }
    largeFiles.removeAll { under(it.path, path) }
}
