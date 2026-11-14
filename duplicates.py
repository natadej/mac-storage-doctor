"""Find duplicate files among scan candidates (size -> partial hash -> full hash)."""

import hashlib
import os
from collections import defaultdict

CHUNK = 1024 * 1024
EDGE = 64 * 1024


def _partial(path, size):
    h = hashlib.sha1()
    with open(path, "rb") as f:
        h.update(f.read(EDGE))
        if size > 2 * EDGE:
            f.seek(size // 2)
            h.update(f.read(EDGE))
            f.seek(-EDGE, os.SEEK_END)
            h.update(f.read(EDGE))
    return h.hexdigest()


def _full(path, progress):
    h = hashlib.sha1()
    with open(path, "rb") as f:
        while True:
            if progress.get("cancelled"):
                raise InterruptedError
            b = f.read(CHUNK)
            if not b:
                break
            h.update(b)
            progress["hashed_bytes"] = progress.get("hashed_bytes", 0) + len(b)
    return h.hexdigest()


def find_duplicates(candidates, progress=None, limit=200):
    """candidates: iterable of (path, allocated_size). Returns groups sorted by wasted space."""
    progress = progress if progress is not None else {}
    by_size = defaultdict(list)
    for path, _ in candidates:
        try:
            st = os.stat(path, follow_symlinks=False)
        except OSError:
            continue
        by_size[st.st_size].append((path, st))
    groups = [g for g in by_size.values() if len(g) > 1]
    progress["total_files"] = sum(len(g) for g in groups)
    progress["total_bytes"] = sum(len(g) * g[0][1].st_size for g in groups)
    progress["done_files"] = 0

    results = []
    for g in groups:
        size = g[0][1].st_size
        # drop hard links of the same inode
        uniq = {}
        for p, st in g:
            uniq.setdefault((st.st_dev, st.st_ino), (p, st))
        g = list(uniq.values())
        partial = defaultdict(list)
        for p, st in g:
            try:
                partial[_partial(p, size)].append((p, st))
            except OSError:
                pass
        for same in partial.values():
            if len(same) < 2:
                continue
            full = defaultdict(list)
            for p, st in same:
                progress["current"] = p
                try:
                    full[_full(p, progress)].append((p, st))
                except OSError:
                    pass
            for digest, files in full.items():
                if len(files) < 2:
                    continue
                alloc = max(getattr(st, "st_blocks", 0) * 512 for _, st in files) or size
                files.sort(key=lambda x: x[1].st_mtime)
                results.append({
                    "hash": digest,
                    "size": alloc,
                    "count": len(files),
                    "wasted": alloc * (len(files) - 1),
                    "files": [{"path": p, "mtime": st.st_mtime} for p, st in files],
                })
        progress["done_files"] += len(g)
    results.sort(key=lambda r: -r["wasted"])
    return results[:limit]
