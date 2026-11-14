"use strict";
const TOKEN = document.querySelector('meta[name="token"]').content;
const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const S = { disk: null, results: null, config: null, selected: new Set(), trashed: new Set(), explorePath: "", polling: null };
const SAFETY_RANK = { safe: 0, review: 1, careful: 2 };

async function api(path, body) {
  const opt = { headers: { "X-Token": TOKEN } };
  if (body !== undefined) { opt.method = "POST"; opt.headers["Content-Type"] = "application/json"; opt.body = JSON.stringify(body); }
  const r = await fetch(path, opt);
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw Object.assign(new Error(j.error || r.statusText), { status: r.status, body: j });
  return j;
}

function fmt(n) {
  n = Number(n) || 0;
  const u = ["B", "KB", "MB", "GB", "TB"]; let i = 0;
  while (Math.abs(n) >= 1000 && i < u.length - 1) { n /= 1000; i++; }
  return (i < 2 ? n.toFixed(0) : n >= 100 ? n.toFixed(0) : n.toFixed(1)) + " " + u[i];
}
const num = (n) => Number(n || 0).toLocaleString();
function ago(t) {
  if (!t) return "";
  const d = (Date.now() / 1000 - t) / 86400;
  if (d < 1) return "today"; if (d < 2) return "yesterday"; if (d < 31) return Math.floor(d) + " days ago";
  if (d < 365) return Math.floor(d / 30) + " months ago"; return (d / 365).toFixed(1) + " years ago";
}
function esc(s) { return String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])); }
function shortPath(p) { const h = S.config?.home; return h && p && p.startsWith(h) ? "~" + p.slice(h.length) : p; }
function pathEl(p) { return `<div class="path" title="${esc(p)}"><bdi>${esc(shortPath(p))}</bdi></div>`; }
function toast(msg, ms = 3500) { const t = $("#toast"); t.textContent = msg; t.classList.remove("hidden"); clearTimeout(t._h); t._h = setTimeout(() => t.classList.add("hidden"), ms); }
function catInfo(id) { return S.results?.categories.find(c => c.id === id) || { label: id, color: "#888" }; }

// ------------------------------------------------------------ disk
function renderDisk() {
  const d = S.disk; if (!d) return;
  $("#disk-text").textContent = `${fmt(d.used)} of ${fmt(d.total)} used (${d.percent}%)`;
  const need = $("#disk-need");
  if (d.need_to_free > 0) { need.className = "disk-need bad"; need.textContent = `Free up ${fmt(d.need_to_free)} to get back to ${d.target}%. Available now: ${fmt(d.free)}.`; }
  else { need.className = "disk-need good"; need.textContent = `You're under your ${d.target}% target. Available: ${fmt(d.free)}.`; }
  $("#gauge-used").style.width = Math.min(100, d.percent) + "%";
  $("#gauge-used").classList.toggle("over", d.percent > d.target);
  $("#gauge-target").style.left = d.target + "%";
  $("#target").value = d.target; $("#target-val").textContent = d.target;
}
async function refreshDisk() { S.disk = await api("/api/disk"); renderDisk(); if (S.results) renderPlan(); }

// ------------------------------------------------------------ scanning
async function startScan() {
  const roots = $("#roots").value.split("\n").map(s => s.trim()).filter(Boolean);
  try { await api("/api/scan", { roots }); } catch (e) { if (e.status !== 409) return toast("Couldn't start: " + e.message); }
  S.selected.clear(); S.trashed.clear();
  pollStatus();
}
function pollStatus() {
  clearInterval(S.polling);
  $("#scan-btn").disabled = true; $("#cancel-btn").classList.remove("hidden");
  S.polling = setInterval(async () => {
    const st = await api("/api/status").catch(() => null); if (!st) return;
    if (st.scanning) {
      $("#scan-status").textContent = `Scanning... ${num(st.files)} files, ${fmt(st.bytes)} (${Math.round(st.elapsed)}s)  ${shortPath(st.current) || ""}`;
    } else {
      clearInterval(S.polling);
      $("#scan-btn").disabled = false; $("#scan-btn").textContent = "Scan again"; $("#cancel-btn").classList.add("hidden");
      $("#scan-status").textContent = "";
      loadResults();
    }
  }, 700);
}
async function loadResults() {
  let r; try { r = await api("/api/results"); } catch { return; }
  if (r.error) { toast("Scan failed: " + r.error, 8000); return; }
  S.results = r; S.disk = r.disk; renderDisk();
  $("#scan-status").textContent = `Last scan: ${num(r.files)} files, ${fmt(r.scanned)} in ${Math.round(r.duration)}s${r.cancelled ? " (cancelled, partial)" : ""}`;
  $("#scan-btn").textContent = "Scan again";
  renderAll();
}
function renderAll() {
  $("#empty-state").classList.add("hidden"); $("#overview").classList.remove("hidden");
  renderAlerts(); renderOverview(); fillCatSelects(); selectRecommended(); renderPlan(); renderAgents(); renderLarge(); loadTree(S.explorePath || "");
}

