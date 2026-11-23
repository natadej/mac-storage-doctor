package com.natadej.storagedoctor.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.natadej.storagedoctor.core.Node
import com.natadej.storagedoctor.core.PlanItem
import com.natadej.storagedoctor.core.Safety
import com.natadej.storagedoctor.core.FileAccessMode
import com.natadej.storagedoctor.core.fileAccessMode

private val Bg = Color(0xFF0F1115)
private val CardBg = Color(0xFF181B22)
private val Muted = Color(0xFF9AA3B2)
private val Accent = Color(0xFF6C8CFF)

private val TABS = listOf("Overview", "AI & runaway", "Cleanup", "Large files", "Duplicates", "Apps", "Explore")

@Composable
fun App(vm: MainViewModel) {
    var requestDisclosure by remember { mutableStateOf(false) }
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refreshPermissions() }
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Bg, surfaceVariant = CardBg)) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            when (fileAccessMode(vm.hasFiles, vm.disclosureAnswered, requestDisclosure)) {
                FileAccessMode.DISCLOSURE -> PermissionScreen(vm, requestLegacyAccess = {
                    legacy.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                }) {
                    vm.answerDisclosure()
                    requestDisclosure = false
                }
                else -> Main(vm) { requestDisclosure = true }
            }
        }
    }
}

@Composable
private fun PermissionScreen(vm: MainViewModel, requestLegacyAccess: () -> Unit, answerDisclosure: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Storage Doctor", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("File access for storage maintenance", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Text("With All files access, Storage Doctor reads file names, folder paths, sizes and dates across shared storage, including photos, videos, downloads, documents, chat media and AI model files. It reads file contents to compare duplicates. This powers the folder explorer, storage breakdown, growth and generated-file detection, and cleanup plan.", color = Muted)
        Spacer(Modifier.height(8.dp))
        Text("Access is processed only on this phone. Folder sizes and optional visible-app sizes are saved in private local scan history. No files, history or app inventory are uploaded, sold or used for advertising. Opening a file gives the viewer you choose read access to that file.", color = Muted)
        Spacer(Modifier.height(8.dp))
        Text("This access also permits changes to shared files. Storage Doctor deletes only items you select and confirm; supported media goes through Android's trash confirmation. Other files are permanently deleted. Android/data and other apps' private files remain restricted. The optional background check reads storage totals only, not your files.", color = Muted)
        Spacer(Modifier.height(8.dp))
        Text("You can decline or revoke access in Settings. Without it, storage totals, your target and optional storage alerts still work; file scans and cleanup are unavailable.", color = Muted)
        Spacer(Modifier.height(24.dp))
        Button(modifier = Modifier.testTag("grant"), onClick = {
            answerDisclosure()
            if (Build.VERSION.SDK_INT >= 30) {
                val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                runCatching { ctx.startActivity(i) }.recoverCatching {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }.onFailure {
                    vm.message = "Settings could not be opened. You can continue without file access or enable it later in Android Settings → Apps → Special app access → All files access."
                }
            } else requestLegacyAccess()
        }) { Text(if (Build.VERSION.SDK_INT >= 30) "Agree and open file access settings" else "Agree and allow storage access") }
        OutlinedButton(onClick = answerDisclosure, modifier = Modifier.testTag("limited")) { Text("Not now — use storage totals") }
        PrivacyButton()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Main(vm: MainViewModel, requestFiles: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val systemDialog = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        vm.onRequestResult(it.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(vm.request) {
        vm.request?.takeUnless { it.launched }?.let {
            it.launched = true
            runCatching { systemDialog.launch(IntentSenderRequest.Builder(it.sender).build()) }
                .onFailure { vm.onRequestLaunchFailed() }
        }
    }
    LaunchedEffect(vm.message) { vm.message?.let { snack.showSnackbar(it); vm.message = null } }
    LaunchedEffect(Unit) { if (vm.result == null) vm.scan() }

    Scaffold(
        containerColor = Bg,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Storage Doctor") },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
                    actions = {
                        if (!vm.hasFiles) TextButton(onClick = requestFiles) { Text("File access") }
                        else if (vm.scanning) TextButton(onClick = vm::cancelScan) { Text("Stop") }
                        else TextButton(onClick = vm::scan, modifier = Modifier.testTag("scan")) { Text(if (vm.result == null) "Scan" else "Rescan") }
                    },
                )
                ScrollableTabRow(selectedTabIndex = tab, containerColor = Bg, edgePadding = 8.dp) {
                    TABS.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
                }
                if (vm.scanning) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(vm.progress, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
        },
        bottomBar = {
            if (vm.selected.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().background(CardBg).navigationBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${vm.selected.size} selected · ${fmtBytes(vm.selected.values.sumOf { it.size })}", fontWeight = FontWeight.SemiBold)
                        if (vm.needed > 0) Text("Need ${fmtBytes(vm.needed)} for ${vm.target}%", color = Muted, fontSize = 12.sp)
                    }
                    TextButton(onClick = vm::clearSelection) { Text("Clear") }
                    Button(onClick = { confirm = true }, modifier = Modifier.testTag("delete")) { Text("Clean up") }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!vm.hasFiles) Text("Limited mode: storage totals and optional alerts only. File scans and cleanup are off.", color = Muted, modifier = Modifier.padding(16.dp))
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> Overview(vm)
                    else -> if (vm.result == null) Empty(if (!vm.hasFiles) "Enable file access to scan and manage shared files." else if (vm.scanning) "Scanning…" else "Run a scan first.") else when (tab) {
                        1 -> Runaway(vm)
                        2 -> Cleanup(vm)
                        3 -> LargeFiles(vm)
                        4 -> Duplicates(vm)
                        5 -> Apps(vm)
                        6 -> Explore(vm)
                    }
                }
            }
        }
    }
    if (confirm) ConfirmDialog(vm) { confirm = false }
}

