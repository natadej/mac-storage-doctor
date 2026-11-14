"""Disk scanner: walks folders once and produces categories, cleanup candidates,
large files, runaway-generation suspects and recent-growth hotspots."""

import os
import re
import stat
import sys
import time
from collections import Counter, defaultdict

import rules as R

MB = R.MB
GB = R.GB

LARGE_FILE_MIN = 200 * MB
DUP_CANDIDATE_MIN = 20 * MB
TREE_FILE_MIN = 5 * MB
TREE_DIR_MIN = 1 * MB
TREE_MAX_CHILDREN = 80
RECENT_DAYS = 7

RUNAWAY_MIN_FILES = 300          # files directly inside one folder
RUNAWAY_MIN_SHARE = 0.5          # share of files following one name pattern
BURST_MIN_RECENT_FILES = 5000    # new files under one folder in RECENT_DAYS
NEST_REPEAT = 4                  # same folder name repeated N times in a path

_UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", re.I)
_HEX = re.compile(r"(?<![a-z])[0-9a-f]{12,}(?![a-z])", re.I)
_DIGITS = re.compile(r"\d+")


def name_pattern(name):
    """Collapse the variable parts of a file name: 'out_0012.png' -> 'out_#.png'."""
    p = _UUID.sub("{id}", name)
    p = _HEX.sub("{id}", p)
    return _DIGITS.sub("#", p)


def expand(p, home):
    if p == "~":
        return home
    if p.startswith("~/"):
        return os.path.join(home, p[2:])
    return p


class RuleSet:
    def __init__(self, home, rules=None):
        self.home = home
        self.by_path = {}
        self.children = {}
        self.by_dirname = defaultdict(list)
        self.suffix = []
        self.file_suffix = []
        for raw in rules or R.RULES:
            r = dict(raw)
            r.setdefault("stop", not r.get("container", False))
            r.setdefault("container", False)
            r.setdefault("safety", "review")
            r.setdefault("advice", "")
            r.setdefault("command", None)
            r.setdefault("min_size", 50 * MB)
            r.setdefault("trashable", True)
            r.setdefault("label", None)
            kind = r["kind"]
            if kind == "path":
                self.by_path[os.path.normpath(expand(r["match"], home))] = r
            elif kind == "children":
                self.children[os.path.normpath(expand(r["match"], home))] = r
            elif kind == "dirname":
                self.by_dirname[r["match"]].append(r)
            elif kind == "suffix":
                self.suffix.append(r)
            elif kind == "file_suffix":
                self.file_suffix.append(r)
        # folders that contain a more specific path rule further down
        self.path_ancestors = set()
        for p in self.by_path:
            d = os.path.dirname(p)
            while d and d != os.path.dirname(d):
                self.path_ancestors.add(d)
                d = os.path.dirname(d)

    def match_dir(self, path, name, parent):
        r = self.by_path.get(path)
        if r:
            return r
        if path in self.path_ancestors:
            return None
        r = self.children.get(parent)
        if r:
            return r
        for r in self.by_dirname.get(name, ()):
            if "requires" in r and not os.path.exists(os.path.join(path, r["requires"])):
                continue
            if "sibling" in r and not os.path.exists(os.path.join(parent, r["sibling"])):
                continue
            return r
        for r in self.suffix:
            if name.endswith(r["match"]):
                return r
        return None

    def match_file(self, name, parent):
        r = self.children.get(parent)
        if r:
            return r
        low = name.lower()
        for r in self.file_suffix:
            if low.endswith(r["match"]):
                return r
        return None


def file_size(st):
    blocks = getattr(st, "st_blocks", None)
    if blocks is not None:
        return blocks * 512  # allocated size (correct for sparse & iCloud-evicted files)
    return st.st_size