function renderAlerts() {
  const r = S.results, out = [];
  if (r.permission_denied_count) out.push(`<div class="alert">${num(r.permission_denied_count)} folders couldn't be read (for example <code>${esc(shortPath(r.permission_denied[0]))}</code>). For a complete scan, open <b>System Settings &gt; Privacy &amp; Security &gt; Full Disk Access</b>, turn on your Terminal app, then restart the app and scan again.</div>`);
  if (r.tm_snapshots && r.tm_snapshots.length) out.push(`<div class="alert info">${r.tm_snapshots.length} Time Machine local snapshot(s) found. They can hold a lot of space that shows up as "System Data" and macOS removes them when space runs low. To check: <code>tmutil listlocalsnapshots /</code></div>`);
  const sys = r.categories.find(c => c.id === "system");
  if (sys && sys.size > 0.25 * r.disk.used) out.push(`<div class="alert info">${fmt(sys.size)} wasn't covered by the scan: macOS itself, other users, local snapshots, swap/sleep files, and folders that couldn't be read.</div>`);
  $("#alerts").innerHTML = out.join("");
}

// ------------------------------------------------------------ overview
function planItems() {
  const r = S.results; if (!r) return [];
  const items = r.findings.filter(f => !S.trashed.has(f.path)).map(f => ({ ...f, source: "finding" }));
  for (const lf of r.large_files) {
    if (lf.in_finding || S.trashed.has(lf.path)) continue;
    const careful = ["media", "docs", "cloud", "messages", "backups"].includes(lf.category);
    items.push({ path: lf.path, name: lf.name, label: lf.name, category: lf.category, size: lf.size, newest: lf.mtime,
      safety: "review", trashable: !lf.locked && !["cloud", "messages", "backups"].includes(lf.category), kind: "file", source: "large",
      advice: careful ? "Large personal file. Keep it if you need it, or move it to an external drive." : "Large file. Delete it if you no longer need it." });
  }
  items.sort((a, b) => (SAFETY_RANK[a.safety] - SAFETY_RANK[b.safety]) || (b.size - a.size));
  return items;
}
function renderOverview() {
  const r = S.results, cats = r.categories.filter(c => c.size > 0).sort((a, b) => b.size - a.size);
  const total = cats.reduce((s, c) => s + c.size, 0) || 1;
  $("#cat-bar").innerHTML = cats.map(c => `<div title="${esc(c.label)}: ${fmt(c.size)}" style="width:${c.size / total * 100}%;background:${c.color}"></div>`).join("");
  $("#cat-list").innerHTML = cats.map(c => `<div class="cat-row" data-cat="${c.id}"><span class="dot" style="background:${c.color}"></span><span class="n">${esc(c.label)}</span><span class="sz">${fmt(c.size)}</span><span class="pc">${(c.size / total * 100).toFixed(0)}%</span></div>`).join("");
  $$("#cat-list .cat-row").forEach(el => el.onclick = () => { if (el.dataset.cat === "system") return; $("#plan-cat").value = el.dataset.cat; renderPlan(); showTab("plan"); });

  const cs = id => r.categories.find(c => c.id === id)?.size || 0;
  const bigFiles = r.large_files.filter(f => f.size >= 1e9);
  const recent = r.hotspots.reduce((s, h) => s + h.recent, 0);
  const items = planItems(); const safe = items.filter(i => i.safety === "safe").reduce((s, i) => s + i.size, 0);
  const q = [
    { v: fmt(cs("ai")), l: "AI agents & models", s: "models, agent data, editor/agent workspaces", tab: "agents" },
    { v: String(r.runaway.length), l: "Runaway-file suspects", s: r.runaway.length ? "folders filling up with generated files" : "nothing suspicious found", tab: "agents", warn: r.runaway.length > 0 },
    { v: fmt(cs("media")), l: "Photos & media", s: "Photos library, Movies, Music, Pictures", tab: "plan", cat: "media" },
    { v: fmt(bigFiles.reduce((s, f) => s + f.size, 0)), l: `${bigFiles.length} files over 1 GB`, s: "videos, disk images, archives...", tab: "large" },
    { v: fmt(cs("dev")), l: "Developer files", s: "node_modules, venvs, Xcode, package caches", tab: "plan", cat: "dev" },
    { v: fmt(recent), l: `Written in last ${r.recent_days} days`, s: "new or changed data", tab: "agents" },
    { v: fmt(safe), l: "Safe to clean", s: "rebuilds itself automatically", tab: "plan" },
  ];
  $("#quick").innerHTML = q.map((c, i) => `<div class="qcard ${c.warn ? "warn" : ""}" data-i="${i}"><div class="v">${esc(c.v)}</div><div class="l">${esc(c.l)}</div><div class="s">${esc(c.s)}</div></div>`).join("");
  $$("#quick .qcard").forEach(el => el.onclick = () => { const c = q[+el.dataset.i]; if (c.cat !== undefined) { $("#plan-cat").value = c.cat; renderPlan(); } showTab(c.tab); });

  $("#top-findings").innerHTML = items.filter(i => i.safety !== "careful").slice(0, 8).map(i => `<div class="row"><div class="grow"><span class="badge ${i.safety}">${i.safety}</span><b>${esc(i.label)}</b>${pathEl(i.path)}</div><div class="sz">${fmt(i.size)}</div></div>`).join("") || `<div class="muted">No cleanup candidates found.</div>`;

  const parts = [];
  if (r.growth && r.growth.folders.length) {
    parts.push(`<div class="muted small">Since the last scan (${ago(r.growth.since)}):</div>` + r.growth.folders.slice(0, 4).map(g => `<div class="row"><div class="grow">${pathEl(g.path)}</div><div class="sz">${growthText(g)}</div></div>`).join(""));
  }
  parts.push(`<div class="muted small">Most data written in the last ${r.recent_days} days:</div>` + (r.hotspots.slice(0, 5).map(h => `<div class="row"><div class="grow">${pathEl(h.path)}<div class="small muted">${num(h.recent_files)} files changed</div></div><div class="sz">${fmt(h.recent)}</div></div>`).join("") || `<div class="muted">Nothing big.</div>`));
  $("#recent-summary").innerHTML = parts.join("");
}

