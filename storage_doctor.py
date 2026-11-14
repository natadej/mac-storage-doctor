#!/usr/bin/env python3
"""Mac Storage Doctor - find what is filling your disk and get back to your target.

Usage:
  python3 storage_doctor.py                 # start the web app (opens your browser)
  python3 storage_doctor.py report          # scan and print a summary in the terminal
  python3 storage_doctor.py check --notify  # quick disk check (used by the monitor)
  python3 storage_doctor.py install-monitor # check every 6 hours, notify when over target
  python3 storage_doctor.py uninstall-monitor
"""

import argparse
import json
import os
import secrets
import shutil
import subprocess
import sys
import threading
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

sys.setrecursionlimit(20000)
APP_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, APP_DIR)

import rules as R  # noqa: E402
from duplicates import find_duplicates  # noqa: E402
from scanner import RECENT_DAYS, Scanner, default_roots, normalize_roots  # noqa: E402

IS_MAC = sys.platform == "darwin"
HOME = os.path.normpath(os.path.expanduser("~"))
DATA_DIR = os.environ.get("STORAGE_DOCTOR_DATA") or os.path.join(HOME, ".storage-doctor")
WEB_DIR = os.path.join(APP_DIR, "web")
LAUNCH_AGENT = os.path.join(HOME, "Library", "LaunchAgents", "com.storagedoctor.monitor.plist")
DEFAULT_TARGET = 50
SNAPSHOT_MIN = 50 * R.MB

PROTECTED = {HOME, "/", "/Applications", "/Library", "/System", "/Users", "/private", "/usr", "/opt", "/bin", "/sbin",
             "/var", "/etc", "/tmp", "/Volumes"}
PROTECTED |= {os.path.join(HOME, d) for d in ("Library", "Documents", "Desktop", "Downloads", "Pictures", "Movies",
                                             "Music", "Applications", ".Trash", "Public",
                                             "Library/Application Support", "Library/Containers",
                                             "Library/Group Containers", "Library/Caches", "Library/Mobile Documents",
                                             "Library/CloudStorage")}


# ---------------------------------------------------------------- utilities
def human(n):
    n = float(n)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if abs(n) < 1000 or unit == "TB":
            return f"{n:.1f} {unit}" if unit not in ("B", "KB") else f"{n:.0f} {unit}"
        n /= 1000


def ensure_data_dir():
    os.makedirs(os.path.join(DATA_DIR, "scans"), exist_ok=True)


def load_settings():
    try:
        with open(os.path.join(DATA_DIR, "settings.json")) as f:
            return json.load(f)
    except (OSError, ValueError):
        return {"target": DEFAULT_TARGET}


def save_settings(s):
    ensure_data_dir()
    with open(os.path.join(DATA_DIR, "settings.json"), "w") as f:
        json.dump(s, f, indent=2)


def disk_info(target=None):
    target = target if target is not None else load_settings().get("target", DEFAULT_TARGET)
    path = os.environ.get("STORAGE_DOCTOR_DISK") or ("/System/Volumes/Data" if os.path.isdir("/System/Volumes/Data") else "/")
    du = shutil.disk_usage(path)
    used = du.total - du.free  # whole APFS container (system + data volumes)
    pct = used / du.total * 100 if du.total else 0
    need = max(0, used - du.total * target / 100)
    return {"total": du.total, "used": used, "free": du.free, "percent": round(pct, 1), "target": target,
            "need_to_free": need, "path": path}


def tm_local_snapshots():
    if not IS_MAC:
        return None
    try:
        out = subprocess.run(["tmutil", "listlocalsnapshots", "/"], capture_output=True, text=True, timeout=15).stdout
        return [ln.strip() for ln in out.splitlines() if "com.apple" in ln]
    except (OSError, subprocess.SubprocessError):
        return None


def notify(title, message):
    if IS_MAC:
        script = 'on run argv\ndisplay notification (item 2 of argv) with title (item 1 of argv)\nend run'
        subprocess.run(["osascript", "-e", script, title, message], capture_output=True)
    else:
        print(f"[notify] {title}: {message}")


def trash_dir():
    return os.environ.get("STORAGE_DOCTOR_TRASH") or (
        os.path.join(HOME, ".Trash") if IS_MAC else os.path.join(HOME, ".local", "share", "Trash", "files"))