@Composable
private fun ConfirmDialog(vm: MainViewModel, close: () -> Unit) {
    val items = vm.selected.values.toList()
    val media = items.filter { it.isMedia && Build.VERSION.SDK_INT >= 30 }
    val other = items - media.toSet()
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Clean up ${fmtBytes(items.sumOf { it.size })}?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (media.isNotEmpty()) Text("• ${media.size} photo/video/audio file(s), ${fmtBytes(media.sumOf { it.size })}: moved to the system trash. Android asks you to confirm. You can restore them for 30 days; the space is freed when the trash is emptied.")
                if (other.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("• ${other.size} other item(s), ${fmtBytes(other.sumOf { it.size })}: deleted permanently. This cannot be undone.", color = Color(0xFFFCA5A5))
                }
            }
        },
        confirmButton = { Button(onClick = { vm.cleanup(items); close() }, modifier = Modifier.testTag("confirm")) { Text("Clean up") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

@Composable
private fun Empty(text: String) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text, color = Muted) }

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = CardBg)) {
        Column(Modifier.padding(14.dp)) { content() }
    }
}

@Composable
private fun Title(text: String, sub: String? = null) {
    Text(text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    if (sub != null) Text(sub, color = Muted, fontSize = 13.sp)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Bar(fraction: Float, color: Color, marker: Float? = null, height: Int = 10) {
    Box(Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF2A2F3A))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height.dp).background(color))
        if (marker != null) Box(Modifier.fillMaxWidth(marker).height(height.dp)) {
            Box(Modifier.align(Alignment.CenterEnd).width(2.dp).height(height.dp).background(Color.White))
        }
    }
}