function fillCatSelects() {
  const cats = S.results.categories.filter(c => c.id !== "system" && c.size > 0);
  for (const sel of ["#plan-cat", "#large-cat"]) {
    const v = $(sel).value;
    $(sel).innerHTML = `<option value="">All categories</option>` + cats.map(c => `<option value="${c.id}">${esc(c.label)}</option>`).join("");
    $(sel).value = v;
  }
}

// ------------------------------------------------------------ plan
function selectRecommended() {
  S.selected.clear();
  let need = S.disk?.need_to_free || 0;
  for (const i of planItems()) {
    if (need <= 0) break;
    if (i.safety === "safe" && i.trashable) { S.selected.add(i.path); need -= i.size; }
  }
}
function renderPlan() {
  if (!S.results) return;
  const cat = $("#plan-cat").value, q = $("#plan-q").value.toLowerCase();
  const safeties = new Set($$(".plan-safety").filter(x => x.checked).map(x => x.value));
  const all = planItems();
  const items = all.filter(i => (!cat || i.category === cat) && safeties.has(i.safety) && (!q || (i.label + " " + i.path).toLowerCase().includes(q)));
  const sel = all.filter(i => S.selected.has(i.path)); const selSize = sel.reduce((s, i) => s + i.size, 0);
  const d = S.disk; const after = d ? (d.used - selSize) / d.total * 100 : 0;
  const safeTotal = all.filter(i => i.safety === "safe" && i.trashable).reduce((s, i) => s + i.size, 0);
  let msg = `Selected <b>${sel.length}</b> items = <b>${fmt(selSize)}</b> &rarr; disk would be <b>${after.toFixed(1)}%</b> used`;
  if (d) {
    if (d.need_to_free <= 0) msg += `. You're already under your ${d.target}% target.`;
    else if (selSize >= d.need_to_free) msg += `. <span style="color:#9be7b0">That reaches your ${d.target}% target.</span>`;
    else msg += `. Still need <b>${fmt(d.need_to_free - selSize)}</b> more for ${d.target}%.` + (safeTotal < d.need_to_free ? ` Safe items add up to only ${fmt(safeTotal)}, so you'll also need to choose some "review" items (large files, models, apps, Photos).` : "");
  }
  $("#plan-summary").innerHTML = msg + `<div class="small muted">Moving items to the Trash doesn't free space until you click <b>Empty Trash</b>.</div>`;
  const tb = $("#plan-table tbody");
  tb.innerHTML = items.slice(0, 400).map((i, n) => {
    const can = i.trashable;
    return `<tr class="${S.selected.has(i.path) ? "rec" : ""}" data-n="${n}">
      <td>${can ? `<input type="checkbox" ${S.selected.has(i.path) ? "checked" : ""}>` : ""}</td>
      <td><span class="badge ${i.safety}">${i.safety}</span><span class="label">${esc(i.label)}</span>${pathEl(i.path)}
        <div class="advice">${esc(i.advice || "")}</div>${i.command ? `<span class="cmd" title="Click to copy">${esc(i.command)}</span>` : ""}</td>
      <td><span class="dot" style="display:inline-block;background:${catInfo(i.category).color}"></span> ${esc(catInfo(i.category).label)}</td>
      <td class="r sz">${fmt(i.size)}</td><td class="small muted">${ago(i.newest)}</td>
      <td class="acts"><button class="mini reveal">Reveal</button>${can ? `<button class="mini trash">Trash</button>` : ""}${i.empty_trash ? `<button class="mini emptyt">Empty Trash</button>` : ""}</td></tr>`;
  }).join("") || `<tr><td colspan="6" class="muted">Nothing matches these filters.</td></tr>`;
  $$("tr[data-n]", tb).forEach(tr => {
    const i = items[+tr.dataset.n];
    const cb = $("input", tr); if (cb) cb.onchange = () => { cb.checked ? S.selected.add(i.path) : S.selected.delete(i.path); renderPlan(); };
    $(".reveal", tr).onclick = () => revealPath(i.path);
    const t = $(".trash", tr); if (t) t.onclick = () => trashPaths([i]);
    const e = $(".emptyt", tr); if (e) e.onclick = emptyTrash;
    const c = $(".cmd", tr); if (c) c.onclick = () => { navigator.clipboard?.writeText(i.command); toast("Command copied. Paste it into Terminal."); };
  });
}

