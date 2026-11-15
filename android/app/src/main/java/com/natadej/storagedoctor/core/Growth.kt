package com.natadej.storagedoctor.core

data class GrowthItem(val key: String, val label: String, val growth: Long, val size: Long, val was: Long?, val wasUnder: Long)

data class Growth(val since: Long, val usedThen: Long, val folders: List<GrowthItem>, val apps: List<GrowthItem>)

data class ScanRecord(val time: Long, val used: Long, val folders: Map<String, Long>, val apps: Map<String, Long>, val snapshotMin: Long = SNAPSHOT_MIN)

/**
 * What grew since the previous scan. A folder missing from the previous snapshot was smaller than
 * snapshotMin then, so its growth is a lower bound (was = null).
 */
fun growthSince(prev: ScanRecord?, folders: Map<String, Long>, apps: Map<String, Long>, appLabels: Map<String, String> = emptyMap(),
                minBytes: Long = 100 * MB, limit: Int = 30): Growth? {
    if (prev == null) return null
    val deltas = HashMap<String, Long>()
    for ((p, s) in folders) {
        val d = prev.folders[p]?.let { s - it } ?: (s - prev.snapshotMin)
        if (d >= minBytes) deltas[p] = d
    }
    val keep = deltas.filter { (p, d) ->
        deltas.none { (q, dq) -> q != p && q.startsWith("$p/") && dq >= 0.7 * d }
    }.map { (p, d) -> GrowthItem(p, p.substringAfterLast('/'), d, folders.getValue(p), prev.folders[p], prev.snapshotMin) }
        .sortedByDescending { it.growth }.take(limit)
    val appGrowth = apps.mapNotNull { (pkg, s) ->
        val was = prev.apps[pkg]
        val d = s - (was ?: 0L)
        if (d >= minBytes / 2) GrowthItem(pkg, appLabels[pkg] ?: pkg, d, s, was, 0) else null
    }.sortedByDescending { it.growth }.take(limit)
    return Growth(prev.time, prev.used, keep, appGrowth)
}