def move_to_trash(path):
    """Move to the macOS Trash via Finder (so 'Put Back' works); fall back to a plain move."""
    if IS_MAC:
        script = 'on run argv\ntell application "Finder" to delete (POSIX file (item 1 of argv) as alias)\nend run'
        r = subprocess.run(["osascript", "-e", script, path], capture_output=True, text=True)
        if r.returncode == 0:
            return
    trash = trash_dir()
    os.makedirs(trash, exist_ok=True)
    base = os.path.basename(path.rstrip(os.sep))
    dest = os.path.join(trash, base)
    i = 1
    while os.path.exists(dest):
        stem, ext = os.path.splitext(base)
        dest = os.path.join(trash, f"{stem} {i}{ext}")
        i += 1
    shutil.move(path, dest)


def empty_trash():
    if IS_MAC:
        r = subprocess.run(["osascript", "-e", 'tell application "Finder" to empty trash'], capture_output=True, text=True)
        if r.returncode != 0:
            raise RuntimeError(r.stderr.strip() or "Finder could not empty the Trash")
        return
    trash = trash_dir()
    if os.path.isdir(trash):
        for name in os.listdir(trash):
            p = os.path.join(trash, name)
            shutil.rmtree(p) if os.path.isdir(p) and not os.path.islink(p) else os.remove(p)