async function revealPath(p) { try { await api("/api/reveal", { path: p }); } catch (e) { toast(e.message); } }
async function trashPaths(items) {
  if (!items.length) return toast("Nothing selected.");
  const total = items.reduce((s, i) => s + (i.size || 0), 0);
  const careful = items.filter(i => i.safety !== "safe").length;
  const list = items.slice(0, 8).map(i => "• " + shortPath(i.path)).join("\n") + (items.length > 8 ? `\n...and ${items.length - 8} more` : "");
  if (!confirm(`Move ${items.length} item(s) (${fmt(total)}) to the Trash?\n\n${list}\n\n${careful ? careful + " of them are marked 'review'. Make sure you don't need them.\n" : ""}You can restore them from the Trash until you empty it.`)) return;
  try {
    const r = await api("/api/trash", { paths: items.map(i => i.path) });
    r.trashed.forEach(t => { S.trashed.add(t.path); S.selected.delete(t.path); });
    S.disk = r.disk; if (r.results) Object.assign(S.results, r.results); renderDisk();
    toast(`Moved ${r.trashed.length} item(s) to the Trash.` + (r.failed.length ? ` ${r.failed.length} failed: ${r.failed[0].error}` : "") + " Empty the Trash to free the space.", 6000);
    renderPlan(); renderLarge(); renderOverview(); if (S.dups) renderDups(S.dups); loadTree(S.explorePath);
  } catch (e) { toast("Failed: " + e.message); }
}
async function emptyTrash() {
  if (!confirm("Empty the Trash? Items in it will be permanently deleted.")) return;
  try {
    const r = await api("/api/empty-trash", {}); S.disk = r.disk; if (r.results) Object.assign(S.results, r.results);
    renderDisk(); if (S.results) { renderPlan(); renderOverview(); renderLarge(); loadTree(S.explorePath); } toast("Trash emptied.");
  }
  catch (e) { toast("Couldn't empty Trash: " + e.message); }
}