@Composable
private fun Overview(vm: MainViewModel) {
    val ctx = LocalContext.current
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.changeMonitor(true) }
    val d = vm.disk
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            SectionCard {
                Text("%.0f%% used".format(d.usedPct), fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("usedPct"))
                Text("${fmtBytes(d.used)} of ${fmtBytes(d.total)} · ${fmtBytes(d.free)} free", color = Muted)
                Spacer(Modifier.height(10.dp))
                Bar(d.usedPct.toFloat() / 100, if (d.usedPct > vm.target) Color(0xFFEF4444) else Color(0xFF22C55E), vm.target / 100f, 14)
                Spacer(Modifier.height(10.dp))
                Text(
                    if (vm.needed > 0) "Free ${fmtBytes(vm.needed)} to get down to ${vm.target}% used."
                    else "You are under your ${vm.target}% target.",
                    fontWeight = FontWeight.SemiBold, color = if (vm.needed > 0) Color(0xFFFCA5A5) else Color(0xFF86EFAC),
                    modifier = Modifier.testTag("needed"),
                )
                Spacer(Modifier.height(8.dp))
                Text("Target: keep used at or below ${vm.target}%", color = Muted, fontSize = 13.sp)
                Slider(value = vm.target.toFloat(), onValueChange = { vm.changeTarget(it.toInt()) }, valueRange = 20f..95f, steps = 14)
            }
        }
        val cats = vm.categoryTotals().entries.sortedByDescending { it.value }
        if (cats.isNotEmpty()) item {
            SectionCard {
                Title("What is using space", vm.lastScanTime?.let { "Last scan ${ago(it)}" })
                val max = cats.first().value.toFloat()
                cats.forEach { (id, bytes) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(catColor(id)))
                        Spacer(Modifier.width(8.dp))
                        Text(catLabel(id), Modifier.weight(1f))
                        Text(fmtBytes(bytes), fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(4.dp))
                    Bar(bytes / max, catColor(id), height = 6)
                    Spacer(Modifier.height(10.dp))
                }
                Text("\"Android system & not scanned\" is the OS, app files Android hides from other apps (Android/data) and anything else the scan cannot read." +
                    if (!vm.hasUsage) " Optional usage access shows sizes for apps Android makes visible to us." else "", color = Muted, fontSize = 12.sp)
            }
        }
        if (vm.hasFiles && !vm.hasUsage) item {
            SectionCard {
                Title("Optional app sizes", "Usage access reads sizes of apps Android makes visible to us. Sizes are stored only in local scan history, never uploaded. Other apps remain in the unscanned total. File scanning works without this access.")
                OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Allow usage access") }
            }
        }
        vm.result?.let { r ->
            item {
                SectionCard {
                    Title("At a glance")
                    Text("${r.runaway.size} runaway suspect(s) · ${r.largeFiles.size} file(s) over 100 MB · ${"%,d".format(r.files)} files scanned", color = Muted)
                    if (r.denied > 0) Text("${r.denied} folder(s) could not be read.", color = Muted, fontSize = 12.sp)
                }
            }
        }
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Check every 6 hours", fontWeight = FontWeight.SemiBold)
                        Text("Notify me when storage is over ${vm.target}% or grows by 5 GB or more.", color = Muted, fontSize = 13.sp)
                    }
                    Switch(checked = vm.monitor, onCheckedChange = { on ->
                        if (on && Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.changeMonitor(on)
                    })
                }
            }
        }
        item { PrivacyButton() }
    }
}

