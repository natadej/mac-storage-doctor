package com.natadej.storagedoctor.ui

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.natadej.storagedoctor.core.DupGroup
import com.natadej.storagedoctor.core.Growth
import com.natadej.storagedoctor.core.PlanItem
import com.natadej.storagedoctor.core.Protection
import com.natadej.storagedoctor.core.ScanRecord
import com.natadej.storagedoctor.core.ScanResult
import com.natadej.storagedoctor.core.Scanner
import com.natadej.storagedoctor.core.applyRemoval
import com.natadej.storagedoctor.core.buildPlan
import com.natadej.storagedoctor.core.findDuplicates
import com.natadej.storagedoctor.core.growthSince
import com.natadej.storagedoctor.core.isMediaFile
import com.natadej.storagedoctor.core.neededBytes
import com.natadej.storagedoctor.core.recommended
import com.natadej.storagedoctor.data.AppSize
import com.natadej.storagedoctor.data.Cleaner
import com.natadej.storagedoctor.data.Device
import com.natadej.storagedoctor.data.DiskInfo
import com.natadej.storagedoctor.data.MonitorWorker
import com.natadej.storagedoctor.data.Opener
import com.natadej.storagedoctor.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One thing the user asked to remove, from any tab. */
data class Target(val path: String, val size: Long, val category: String, val isDir: Boolean) {
    val isMedia get() = !isDir && isMediaFile(path)
}