const growthText = g => (g.was == null ? "&ge; " : "") + "+" + fmt(g.growth);

// ------------------------------------------------------------ agents / runaway
function renderAgents() {
  const r = S.results;
  $$(".recent-days").forEach(e => e.textContent = r.recent_days);
  const typeLabel = { pattern: "repeated names", burst: "burst of new files", nesting: "self-nesting folders" };
  $("#runaway").innerHTML = r.runaway.length ? `<table class="tbl"><thead><tr><th>Folder</th><th>What looks wrong</th><th class="r">Files</th><th class="r">Size</th><th>Last write</th><th></th></tr></thead><tbody>` +
    r.runaway.map((x, n) => `<tr data-n="${n}"><td><span class="badge t">${typeLabel[x.type] || x.type}</span>${pathEl(x.path)}</td>
      <td>${esc(x.detail)}${x.sample ? `<div class="small muted">e.g. <code>${esc(x.sample)}</code></div>` : ""}${x.recent_files && x.type !== "burst" ? `<div class="small muted">${num(x.recent_files)} new in last ${r.recent_days} days</div>` : ""}</td>
      <td class="r">${num(x.files)}</td><td class="r sz">${x.size ? fmt(x.size) : ""}</td><td class="small muted">${ago(x.newest)}</td>
      <td class="acts"><button class="mini reveal">Reveal</button><button class="mini explore">Explore</button></td></tr>`).join("") + `</tbody></table>
      <p class="small muted">If one of these belongs to an AI agent or script, stop it first, check what it was doing, then delete the folder (Explore &gt; Trash).</p>`
    : `<div class="muted">No folders with runaway file generation found.</div>`;
  $$("#runaway tr[data-n]").forEach(tr => { const x = r.runaway[+tr.dataset.n]; $(".reveal", tr).onclick = () => revealPath(x.path); $(".explore", tr).onclick = () => { loadTree(x.path); showTab("explore"); }; });

  const maxH = Math.max(1, ...r.hotspots.map(h => h.recent));
  $("#hotspots").innerHTML = r.hotspots.map(h => `<div class="row"><div class="grow">${pathEl(h.path)}<div class="bar"><div style="width:${h.recent / maxH * 100}%"></div></div><div class="small muted">${num(h.recent_files)} files changed · folder total ${fmt(h.size)}</div></div><div class="sz">${fmt(h.recent)}</div></div>`).join("") || `<div class="muted">No folder got more than 500 MB of new data.</div>`;
  if (r.growth) {
    $("#growth").innerHTML = `<div class="small muted">Compared with the scan from ${ago(r.growth.since)}${r.growth.disk_was ? ` · disk used then: ${fmt(r.growth.disk_was)}, now: ${fmt(r.disk.used)}` : ""}</div>` +
      (r.growth.folders.map(g => `<div class="row"><div class="grow">${pathEl(g.path)}<div class="small muted">${g.was == null ? `under ${fmt(g.was_under)}` : fmt(g.was)} &rarr; ${fmt(g.size)}</div></div><div class="sz">${growthText(g)}</div></div>`).join("") || `<div class="muted">No folder grew by more than 100 MB.</div>`);
  } else $("#growth").innerHTML = `<div class="muted">This is the first scan. Scan again in a few days (or after an agent runs) to see exactly which folders grew.</div>`;

  const ai = r.findings.filter(f => f.category === "ai" && !S.trashed.has(f.path));
  $("#ai-list").innerHTML = ai.map(f => `<div class="row"><div class="grow"><span class="badge ${f.safety}">${f.safety}</span><b>${esc(f.label)}</b>${pathEl(f.path)}<div class="small muted">${esc(f.advice)} ${f.recent ? `· ${fmt(f.recent)} written in last ${r.recent_days} days` : ""}</div></div><div class="sz">${fmt(f.size)}</div></div>`).join("") || `<div class="muted">No AI agent / model folders over 50 MB found.</div>`;
}