def reveal(path):
    if IS_MAC:
        subprocess.run(["open", "-R", path])
    else:
        subprocess.Popen(["xdg-open", os.path.dirname(path)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


# ---------------------------------------------------------------- history
def save_scan_history(scanner, disk):
    ensure_data_dir()
    rec = {"time": scanner.now, "roots": scanner.roots, "disk": disk, "scanned": scanner.tree["size"],
           "categories": dict(scanner.cat_totals), "folders": scanner.snapshot(SNAPSHOT_MIN),
           "snapshot_min": SNAPSHOT_MIN}
    path = os.path.join(DATA_DIR, "scans", time.strftime("%Y%m%d-%H%M%S", time.localtime(scanner.now)) + ".json")
    with open(path, "w") as f:
        json.dump(rec, f)
    files = sorted(os.listdir(os.path.join(DATA_DIR, "scans")))
    for old in files[:-20]:
        try:
            os.remove(os.path.join(DATA_DIR, "scans", old))
        except OSError:
            pass


def previous_scan(roots, before):
    d = os.path.join(DATA_DIR, "scans")
    try:
        files = sorted(os.listdir(d), reverse=True)
    except OSError:
        return None
    for name in files:
        try:
            with open(os.path.join(d, name)) as f:
                rec = json.load(f)
        except (OSError, ValueError):
            continue
        if rec.get("time", 0) < before - 60 and rec.get("roots") == roots:
            return rec
    return None


def growth_since(prev, snap, min_bytes=100 * R.MB, limit=30):
    """Folders that grew since the previous scan. Folders missing from the previous snapshot were
    smaller than SNAPSHOT_MIN then, so their growth is reported as a lower bound (was=None)."""
    if not prev:
        return None
    old = prev.get("folders", {})
    floor = prev.get("snapshot_min", SNAPSHOT_MIN)
    deltas = {}
    for p, s in snap.items():
        d = s - old[p] if p in old else s - floor
        if d >= min_bytes:
            deltas[p] = d
    keep = []
    for p, d in deltas.items():
        explained = any(q != p and q.startswith(p + os.sep) and dq >= 0.7 * d for q, dq in deltas.items())
        if not explained:
            keep.append({"path": p, "growth": d, "size": snap[p], "was": old.get(p), "was_under": floor})
    keep.sort(key=lambda x: -x["growth"])
    return {"since": prev["time"], "disk_was": prev.get("disk", {}).get("used"), "folders": keep[:limit]}


# ---------------------------------------------------------------- app state
class State:
    def __init__(self):
        self.lock = threading.Lock()
        self.scanner = None
        self.scanning = False
        self.started = 0
        self.finished = 0
        self.results = None
        self.dup = {"running": False, "results": None, "progress": {}}

    def start_scan(self, roots):
        with self.lock:
            if self.scanning:
                return False
            sc = Scanner(roots, home=HOME)
            self.scanner = sc
            self.scanning = True
            self.started = time.time()
            self.dup = {"running": False, "results": None, "progress": {}}
        threading.Thread(target=self._run, args=(sc,), daemon=True).start()
        return True

    def _run(self, sc):
        try:
            sc.run()
            disk = disk_info()
            prev = previous_scan(sc.roots, sc.now)
            snap = sc.snapshot(SNAPSHOT_MIN)
            growth = growth_since(prev, snap)
            if not sc.cancelled:
                save_scan_history(sc, disk)
            self.results = build_results(sc, disk, growth, time.time() - self.started)
        except Exception as e:  # surface the error in the UI instead of hanging
            self.results = {"error": repr(e)}
        finally:
            self.scanning = False
            self.finished = time.time()

    def status(self):
        sc = self.scanner
        return {"scanning": self.scanning, "has_results": self.results is not None,
                "files": sc.files if sc else 0, "dirs": sc.dirs if sc else 0, "bytes": sc.bytes if sc else 0,
                "current": sc.current if sc else "", "elapsed": (time.time() - self.started) if self.scanning else
                (self.finished - self.started if self.finished else 0)}


def category_list(sc, disk):
    totals = dict(sc.cat_totals)
    totals["system"] = max(0, disk["used"] - sc.tree["size"])
    return [{"id": cid, "label": label, "color": color, "size": totals.get(cid, 0)} for cid, label, color in R.CATEGORIES]


def refresh_results():
    """Bring the cached results in line with the scanner after a cleanup action."""
    sc, res = STATE.scanner, STATE.results
    if not (sc and res):
        return None
    disk = disk_info()
    res.update(disk=disk, scanned=sc.tree["size"], categories=category_list(sc, disk),
               findings=sc.findings[:600], large_files=sc.large_files[:400])
    return {k: res[k] for k in ("scanned", "categories", "findings", "large_files")}


def build_results(sc, disk, growth, duration):
    cats = category_list(sc, disk)
    snaps = tm_local_snapshots()
    return {
        "time": sc.now,
        "duration": duration,
        "platform": sys.platform,
        "home": HOME,
        "roots": sc.roots,
        "disk": disk,
        "scanned": sc.tree["size"],
        "files": sc.files,
        "categories": cats,
        "findings": sc.findings[:600],
        "large_files": sc.large_files[:400],
        "runaway": sc.runaway[:100],
        "hotspots": sc.hotspots(),
        "growth": growth,
        "recent_days": RECENT_DAYS,
        "dup_candidates": len(sc.dup_candidates),
        "permission_denied": sc.perm_denied[:30],
        "permission_denied_count": len(sc.perm_denied),
        "tm_snapshots": snaps,
        "cancelled": sc.cancelled,
    }


STATE = State()


def allowed_target(path):
    """Only allow trashing things inside scanned folders, never the important top-level folders."""
    sc = STATE.scanner
    if not sc or not path or not os.path.isabs(path):
        return False
    path = os.path.normpath(path)
    if path in PROTECTED or path in sc.roots:
        return False
    if sc.is_locked(path) or sc.contains_locked(path):
        return False
    if not any(path.startswith(r.rstrip(os.sep) + os.sep) for r in sc.roots):
        return False
    return os.path.lexists(path)


# ---------------------------------------------------------------- HTTP
class Handler(BaseHTTPRequestHandler):
    token = ""
    port = 0

    def log_message(self, *a):
        pass

    def _host_ok(self):
        host = (self.headers.get("Host") or "").split(":")[0]
        return host in ("127.0.0.1", "localhost") or os.environ.get("STORAGE_DOCTOR_ALLOW_ANY_HOST") == "1"

    def _send(self, code, body, ctype="application/json"):
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(data)

    def _json_body(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n > 1_000_000:
            raise ValueError("body too large")
        return json.loads(self.rfile.read(n) or b"{}")

    def do_GET(self):
        if not self._host_ok():
            return self._send(403, {"error": "bad host"})
        u = urlparse(self.path)
        if u.path in ("/", "/index.html"):
            with open(os.path.join(WEB_DIR, "index.html"), "rb") as f:
                html = f.read().replace(b"{{TOKEN}}", self.token.encode())
            return self._send(200, html, "text/html; charset=utf-8")
        if u.path.startswith("/static/"):
            name = os.path.basename(u.path)
            fp = os.path.join(WEB_DIR, name)
            if not os.path.isfile(fp):
                return self._send(404, {"error": "not found"})
            ctype = {"js": "text/javascript", "css": "text/css", "svg": "image/svg+xml"}.get(name.rsplit(".", 1)[-1],
                                                                                         "application/octet-stream")
            with open(fp, "rb") as f:
                return self._send(200, f.read(), ctype + "; charset=utf-8")
        if not u.path.startswith("/api/"):
            return self._send(404, {"error": "not found"})
        if self.headers.get("X-Token") != self.token:
            return self._send(403, {"error": "bad token"})
        q = parse_qs(u.query)
        if u.path == "/api/disk":
            return self._send(200, disk_info())
        if u.path == "/api/config":
            return self._send(200, {"home": HOME, "default_roots": default_roots(HOME), "settings": load_settings(),
                                    "platform": sys.platform, "monitor_installed": os.path.exists(LAUNCH_AGENT)})
        if u.path == "/api/status":
            return self._send(200, STATE.status())
        if u.path == "/api/results":
            if STATE.results and STATE.scanner and not STATE.scanning:
                res = dict(STATE.results)
                res["disk"] = disk_info()
                return self._send(200, res)
            return self._send(404, {"error": "no results yet"})
        if u.path == "/api/tree":
            sc = STATE.scanner
            if not sc or not sc.tree or STATE.scanning:
                return self._send(404, {"error": "no scan"})
            path = q.get("path", [""])[0]
            node = sc.index.get(path)
            if node is None:
                return self._send(404, {"error": "folder not in scan (too small or not scanned)"})
            kids = [{k: c.get(k) for k in ("name", "path", "kind", "size", "files", "newest", "recent")}
                    | {"has_children": bool(c.get("children")),
                       "locked": bool(c.get("path")) and (sc.is_locked(c["path"]) or sc.contains_locked(c["path"]))}
                    for c in node["children"]]
            return self._send(200, {"path": path, "name": node["name"], "size": node["size"], "children": kids})
        if u.path == "/api/duplicates":
            d = STATE.dup
            sc = STATE.scanner
            for g in d["results"] or []:
                for f in g["files"]:
                    f["locked"] = bool(sc and sc.is_locked(f["path"]))
            return self._send(200, {"running": d["running"], "results": d["results"], "progress": d["progress"]})
        return self._send(404, {"error": "unknown endpoint"})

    def do_POST(self):
        if not self._host_ok():
            return self._send(403, {"error": "bad host"})
        if self.headers.get("X-Token") != self.token:
            return self._send(403, {"error": "bad token"})
        u = urlparse(self.path)
        try:
            body = self._json_body()
        except ValueError:
            return self._send(400, {"error": "invalid JSON"})
        if u.path == "/api/scan":
            roots = normalize_roots(body.get("roots") or default_roots(HOME))
            if not roots:
                return self._send(400, {"error": "no valid folders to scan"})
            ok = STATE.start_scan(roots)
            return self._send(200 if ok else 409, {"started": ok, "roots": roots})
        if u.path == "/api/cancel":
            if STATE.scanner:
                STATE.scanner.cancelled = True
            STATE.dup["progress"]["cancelled"] = True
            return self._send(200, {"ok": True})
        if u.path == "/api/settings":
            s = load_settings()
            t = body.get("target")
            if isinstance(t, (int, float)) and 10 <= t <= 95:
                s["target"] = round(t)
            save_settings(s)
            return self._send(200, {"settings": s, "disk": disk_info()})
        if u.path == "/api/reveal":
            path = body.get("path", "")
            sc = STATE.scanner
            if not sc or not any(path == r or path.startswith(r.rstrip(os.sep) + os.sep) for r in sc.roots):
                return self._send(400, {"error": "path not in scan"})
            reveal(path)
            return self._send(200, {"ok": True})
        if u.path == "/api/trash":
            paths = body.get("paths") or ([body["path"]] if body.get("path") else [])
            done, failed = [], []
            for p in paths:
                p = os.path.normpath(p)
                if not allowed_target(p):
                    failed.append({"path": p, "error": "not allowed (protected, personal data, app-managed, missing, or outside scanned folders)"})
                    continue
                try:
                    move_to_trash(p)
                    size = STATE.scanner.remove_path(p)
                    STATE.scanner.moved_to_trash(size, trash_dir())
                    done.append({"path": p, "size": size})
                except Exception as e:
                    failed.append({"path": p, "error": str(e)})
            return self._send(200, {"trashed": done, "failed": failed, "results": refresh_results(), "disk": disk_info()})
        if u.path == "/api/empty-trash":
            try:
                empty_trash()
            except Exception as e:
                return self._send(500, {"error": str(e)})
            if STATE.scanner and STATE.scanner.tree and not STATE.scanning:
                STATE.scanner.trash_emptied(trash_dir())
            return self._send(200, {"ok": True, "results": refresh_results(), "disk": disk_info()})
        if u.path == "/api/duplicates":
            sc = STATE.scanner
            if not sc or STATE.scanning:
                return self._send(400, {"error": "run a scan first"})
            if STATE.dup["running"]:
                return self._send(409, {"error": "already running"})
            STATE.dup = {"running": True, "results": None, "progress": {}}

            def job():
                try:
                    STATE.dup["results"] = find_duplicates(sc.dup_candidates, STATE.dup["progress"])
                except InterruptedError:
                    STATE.dup["results"] = []
                finally:
                    STATE.dup["running"] = False

            threading.Thread(target=job, daemon=True).start()
            return self._send(200, {"started": True})
        if u.path == "/api/monitor":
            try:
                msg = install_monitor(body.get("target")) if body.get("enable") else uninstall_monitor()
            except Exception as e:
                return self._send(500, {"error": str(e)})
            return self._send(200, {"ok": True, "message": msg, "installed": os.path.exists(LAUNCH_AGENT)})
        return self._send(404, {"error": "unknown endpoint"})


def serve(port, open_browser=True, host="127.0.0.1"):
    Handler.token = os.environ.get("STORAGE_DOCTOR_TOKEN") or secrets.token_urlsafe(24)
    httpd = None
    for p in range(port, port + 20):
        try:
            httpd = ThreadingHTTPServer((host, p), Handler)
            break
        except OSError:
            continue
    if httpd is None:
        sys.exit(f"Could not open a port between {port} and {port + 19}")
    p = httpd.server_address[1]
    url = f"http://127.0.0.1:{p}/"
    print(f"Mac Storage Doctor is running at {url}  (press Ctrl+C to stop)")
    if open_browser:
        threading.Timer(0.6, lambda: webbrowser.open(url)).start()
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nBye!")


# ---------------------------------------------------------------- monitor
def check(target=None, do_notify=False, growth_alert_gb=10):
    d = disk_info(target)
    ensure_data_dir()
    hist = os.path.join(DATA_DIR, "usage_history.jsonl")
    last = None
    try:
        with open(hist) as f:
            lines = f.read().strip().splitlines()
            for ln in reversed(lines):
                rec = json.loads(ln)
                if time.time() - rec["time"] >= 20 * 3600:  # compare with roughly a day ago
                    last = rec
                    break
            if last is None and lines:
                last = json.loads(lines[0])
    except (OSError, ValueError, KeyError):
        pass
    with open(hist, "a") as f:
        f.write(json.dumps({"time": time.time(), "used": d["used"], "total": d["total"]}) + "\n")

    msgs = []
    if d["percent"] > d["target"]:
        msgs.append(f"Disk is {d['percent']:.0f}% full (target {d['target']}%). Free {human(d['need_to_free'])}.")
    if last and d["used"] - last["used"] >= growth_alert_gb * R.GB:
        hours = (time.time() - last["time"]) / 3600
        msgs.append(f"Disk usage grew {human(d['used'] - last['used'])} in {hours:.0f} h. Something may be "
                    f"writing files in a loop.")
    print(f"Used {human(d['used'])} of {human(d['total'])} ({d['percent']}%), target {d['target']}%")
    for m in msgs:
        print("  ! " + m)
    if do_notify and msgs:
        notify("Mac Storage Doctor", " ".join(msgs) + " Open the app to see what's growing.")
    return d, msgs


def install_monitor(target=None):
    if not IS_MAC:
        raise RuntimeError("The background monitor uses macOS launchd and only works on a Mac.")
    if target:
        s = load_settings()
        s["target"] = int(target)
        save_settings(s)
    os.makedirs(os.path.dirname(LAUNCH_AGENT), exist_ok=True)
    ensure_data_dir()
    plist = f"""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>com.storagedoctor.monitor</string>
  <key>ProgramArguments</key>
  <array>
    <string>{sys.executable}</string>
    <string>{os.path.abspath(__file__)}</string>
    <string>check</string>
    <string>--notify</string>
  </array>
  <key>StartInterval</key><integer>21600</integer>
  <key>RunAtLoad</key><true/>
  <key>StandardOutPath</key><string>{os.path.join(DATA_DIR, "monitor.log")}</string>
  <key>StandardErrorPath</key><string>{os.path.join(DATA_DIR, "monitor.log")}</string>
</dict>
</plist>
"""
    with open(LAUNCH_AGENT, "w") as f:
        f.write(plist)
    uid = str(os.getuid())
    subprocess.run(["launchctl", "bootout", f"gui/{uid}", LAUNCH_AGENT], capture_output=True)
    r = subprocess.run(["launchctl", "bootstrap", f"gui/{uid}", LAUNCH_AGENT], capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.strip() or "launchctl failed")
    return "Monitor installed: checks every 6 hours and notifies you when the disk is over target or grows fast."


def uninstall_monitor():
    if os.path.exists(LAUNCH_AGENT):
        subprocess.run(["launchctl", "bootout", f"gui/{os.getuid()}", LAUNCH_AGENT], capture_output=True)
        os.remove(LAUNCH_AGENT)
        return "Monitor removed."
    return "Monitor was not installed."


# ---------------------------------------------------------------- terminal report
def report(roots, top=15):
    sc = Scanner(roots or default_roots(HOME), home=HOME)
    print(f"Scanning {', '.join(sc.roots)} ...")
    t0 = time.time()
    th = threading.Thread(target=sc.run)
    th.start()
    while th.is_alive():
        th.join(1)
        print(f"\r  {sc.files:,} files, {human(sc.bytes)}", end="", flush=True)
    d = disk_info()
    print(f"\rScanned {sc.files:,} files ({human(sc.tree['size'])}) in {time.time() - t0:.0f}s" + " " * 20)
    print(f"\nDisk: {human(d['used'])} used of {human(d['total'])} ({d['percent']}%)  target {d['target']}%  "
          f"-> free {human(d['need_to_free'])}\n")
    totals = dict(sc.cat_totals)
    totals["system"] = max(0, d["used"] - sc.tree["size"])
    print("By category:")
    for cid, label, _ in R.CATEGORIES:
        if totals.get(cid):
            print(f"  {label:<32} {human(totals[cid]):>10}")
    print("\nBiggest cleanup candidates:")
    for f in [f for f in sc.findings if f["safety"] != "careful"][:top]:
        print(f"  [{f['safety']:<6}] {human(f['size']):>10}  {f['label']:<34} {f['path']}")
    if sc.runaway:
        print("\nPossible runaway file generation:")
        for r in sc.runaway[:10]:
            print(f"  {r['detail']:<55} {r['path']}")
    hs = sc.hotspots()
    if hs:
        print(f"\nWritten in the last {RECENT_DAYS} days:")
        for h in hs[:10]:
            print(f"  {human(h['recent']):>10}  {h['path']}")
    if sc.perm_denied:
        print(f"\n{len(sc.perm_denied)} folders could not be read. Give your terminal Full Disk Access "
              "(System Settings > Privacy & Security) for a complete scan.")


def main():
    ap = argparse.ArgumentParser(description="Find what's filling your Mac's disk.")
    sub = ap.add_subparsers(dest="cmd")
    s = sub.add_parser("serve", help="start the web app (default)")
    s.add_argument("--port", type=int, default=8765)
    s.add_argument("--no-browser", action="store_true")
    s.add_argument("--host", default="127.0.0.1")
    r = sub.add_parser("report", help="scan and print a summary")
    r.add_argument("roots", nargs="*")
    c = sub.add_parser("check", help="quick disk usage check")
    c.add_argument("--target", type=int)
    c.add_argument("--notify", action="store_true")
    i = sub.add_parser("install-monitor", help="check every 6 hours and notify (macOS)")
    i.add_argument("--target", type=int)
    sub.add_parser("uninstall-monitor")
    a = ap.parse_args()
    if a.cmd in (None, "serve"):
        serve(getattr(a, "port", 8765), not getattr(a, "no_browser", False), getattr(a, "host", "127.0.0.1"))
    elif a.cmd == "report":
        report(a.roots)
    elif a.cmd == "check":
        _, msgs = check(a.target, a.notify)
        sys.exit(1 if msgs else 0)
    elif a.cmd == "install-monitor":
        print(install_monitor(a.target))
    elif a.cmd == "uninstall-monitor":
        print(uninstall_monitor())


if __name__ == "__main__":
    main()