/** A pending system dialog (trash or delete) that the activity must launch. */
data class SystemRequest(val sender: IntentSender, val items: List<Target>, val emptyTrash: Boolean)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    val store = Store(app)
    private val root = Device.storageRoot
    val protection = Protection(root)

    var hasFiles by mutableStateOf(Device.hasAllFilesAccess(app)); private set
    var hasUsage by mutableStateOf(Device.hasUsageAccess(app)); private set
    var disclosureAnswered by mutableStateOf(store.disclosureAnswered); private set
    var target by mutableStateOf(store.target); private set
    var monitor by mutableStateOf(store.monitor); private set
    var disk by mutableStateOf(Device.disk(app)); private set

    var scanning by mutableStateOf(false); private set
    var progress by mutableStateOf(""); private set
    var result by mutableStateOf<ScanResult?>(null); private set
    var version by mutableStateOf(0); private set
    var apps by mutableStateOf<List<AppSize>>(emptyList()); private set
    var growth by mutableStateOf<Growth?>(null); private set
    var lastScanTime by mutableStateOf(store.history().lastOrNull()?.time); private set

    var dups by mutableStateOf<List<DupGroup>?>(null); private set
    var dupProgress by mutableStateOf<String?>(null); private set

    var selected by mutableStateOf<Map<String, Target>>(emptyMap()); private set
    var request by mutableStateOf<SystemRequest?>(null); private set
    var message by mutableStateOf<String?>(null)

    private var scanner: Scanner? = null
    private var duplicateJob: Job? = null

    val appsTotal get() = apps.sumOf { it.total }
    val needed get() = neededBytes(disk.total, disk.used, target)
    val plan: List<PlanItem> get() { version; return result?.let { buildPlan(it, protection) } ?: emptyList() }

    /** Category totals for the whole disk: scanned files + apps + whatever is left (system, unscanned). */
    fun categoryTotals(): Map<String, Long> {
        version
        val r = result ?: return emptyMap()
        val m = r.catTotals.filterValues { it > 0 }.toMutableMap()
        if (appsTotal > 0) m["apps"] = (m["apps"] ?: 0L) + appsTotal
        m["system"] = maxOf(0L, disk.used - r.scanned - appsTotal)
        return m
    }

    fun refreshPermissions() {
        hasFiles = Device.hasAllFilesAccess(ctx)
        hasUsage = Device.hasUsageAccess(ctx)
        disk = Device.disk(ctx)
        if (!hasFiles) {
            cancelScan()
            duplicateJob?.cancel(); dupProgress = null
            result = null; dups = null; growth = null
            selected = emptyMap(); request = null; apps = emptyList()
        } else if (!hasUsage) apps = emptyList()
    }

    fun answerDisclosure() { store.disclosureAnswered = true; disclosureAnswered = true }

    fun changeTarget(v: Int) { target = v; store.target = v }

    fun changeMonitor(on: Boolean) { monitor = on; store.monitor = on; MonitorWorker.schedule(ctx, on) }

    fun scan() {
        if (scanning || !hasFiles) return
        scanning = true; dups = null; selected = emptyMap()
        val s = Scanner(File(root)).also { scanner = it }
        viewModelScope.launch {
            val ticker = launch {
                while (isActive) {
                    progress = "%,d files · %s · %s".format(s.filesSeen, fmtBytes(s.bytesSeen), s.current.removePrefix(root))
                    delay(250)
                }
            }
            val r = withContext(Dispatchers.IO) { s.run() }
            progress = "Measuring apps…"
            val a = if (hasUsage) withContext(Dispatchers.IO) { runCatching { Device.appSizes(ctx) }.getOrDefault(emptyList()) } else emptyList()
            ticker.cancel()
            if (s.cancelled || !hasFiles) { scanning = false; progress = ""; return@launch }
            disk = Device.disk(ctx)
            val prev = store.history().lastOrNull()
            val appMap = a.associate { it.pkg to it.total }
            growth = growthSince(prev, r.snapshot, appMap, a.associate { it.pkg to it.label })
            store.addRecord(ScanRecord(r.time, disk.used, r.snapshot, appMap))
            lastScanTime = r.time
            result = r; apps = a; version++
            scanning = false; progress = ""
        }
    }

    fun cancelScan() { scanner?.cancelled = true }

    fun findDups() {
        if (!hasFiles) return
        val r = result ?: return
        dupProgress = "Comparing files…"
        duplicateJob?.cancel()
        duplicateJob = viewModelScope.launch {
            val g = withContext(Dispatchers.IO) {
                findDuplicates(r.dupCandidates, { d, t ->
                    dupProgress = "Comparing files… $d / $t"
                })
            }
            if (hasFiles) dups = g
            dupProgress = null
        }
    }

    fun toggle(t: Target) {
        selected = if (t.path in selected) selected - t.path else selected + (t.path to t)
    }

    fun clearSelection() { selected = emptyMap() }

    fun open(path: String) {
        if (!hasFiles) return
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) { runCatching { Opener.uriFor(ctx, path) }.getOrNull() }
            if (uri == null || !Opener.open(ctx, uri, path)) message = "No app on this phone can open this file."
        }
    }

    fun selectRecommended() {
        val byPath = plan.associateBy { it.path }
        selected = recommended(plan, needed).associateWith { p ->
            byPath.getValue(p).let { Target(it.path, it.size, it.category, it.isDir) }
        }
    }

    /** Media files go to the system trash (needs a system dialog); everything else is deleted now. */
    fun cleanup(items: Collection<Target>) {
        if (!hasFiles) { message = "File access is off. Nothing was deleted."; return }
        val allowed = items.filter { protection.canDelete(it.path) }
        if (allowed.size < items.size) message = "${items.size - allowed.size} protected item(s) were skipped."
        viewModelScope.launch {
            val media = allowed.filter { it.isMedia }
            val uris: Map<String, Uri> = withContext(Dispatchers.IO) {
                val known = Cleaner.mediaUris(ctx, media.map { it.path })
                val missing = media.map { it.path }.filter { it !in known }
                if (missing.isEmpty()) known else { Cleaner.index(ctx, missing); Cleaner.mediaUris(ctx, media.map { it.path }) }
            }
            val canTrash = Build.VERSION.SDK_INT >= 30
            val toTrash = if (canTrash) media.filter { it.path in uris } else emptyList()
            val sender = Cleaner.trashRequest(ctx, toTrash.mapNotNull { uris[it.path] })
            // Media we promised to trash is never deleted permanently instead.
            val skipped = if (canTrash) media.size - (if (sender != null) toTrash.size else 0) else 0
            val now = if (canTrash) allowed - media.toSet() else allowed
            if (skipped > 0) message = "$skipped media file(s) could not be moved to the trash (Android has not indexed them). Nothing was deleted."
            var freed = 0L
            withContext(Dispatchers.IO) {
                for (t in now) {
                    val bytes = Cleaner.deleteNow(ctx, t.path)
                    freed += bytes
                    withContext(Dispatchers.Main) { removed(t, bytes, toTrash = false) }
                }
            }
            if (now.isNotEmpty() && skipped == 0) message = "Deleted ${now.size} item(s), freed ${fmtBytes(freed)}."
            if (sender != null) request = SystemRequest(sender, toTrash, emptyTrash = false)
            disk = Device.disk(ctx)
        }
    }

    fun emptyTrash() {
        if (!hasFiles) { message = "File access is off. Nothing was deleted."; return }
        viewModelScope.launch {
            val t = withContext(Dispatchers.IO) { Cleaner.trashed(ctx) }
            if (t.isEmpty()) { message = "The system trash is empty."; return@launch }
            val items = t.map { (p, v) -> Target(p, v.second, "trash", false) }
            Cleaner.deleteRequest(ctx, t.values.map { it.first })?.let { request = SystemRequest(it, items, emptyTrash = true) }
        }
    }

    /** Called by the activity after the system dialog closes. */
    fun onRequestResult(ok: Boolean) {
        val req = request ?: return
        request = null
        if (!ok) { message = "Cancelled."; return }
        req.items.forEach { removed(it, it.size, toTrash = !req.emptyTrash) }
        disk = Device.disk(ctx)
        message = if (req.emptyTrash) "Trash emptied: ${fmtBytes(req.items.sumOf { it.size })} freed."
        else "Moved ${req.items.size} file(s) to the trash. They are deleted for good after 30 days, or empty the trash now to free the space."
    }

    private fun removed(t: Target, bytes: Long, toTrash: Boolean) {
        val r = result
        if (r != null) {
            val cat = r.findings.firstOrNull { it.path == t.path }?.category ?: t.category
            r.applyRemoval(t.path, bytes, if (t.category == "trash") "trash" else cat, toTrash)
        }
        dups = dups?.mapNotNull { g ->
            val left = g.files.filter { it.path != t.path && !it.path.startsWith(t.path + "/") }
            if (left.size > 1) g.copy(files = left) else null
        }
        selected = selected - t.path
        version++
    }
}