// ------------------------------------------------------------ large files
function renderLarge() {
  if (!S.results) return;
  const min = +$("#large-min").value, age = +$("#large-age").value, cat = $("#large-cat").value, now = Date.now() / 1000;
  const files = S.results.large_files.filter(f => !S.trashed.has(f.path) && f.size >= min && (!age || now - f.mtime >= age * 86400) && (!cat || f.category === cat));
  const tb = $("#large-table tbody");
  tb.innerHTML = files.map((f, n) => `<tr data-n="${n}"><td><b>${esc(f.name)}</b>${pathEl(f.path)}</td><td>${esc(catInfo(f.category).label)}</td><td class="r sz">${fmt(f.size)}</td><td class="small muted">${ago(f.mtime)}</td><td class="acts"><button class="mini reveal">Reveal</button>${f.locked ? "" : `<button class="mini trash">Trash</button>`}</td></tr>`).join("") || `<tr><td colspan="5" class="muted">No files match.</td></tr>`;
  $$("tr[data-n]", tb).forEach(tr => { const f = files[+tr.dataset.n]; $(".reveal", tr).onclick = () => revealPath(f.path); const t = $(".trash", tr); if (t) t.onclick = () => trashPaths([{ ...f, safety: "review" }]); });
}

// ------------------------------------------------------------ duplicates
async function startDups() {
  try { await api("/api/duplicates", {}); } catch (e) { if (e.status !== 409) return toast(e.message); }
  $("#dup-btn").disabled = true;
  const h = setInterval(async () => {
    const d = await api("/api/duplicates");
    const p = d.progress || {};
    $("#dup-status").textContent = d.running ? `Comparing ${num(p.done_files || 0)} / ${num(p.total_files || 0)} candidate files...` : "";
    if (!d.running) { clearInterval(h); $("#dup-btn").disabled = false; S.dups = d.results || []; renderDups(S.dups); }
  }, 800);
}
function renderDups(groups) {
  const g = groups.map(gr => ({ ...gr, files: gr.files.filter(f => !S.trashed.has(f.path)) })).filter(gr => gr.files.length > 1);
  const wasted = g.reduce((s, x) => s + x.size * (x.files.length - 1), 0);
  $("#dup-status").textContent = `${g.length} duplicate groups, ${fmt(wasted)} could be freed.`;
  $("#dups").innerHTML = g.map((gr, gi) => `<div class="dupgroup" data-g="${gi}"><div><b>${gr.files.length} copies</b> · ${fmt(gr.size)} each · <span class="muted">extra copies use ${fmt(gr.size * (gr.files.length - 1))}</span></div>` +
    gr.files.map((f, fi) => `<div class="row" data-f="${fi}"><div class="grow">${pathEl(f.path)}<div class="small muted">changed ${ago(f.mtime)}</div></div>${fi === 0 ? `<span class="badge safe">keep</span>` : ""}<button class="mini reveal">Reveal</button>${f.locked ? "" : `<button class="mini trash">Trash</button>`}</div>`).join("") + `</div>`).join("");
  $$("#dups .dupgroup").forEach(el => { const gr = g[+el.dataset.g]; $$(".row", el).forEach(row => { const f = gr.files[+row.dataset.f]; $(".reveal", row).onclick = () => revealPath(f.path); const t = $(".trash", row); if (t) t.onclick = () => trashPaths([{ path: f.path, size: gr.size, safety: "review" }]); }); });
}