@Composable
private fun Runaway(vm: MainViewModel) {
    val r = vm.result!!
    val root = r.root
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            SectionCard {
                Title("Runaway file generation", "Folders where something keeps writing files: many files with the same name pattern, a burst of new files, or a folder nested inside itself.")
                if (r.runaway.isEmpty()) Text("Nothing suspicious found.", color = Color(0xFF86EFAC))
                r.runaway.forEach { s ->
                    Spacer(Modifier.height(6.dp))
                    Text(s.path.removePrefix(root).ifEmpty { "/" }, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        when (s.type) { "pattern" -> "Repeated names"; "burst" -> "Burst of new files"; else -> "Self-nesting folders" } + " · " + s.detail,
                        color = Color(0xFFFCD34D), fontSize = 13.sp,
                    )
                    if (s.size > 0) Text("${fmtBytes(s.size)} · ${"%,d".format(s.recentFiles)} new in 7 days" + (if (s.newest > 0) " · last write ${ago(s.newest)}" else ""), color = Muted, fontSize = 12.sp)
                    if (s.sample.isNotEmpty()) Text("e.g. ${s.sample}", color = Muted, fontSize = 12.sp)
                    if (s.size > 0 && vm.protection.canDelete(s.path)) {
                        val t = Target(s.path, s.size, "other", true)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = s.path in vm.selected, onCheckedChange = { vm.toggle(t) })
                            Text("Select folder for cleanup", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
        val g = vm.growth
        item {
            SectionCard {
                Title("Growth since last scan", g?.let { "Compared with ${fmtTime(it.since)} · disk then ${fmtBytes(it.usedThen)}, now ${fmtBytes(vm.disk.used)}" })
                when {
                    g == null -> Text("Scan again later to see what grew.", color = Muted)
                    g.folders.isEmpty() && g.apps.isEmpty() -> Text("Nothing grew by 100 MB or more.", color = Muted)
                }
                g?.folders?.forEach {
                    Row {
                        Text(it.key.removePrefix(root), Modifier.weight(1f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text((if (it.was == null) "+≥" else "+") + fmtBytes(it.growth), color = Color(0xFFFCA5A5), fontWeight = FontWeight.SemiBold)
                    }
                    Text(if (it.was == null) "now ${fmtBytes(it.size)} (was under ${fmtBytes(it.wasUnder)})" else "${fmtBytes(it.was)} → ${fmtBytes(it.size)}", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                }
                g?.apps?.forEach {
                    Row {
                        Text("App: ${it.label}", Modifier.weight(1f), fontSize = 13.sp)
                        Text("+" + fmtBytes(it.growth), color = Color(0xFFFCA5A5), fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
        item {
            SectionCard {
                Title("Busiest folders this week", "Where new data was written in the last 7 days.")
                if (r.hotspots.isEmpty()) Text("No folder got 200 MB or more of new files.", color = Muted)
                r.hotspots.forEach {
                    Row {
                        Text(it.path.removePrefix(root), Modifier.weight(1f), fontSize = 13.sp)
                        Text(fmtBytes(it.recentBytes), fontWeight = FontWeight.SemiBold)
                    }
                    Text("${"%,d".format(it.recentFiles)} new files · folder ${fmtBytes(it.size)}", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
        val ai = r.findings.filter { it.category == "ai" }
        if (ai.isNotEmpty()) item {
            SectionCard {
                Title("AI model files", "${fmtBytes(ai.sumOf { it.size })} in ${ai.size} file(s)")
                ai.forEach {
                    Row {
                        Text(it.path.removePrefix(root), Modifier.weight(1f), fontSize = 13.sp)
                        Text(fmtBytes(it.size))
                    }
                }
            }
        }
    }
}

@Composable
private fun Selectable(vm: MainViewModel, t: Target, title: String, sub: String, safety: Safety?, canDelete: Boolean, advice: String = "") {
    val canOpen = !t.isDir
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(CardBg)
        .clickable(enabled = canDelete) { vm.toggle(t) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (canDelete) Checkbox(checked = t.path in vm.selected, onCheckedChange = { vm.toggle(t) })
        else Icon(Icons.Filled.Lock, "Protected", Modifier.padding(12.dp).size(20.dp), tint = Muted)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(catColor(t.category)))
                Spacer(Modifier.width(6.dp))
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                if (safety != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(safetyLabel(safety), color = safetyColor(safety), fontSize = 11.sp,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(safetyColor(safety).copy(alpha = 0.15f)).padding(horizontal = 5.dp, vertical = 1.dp))
                }
            }
            Text(sub, color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (advice.isNotEmpty()) Text(advice, color = Muted, fontSize = 12.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(fmtBytes(t.size), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
            if (canOpen) TextButton(onClick = { vm.open(t.path) }, modifier = Modifier.testTag("open"),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("Open", fontSize = 13.sp) }
        }
    }
}

private fun LazyListScope.planItems(vm: MainViewModel, items: List<PlanItem>, root: String) {
    items(items, key = { it.path }) { p ->
        Selectable(vm, Target(p.path, p.size, p.category, p.isDir), p.label, p.path.removePrefix(root), p.safety, p.canDelete,
            if (!p.canDelete && p.advice.isEmpty()) "Protected. Clean it up inside the app that owns it." else p.advice)
    }
}

@Composable
private fun Cleanup(vm: MainViewModel) {
    val r = vm.result!!
    var filter by rememberSaveable { mutableStateOf<Safety?>(null) }
    val plan = vm.plan
    val shown = plan.filter { filter == null || it.safety == filter }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            SectionCard {
                Title("Cleanup plan",
                    if (vm.needed > 0) "Free ${fmtBytes(vm.needed)} to reach ${vm.target}%. Safe items are caches, installers and leftovers." else "You are already under your ${vm.target}% target.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::selectRecommended, modifier = Modifier.testTag("recommend")) { Text("Select recommended") }
                    OutlinedButton(onClick = vm::emptyTrash) { Text("Empty trash") }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(null, Safety.SAFE, Safety.REVIEW, Safety.CAREFUL).forEach { s ->
                        val n = plan.count { s == null || it.safety == s }
                        TextButton(onClick = { filter = s }) {
                            Text((s?.let { safetyLabel(it) } ?: "all") + " ($n)", color = if (filter == s) Accent else Muted, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
        if (shown.isEmpty()) item { Empty("Nothing here.") }
        planItems(vm, shown, r.root)
    }
}

@Composable
private fun LargeFiles(vm: MainViewModel) {
    val r = vm.result!!
    vm.version
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        item { SectionCard { Title("Files over 100 MB", "${r.largeFiles.size} file(s) · ${fmtBytes(r.largeFiles.sumOf { it.size })}") } }
        items(r.largeFiles.toList(), key = { it.path }) { f ->
            Selectable(vm, Target(f.path, f.size, f.category, false), f.name,
                "${f.path.removePrefix(r.root).substringBeforeLast('/')} · ${ago(f.mtime)}", null, vm.protection.canDelete(f.path))
        }
    }
}

@Composable
private fun Duplicates(vm: MainViewModel) {
    val r = vm.result!!
    val groups = vm.dups
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            SectionCard {
                Title("Duplicate files", groups?.let { "${it.size} group(s) · ${fmtBytes(it.sumOf { g -> g.wasted })} in extra copies" }
                    ?: "Identical files of 1 MB or more, compared by content.")
                when {
                    vm.dupProgress != null -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(vm.dupProgress!!, color = Muted, fontSize = 12.sp) }
                    groups == null -> Button(onClick = vm::findDups, modifier = Modifier.testTag("findDups")) { Text("Find duplicates") }
                    groups.isNotEmpty() -> OutlinedButton(onClick = {
                        groups.forEach { g -> g.files.drop(1).filter { vm.protection.canDelete(it.path) && it.path !in vm.selected }
                            .forEach { vm.toggle(Target(it.path, it.size, it.category, false)) } }
                    }) { Text("Select all extra copies (keeps the oldest)") }
                }
            }
        }
        groups?.forEach { g ->
            item(key = "g" + g.files.first().path) {
                Text("${g.files.size} × ${fmtBytes(g.size)} · ${g.files.first().name}", color = Muted, fontSize = 13.sp,
                    modifier = Modifier.padding(start = 16.dp, top = 10.dp))
            }
            items(g.files, key = { "d" + it.path }) { f ->
                Selectable(vm, Target(f.path, f.size, f.category, false), f.name, f.path.removePrefix(r.root).substringBeforeLast('/') + " · " + ago(f.mtime),
                    null, vm.protection.canDelete(f.path))
            }
        }
    }
}

@Composable
private fun Apps(vm: MainViewModel) {
    val ctx = LocalContext.current
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            SectionCard {
                Title("Visible apps", if (vm.hasUsage) "${vm.apps.size} visible apps · ${fmtBytes(vm.appsTotal)}. This is not a complete installed-app inventory. Tap an app to manage its storage in Settings." else "Optional Usage access reads visible-app sizes for local scan history only. Nothing is uploaded. Other apps remain in the unscanned total.")
                if (!vm.hasUsage) OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Allow usage access") }
                else if (vm.apps.isEmpty()) Text("Rescan to measure apps.", color = Muted)
            }
        }
        items(vm.apps.filter { it.total >= 1_000_000 }, key = { it.pkg }) { a ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(CardBg)
                .clickable { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}"))) }
                .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.label + if (a.system) " (system)" else "", fontWeight = FontWeight.SemiBold)
                    Text("app ${fmtBytes(a.appBytes)} · data ${fmtBytes(a.dataBytes - a.cacheBytes)} · cache ${fmtBytes(a.cacheBytes)}", color = Muted, fontSize = 12.sp)
                }
                Text(fmtBytes(a.total), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Explore(vm: MainViewModel) {
    val r = vm.result!!
    var stack by remember(r) { mutableStateOf(listOf(r.tree)) }
    val node: Node = stack.last()
    BackHandler(stack.size > 1) { stack = stack.dropLast(1) }
    vm.version
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            SectionCard {
                Title(node.path.removePrefix(r.root).ifEmpty { "Internal storage" }, "${fmtBytes(node.size)} · ${"%,d".format(node.files)} files")
                if (stack.size > 1) OutlinedButton(onClick = { stack = stack.dropLast(1) }) { Text("Up") }
            }
        }
        val kids = node.dirs.sortedByDescending { it.size }
        val loose = node.size - kids.sumOf { it.size }
        items(kids, key = { it.path }) { k ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(CardBg)
                .clickable { stack = stack + k }.padding(12.dp)) {
                Row {
                    Text(k.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text(fmtBytes(k.size))
                }
                Spacer(Modifier.height(4.dp))
                Bar(if (node.size > 0) k.size.toFloat() / node.size else 0f, Accent, height = 4)
            }
        }
        if (loose > 0) item {
            Text("Files and small folders here: ${fmtBytes(loose)}", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        }
    }
}