class Scanner:
    def __init__(self, roots, home=None, rules=None, now=None):
        self.home = os.path.normpath(home or os.path.expanduser("~"))
        self.roots = normalize_roots(roots)
        self.rules = RuleSet(self.home, rules)
        self.now = now or time.time()
        self.recent_cutoff = self.now - RECENT_DAYS * 86400
        self.cancelled = False
        # progress
        self.files = 0
        self.dirs = 0
        self.bytes = 0
        self.current = ""
        # results
        self.findings = []
        self.large_files = []
        self.dup_candidates = []
        self.runaway = []
        self.cat_totals = defaultdict(int)
        self.perm_denied = []
        self.errors = 0
        self._seen_inodes = set()
        self.tree = None
        self.index = {}
        self.locked = set()  # folders/files that must be managed in their own app, never trashed

    # ------------------------------------------------------------------
    def run(self):
        root = {"name": "Scanned folders", "path": "", "kind": "dir", "size": 0, "files": 0, "recent": 0,
                "recent_files": 0, "newest": 0, "children": []}
        for r in self.roots:
            if self.cancelled:
                break
            try:
                st = os.lstat(r)
            except OSError:
                continue
            self._dev = st.st_dev
            rule = self.rules.by_path.get(r)
            node, claimed = self._scan_dir(r, os.path.basename(r) or r, os.path.dirname(r), 0,
                                           inside_stop=bool(rule and rule["stop"]),
                                           inside_action=bool(rule and not rule["container"]),
                                           quiet=False, cat=rule["cat"] if rule else "other")
            node["name"] = r
            if rule:
                self._record(rule, node, os.path.basename(r), os.path.dirname(r), claimed)
                claimed = node["size"]
            self.cat_totals["other"] += node["size"] - claimed
            root["children"].append(node)
            for k in ("size", "files", "recent", "recent_files"):
                root[k] += node[k]
            root["newest"] = max(root["newest"], node["newest"])
        root["children"].sort(key=lambda n: -n["size"])
        self.tree = root
        self._build_index(root)
        for f in self.large_files:
            f["locked"] = self.is_locked(f["path"])
        self.findings.sort(key=lambda f: -f["size"])
        self.large_files.sort(key=lambda f: -f["size"])
        self.runaway.sort(key=lambda f: -f["score"])
        return self

    # ------------------------------------------------------------------
    def _record(self, rule, node, name, parent, claimed):
        excl = node["size"] - claimed
        self.cat_totals[rule["cat"]] += excl
        if not rule["trashable"] or rule["safety"] == "careful":
            self.locked.add(node["path"])
        if rule["container"] or node["size"] < rule["min_size"]:
            return
        self.findings.append({
            "path": node["path"],
            "name": name,
            "label": rule["label"] or name,
            "category": rule["cat"],
            "size": node["size"],
            "files": node["files"],
            "newest": node["newest"],
            "recent": node["recent"],
            "safety": rule["safety"],
            "advice": rule["advice"],
            "command": rule["command"],
            "trashable": rule["trashable"],
            "empty_trash": rule.get("empty_trash", False),
            "kind": node["kind"],
        })

    def _scan_dir(self, path, name, parent, depth, inside_stop, inside_action, quiet, cat):
        node = {"name": name, "path": path, "kind": "dir", "size": 0, "files": 0, "recent": 0,
                "recent_files": 0, "newest": 0, "children": [], "cat": cat}
        self.dirs += 1
        if self.dirs % 200 == 0:
            self.current = path
        claimed = 0

        # repeated nesting like a/b/a/b/a/b... (an agent recursing into its own output)
        sep_name = os.sep + name + os.sep
        if (not quiet and name and (path + os.sep).count(sep_name) == NEST_REPEAT
                and not any(path.startswith(x["path"] + os.sep) for x in self.runaway if x["type"] == "nesting")):
            self.runaway.append({"type": "nesting", "path": path, "files": 0, "size": 0, "newest": 0,
                                 "detail": f"Folder name '{name}' repeats {NEST_REPEAT}+ times in this path",
                                 "pattern": name, "sample": "", "recent_files": 0, "score": 50})

        try:
            it = os.scandir(path)
        except PermissionError:
            self.perm_denied.append(path)
            node["denied"] = True
            return node, 0
        except OSError:
            self.errors += 1
            return node, 0

        direct_files = 0
        direct_size = 0
        direct_recent = 0
        patterns = Counter()
        samples = {}
        kids = []
        with it:
            for entry in it:
                if self.cancelled:
                    break
                try:
                    st = entry.stat(follow_symlinks=False)
                except OSError:
                    self.errors += 1
                    continue
                mode = st.st_mode
                if stat.S_ISDIR(mode):
                    if st.st_dev != self._dev:
                        continue  # another volume / mount
                    cpath = entry.path
                    if cpath in self.roots:
                        continue  # scanned separately
                    cname = entry.name
                    rule = None if inside_stop else self.rules.match_dir(cpath, cname, path)
                    child_quiet = (quiet or cname in R.QUIET_NAMES or cname.endswith(R.QUIET_SUFFIXES))
                    child, cclaimed = self._scan_dir(
                        cpath, cname, path, depth + 1,
                        inside_stop=inside_stop or bool(rule and rule["stop"]),
                        inside_action=inside_action or bool(rule and not rule["container"]),
                        quiet=child_quiet,
                        cat=rule["cat"] if rule else cat)
                    if rule:
                        self._record(rule, child, cname, path, cclaimed)
                        claimed += child["size"]
                    else:
                        claimed += cclaimed
                    for k in ("size", "files", "recent", "recent_files"):
                        node[k] += child[k]
                    if child["newest"] > node["newest"]:
                        node["newest"] = child["newest"]
                    kids.append(child)
                elif stat.S_ISREG(mode):
                    if st.st_nlink > 1:
                        key = (st.st_dev, st.st_ino)
                        if key in self._seen_inodes:
                            continue
                        self._seen_inodes.add(key)
                    size = file_size(st)
                    mtime = st.st_mtime
                    fname = entry.name
                    self.files += 1
                    self.bytes += size
                    direct_files += 1
                    direct_size += size
                    node["files"] += 1
                    node["size"] += size
                    if mtime > node["newest"]:
                        node["newest"] = mtime
                    if mtime >= self.recent_cutoff:
                        node["recent"] += size
                        node["recent_files"] += 1
                        direct_recent += 1
                    if not quiet:
                        pat = name_pattern(fname)
                        patterns[pat] += 1
                        if pat not in samples:
                            samples[pat] = fname
                    fpath = entry.path
                    frule = None if inside_stop else self.rules.match_file(fname, path)
                    fcat = cat
                    if frule:
                        fcat = frule["cat"]
                        leaf = {"name": fname, "path": fpath, "kind": "file", "size": size, "files": 1,
                                "newest": mtime, "recent": size if mtime >= self.recent_cutoff else 0}
                        self._record(frule, leaf, fname, path, 0)
                        claimed += size
                    if size >= LARGE_FILE_MIN:
                        self.large_files.append({
                            "path": fpath, "name": fname, "size": size, "mtime": mtime, "category": fcat,
                            "in_finding": inside_action or bool(frule and not frule["container"]),
                        })
                    if size >= DUP_CANDIDATE_MIN and not any(s in fpath for s in R.DUP_SKIP_PARTS):
                        self.dup_candidates.append((fpath, size))
                    if size >= TREE_FILE_MIN:
                        kids.append({"name": fname, "path": fpath, "kind": "file", "size": size, "files": 1,
                                     "newest": mtime, "cat": fcat})

        # runaway: many machine-named files in one folder
        if not quiet and direct_files >= RUNAWAY_MIN_FILES and patterns:
            pat, cnt = patterns.most_common(1)[0]
            share = cnt / direct_files
            generated = "#" in pat or "{id}" in pat
            if generated and share >= RUNAWAY_MIN_SHARE and (direct_recent >= 100 or direct_size >= GB):
                self.runaway.append({
                    "type": "pattern", "path": path, "files": direct_files, "size": direct_size,
                    "pattern": pat, "sample": samples.get(pat, ""), "share": round(share, 2),
                    "recent_files": direct_recent, "newest": node["newest"],
                    "detail": f"{cnt:,} files named like '{pat}'",
                    "score": direct_recent * 2 + direct_files + direct_size / MB,
                })
        # runaway: burst of new files spread over sub-folders
        if not quiet and node["recent_files"] >= BURST_MIN_RECENT_FILES:
            strong = any(k["kind"] == "dir" and k.get("recent_files", 0) >= 0.7 * node["recent_files"] for k in kids)
            if not strong:
                self.runaway.append({
                    "type": "burst", "path": path, "files": node["files"], "size": node["size"],
                    "pattern": "", "sample": "", "recent_files": node["recent_files"], "newest": node["newest"],
                    "detail": f"{node['recent_files']:,} files created/changed in the last {RECENT_DAYS} days",
                    "score": node["recent_files"] * 2,
                })

        node["children"] = prune_children(kids)
        return node, claimed

    def _build_index(self, node):
        if node["kind"] == "dir":
            self.index[node["path"]] = node
            for c in node["children"]:
                self._build_index(c)

    # ------------------------------------------------------------------
    def hotspots(self, min_bytes=500 * MB, limit=25):
        """Folders where the most data was written in the last RECENT_DAYS days."""
        out = []

        def walk(n):
            rec = n.get("recent", 0)
            if rec < min_bytes:
                return
            dirs = [c for c in n["children"] if c["kind"] == "dir"]
            strong = [c for c in dirs if c.get("recent", 0) >= 0.7 * rec]
            if strong or n["path"] == "":
                for c in dirs:
                    walk(c)
            else:
                out.append({"path": n["path"], "recent": rec, "recent_files": n.get("recent_files", 0),
                            "size": n["size"], "newest": n["newest"]})

        walk(self.tree)
        out.sort(key=lambda x: -x["recent"])
        return out[:limit]

    def snapshot(self, min_bytes=50 * MB, max_depth=10):
        snap = {}

        def walk(n, d):
            if n["kind"] != "dir" or n["size"] < min_bytes or d > max_depth:
                return
            if n["path"]:
                snap[n["path"]] = n["size"]
            for c in n["children"]:
                walk(c, d + 1)

        walk(self.tree, 0)
        return snap

    def is_locked(self, path):
        """True if path is (inside) personal data or app-managed storage that must not be trashed."""
        p = path
        while True:
            if p in self.locked:
                return True
            parent = os.path.dirname(p)
            if parent == p:
                return False
            p = parent

    def contains_locked(self, path):
        prefix = path.rstrip(os.sep) + os.sep
        return any(x.startswith(prefix) for x in self.locked)

    def _category_of(self, path, pnode):
        n = self.index.get(path)
        if n and n.get("cat"):
            return n["cat"]
        for c in (pnode or {}).get("children", ()):
            if c["path"] == path and c.get("cat"):
                return c["cat"]
        for f in self.findings + self.large_files:
            if f["path"] == path:
                return f["category"]
        return "other"

    def _add_size(self, start, delta):
        p = start
        while True:
            n = self.index.get(p)
            if n:
                n["size"] = max(0, n["size"] + delta)
            if p in self.roots or p == os.path.dirname(p):
                break
            p = os.path.dirname(p)
        self.tree["size"] = max(0, self.tree["size"] + delta)

    def _in_roots(self, path):
        return any(path == r or path.startswith(r.rstrip(os.sep) + os.sep) for r in self.roots)

    def moved_to_trash(self, size, trash_dir):
        """Account for size bytes that now live in trash_dir (after remove_path)."""
        trash_dir = os.path.normpath(trash_dir)
        if not size or not self._in_roots(trash_dir):
            return
        self.cat_totals["trash"] += size
        self._add_size(trash_dir, size)
        f = next((f for f in self.findings if f["path"] == trash_dir), None)
        if f:
            f["size"] += size
            return
        rule = self.rules.by_path.get(trash_dir)
        if rule:
            self.findings.append({
                "path": trash_dir, "name": os.path.basename(trash_dir), "label": rule["label"] or "Trash",
                "category": rule["cat"], "size": size, "files": 0, "newest": time.time(), "recent": size,
                "safety": rule["safety"], "advice": rule["advice"], "command": rule["command"],
                "trashable": rule["trashable"], "empty_trash": rule.get("empty_trash", False), "kind": "dir"})
            self.findings.sort(key=lambda x: -x["size"])

    def trash_emptied(self, trash_dir):
        trash_dir = os.path.normpath(trash_dir)
        if not self._in_roots(trash_dir):
            return
        n = self.index.get(trash_dir)
        f = next((f for f in self.findings if f["path"] == trash_dir), None)
        size = n["size"] if n else (f["size"] if f else 0)
        if n:
            for k in [k for k in self.index if k.startswith(trash_dir + os.sep)]:
                del self.index[k]
            n["children"] = []
            n["files"] = 0
        self._add_size(trash_dir, -size)
        self.cat_totals["trash"] = max(0, self.cat_totals["trash"] - size)
        self.findings = [x for x in self.findings if x is not f]
        self.large_files = [x for x in self.large_files if not x["path"].startswith(trash_dir + os.sep)]

    def remove_path(self, path, size=None):
        """Update the in-memory tree after a path was moved to the Trash."""
        parent = os.path.dirname(path)
        pnode = self.index.get(parent)
        cat = self._category_of(path, pnode)
        if size is None:
            size = 0
            if pnode:
                for c in pnode["children"]:
                    if c["path"] == path:
                        size = c["size"]
            if not size and path in self.index:
                size = self.index[path]["size"]
        self.cat_totals[cat] = max(0, self.cat_totals[cat] - size)
        if pnode:
            pnode["children"] = [c for c in pnode["children"] if c["path"] != path]
        self._add_size(parent, -size)
        for k in [k for k in self.index if k == path or k.startswith(path + os.sep)]:
            del self.index[k]
        self.findings = [f for f in self.findings if not (f["path"] == path or f["path"].startswith(path + os.sep))]
        self.large_files = [f for f in self.large_files if not (f["path"] == path or f["path"].startswith(path + os.sep))]
        return size