// ------------------------------------------------------------ explore
async function loadTree(path) {
  let t; try { t = await api("/api/tree?path=" + encodeURIComponent(path || "")); } catch (e) { if (path) { toast(e.message); } return; }
  S.explorePath = t.path;
  const crumbs = [`<a data-p="">All scanned folders</a>`];
  if (t.path) {
    const root = S.results.roots.find(r => t.path === r || t.path.startsWith(r.replace(/\/$/, "") + "/"));
    if (root) {
      let acc = root; crumbs.push(`<span>/</span><a data-p="${esc(root)}">${esc(shortPath(root))}</a>`);
      for (const part of t.path.slice(root.length).split("/").filter(Boolean)) { acc += "/" + part; crumbs.push(`<span>/</span><a data-p="${esc(acc)}">${esc(part)}</a>`); }
    }
  }
  $("#crumbs").innerHTML = crumbs.join("");
  $$("#crumbs a").forEach(a => a.onclick = () => loadTree(a.dataset.p));
  const kids = t.children.filter(c => !S.trashed.has(c.path)); const max = Math.max(1, ...kids.map(c => c.size));
  $("#explore").innerHTML = `<div class="xrow small muted"><div>Name</div><div class="r">Size</div><div>Last changed</div><div></div></div>` + kids.map((c, n) => `<div class="xrow ${c.kind === "dir" && c.has_children ? "dir" : ""}" data-n="${n}">
    <div><div class="nm">${c.kind === "dir" ? "📁" : c.kind === "file" ? "📄" : "…"} ${esc(t.path ? c.name : shortPath(c.name))}</div><div class="bar"><div style="width:${c.size / max * 100}%"></div></div></div>
    <div class="r sz">${fmt(c.size)}</div><div class="small muted">${ago(c.newest)}</div>
    <div class="acts">${c.path ? `<button class="mini reveal">Reveal</button>${t.path && !c.locked ? `<button class="mini trash">Trash</button>` : ""}` : ""}</div></div>`).join("");
  $$("#explore .xrow[data-n]").forEach(row => {
    const c = kids[+row.dataset.n];
    if (row.classList.contains("dir")) $(".nm", row).onclick = () => loadTree(c.path);
    const rv = $(".reveal", row); if (rv) rv.onclick = () => revealPath(c.path);
    const tr = $(".trash", row); if (tr) tr.onclick = () => trashPaths([{ path: c.path, size: c.size, safety: "review" }]);
  });
}

// ------------------------------------------------------------ tabs & init
function showTab(name) {
  $$("#tabs button").forEach(b => b.classList.toggle("active", b.dataset.tab === name));
  $$(".tab").forEach(t => t.classList.toggle("hidden", t.id !== "tab-" + name));
  window.scrollTo({ top: 0 });
}
$$("#tabs button").forEach(b => b.onclick = () => showTab(b.dataset.tab));
$$("[data-goto]").forEach(b => b.onclick = () => showTab(b.dataset.goto));
$("#scan-btn").onclick = startScan;
$("#cancel-btn").onclick = () => api("/api/cancel", {});
$("#empty-trash-btn").onclick = emptyTrash;
$("#sel-rec").onclick = () => { selectRecommended(); renderPlan(); };
$("#sel-none").onclick = () => { S.selected.clear(); renderPlan(); };
$("#trash-sel").onclick = () => trashPaths(planItems().filter(i => S.selected.has(i.path)));
["#plan-cat", "#plan-q"].forEach(s => $(s).oninput = renderPlan);
$$(".plan-safety").forEach(c => c.onchange = renderPlan);
["#large-min", "#large-age", "#large-cat"].forEach(s => $(s).onchange = renderLarge);
$("#dup-btn").onclick = startDups;
$("#target").oninput = () => { $("#target-val").textContent = $("#target").value; };
$("#target").onchange = async () => { const r = await api("/api/settings", { target: +$("#target").value }); S.disk = r.disk; renderDisk(); if (S.results) { selectRecommended(); renderPlan(); } };

(async function init() {
  S.config = await api("/api/config");
  $("#roots").value = S.config.default_roots.join("\n");
  await refreshDisk();
  const st = await api("/api/status");
  if (st.scanning) pollStatus(); else if (st.has_results) loadResults();
  setInterval(refreshDisk, 30000);
})();
