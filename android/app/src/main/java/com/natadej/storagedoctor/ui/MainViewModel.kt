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
import com.natadej.storagedoctor.core.MediaRequestBatches
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
import kotlinx.coroutines.CancellationException
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
class SystemRequest(val sender: IntentSender) {
    var launched = false
}

private data class MediaTarget(val target: Target, val uri: Uri)
private data class MediaOperation(val batches: MediaRequestBatches<MediaTarget>, val emptyTrash: Boolean, val summary: String)

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
    private var cleanupJob: Job? = null
    private var mediaOperation: MediaOperation? = null

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
            cleanupJob?.cancel()
            if (mediaOperation != null) finishMediaRequests("File access was revoked. No further media requests will be shown.")
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
        if (!canClean()) return
        val allowed = items.filter { protection.canDelete(it.path) }
        cleanupJob = viewModelScope.launch {
            var processed = 0
            var freed = 0L
            var missing = 0
            fun summary() = listOfNotNull(
                "${items.size - allowed.size} protected item(s) skipped.".takeIf { allowed.size < items.size },
                "$missing media file(s) were not indexed and were skipped.".takeIf { missing > 0 },
                "Direct cleanup: $processed item(s) processed, ${fmtBytes(freed)} freed.".takeIf { processed > 0 },
            ).joinToString(" ")
            try {
                val media = allowed.filter { it.isMedia }
                val uris: Map<String, Uri> = withContext(Dispatchers.IO) {
                    val known = Cleaner.mediaUris(ctx, media.map { it.path })
                    val missing = media.map { it.path }.filter { it !in known }
                    if (missing.isEmpty()) known else { Cleaner.index(ctx, missing); Cleaner.mediaUris(ctx, media.map { it.path }) }
                }
                val canTrash = Build.VERSION.SDK_INT >= 30
                val toTrash = if (canTrash) media.filter { it.path in uris } else emptyList()
                // Media we promised to trash is never deleted permanently instead.
                missing = if (canTrash) media.size - toTrash.size else 0
                val now = if (canTrash) allowed - media.toSet() else allowed
                withContext(Dispatchers.IO) {
                    for (t in now) {
                        val bytes = Cleaner.deleteNow(ctx, t.path)
                        freed += bytes
                        processed++
                        withContext(Dispatchers.Main) { removed(t, bytes, toTrash = false) }
                    }
                }
                disk = Device.disk(ctx)
                beginMediaRequests(toTrash.map { MediaTarget(it, uris.getValue(it.path)) }, emptyTrash = false, summary = summary())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "${summary()} Cleanup stopped because Android could not complete the file operation.".trim()
            }
        }
    }

    fun emptyTrash() {
        if (!canClean()) return
        cleanupJob = viewModelScope.launch {
            try {
                val t = withContext(Dispatchers.IO) { Cleaner.trashed(ctx) }
                if (t.isEmpty()) { message = "The system trash is empty."; return@launch }
                val items = t.map { (p, v) -> MediaTarget(Target(p, v.second, "trash", false), v.first) }
                beginMediaRequests(items, emptyTrash = true, summary = "")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Android could not read the system trash. No delete request was sent."
            }
        }
    }

    private fun canClean(): Boolean {
        if (!hasFiles) { message = "File access is off. Nothing was deleted."; return false }
        if (cleanupJob?.isActive == true || mediaOperation != null) {
            message = "Finish the current cleanup confirmations first."
            return false
        }
        return true
    }

    private fun beginMediaRequests(items: List<MediaTarget>, emptyTrash: Boolean, summary: String) {
        if (items.isEmpty()) { message = summary.ifEmpty { "No eligible items to clean up." }; return }
        mediaOperation = MediaOperation(MediaRequestBatches(items) { it.target.size }, emptyTrash, summary)
        nextMediaRequest()
    }

    private fun nextMediaRequest() {
        val op = mediaOperation ?: return
        val batch = op.batches.current
        if (batch.isEmpty()) { finishMediaRequests(); return }
        val uris = batch.map { it.uri }
        val sender = if (op.emptyTrash) Cleaner.deleteRequest(ctx, uris) else Cleaner.trashRequest(ctx, uris)
        if (sender == null) {
            finishMediaRequests("Android could not create the next media confirmation. No further requests were sent.")
        } else request = SystemRequest(sender)
    }

    private fun finishMediaRequests(reason: String? = null) {
        val op = mediaOperation ?: return
        val batches = op.batches
        batches.stop()
        val outcome = if (op.emptyTrash) "Permanently deleted ${batches.completedCount} trashed file(s), ${fmtBytes(batches.completedBytes)} freed."
        else "Moved ${batches.completedCount} media file(s) to the trash (${fmtBytes(batches.completedBytes)}). Empty the trash to free that space."
        message = listOfNotNull(op.summary.takeIf { it.isNotEmpty() }, outcome, reason,
            "${batches.remainingCount} media file(s) were not confirmed.".takeIf { batches.remainingCount > 0 }).joinToString(" ")
        request = null
        mediaOperation = null
        disk = Device.disk(ctx)
    }

    fun onRequestLaunchFailed() {
        finishMediaRequests("Android could not open the media confirmation. No further requests were sent.")
    }

    /** Called by the activity after the system dialog closes. */
    fun onRequestResult(ok: Boolean) {
        if (request == null) return
        val op = mediaOperation ?: return
        request = null
        if (!ok) { finishMediaRequests("Cancelled. No further requests were sent."); return }
        op.batches.confirmCurrent().forEach { removed(it.target, it.target.size, toTrash = !op.emptyTrash) }
        disk = Device.disk(ctx)
        nextMediaRequest()
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