def prune_children(kids):
    kids.sort(key=lambda k: -k["size"])
    keep, rest_size, rest_n = [], 0, 0
    for k in kids:
        big_enough = k["size"] >= (TREE_DIR_MIN if k["kind"] == "dir" else TREE_FILE_MIN)
        if big_enough and len(keep) < TREE_MAX_CHILDREN:
            keep.append(k)
        else:
            rest_size += k["size"]
            rest_n += 1
    if rest_n and rest_size:
        keep.append({"name": f"{rest_n:,} smaller items", "path": None, "kind": "other", "size": rest_size,
                     "files": 0, "newest": 0, "children": []})
    return keep


def normalize_roots(roots):
    clean = []
    for r in roots:
        r = os.path.normpath(os.path.expanduser(r))
        if os.path.isdir(r) and not os.path.islink(r):
            clean.append(r)
    clean = sorted(set(clean), key=len)
    out = []
    for r in clean:
        if not any(r == o or r.startswith(o.rstrip(os.sep) + os.sep) for o in out):
            out.append(r)
    return out


def default_roots(home):
    roots = [home]
    if sys.platform == "darwin":
        roots += ["/Applications", "/Library", "/opt/homebrew", "/usr/local", "/Users/Shared", "/private/var/folders"]
    return [r for r in roots if os.path.isdir(r)]
