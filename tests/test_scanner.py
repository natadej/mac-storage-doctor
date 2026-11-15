import os
import shutil
import sys
import tempfile
import time
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import scanner  # noqa: E402
from duplicates import find_duplicates  # noqa: E402
from scanner import Scanner, name_pattern, normalize_roots  # noqa: E402

MB = 1000 ** 2


def write(path, size=0, age_days=0, data=None):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data if data is not None else os.urandom(size))
    if age_days:
        t = time.time() - age_days * 86400
        os.utime(path, (t, t))


class Fixture(unittest.TestCase):
    def setUp(self):
        self.home = tempfile.mkdtemp()
        self.old = (scanner.LARGE_FILE_MIN, scanner.DUP_CANDIDATE_MIN)
        scanner.LARGE_FILE_MIN = 2 * MB
        scanner.DUP_CANDIDATE_MIN = 1 * MB

    def tearDown(self):
        scanner.LARGE_FILE_MIN, scanner.DUP_CANDIDATE_MIN = self.old
        shutil.rmtree(self.home)

    def scan(self, rules=None):
        return Scanner([self.home], home=self.home, rules=rules).run()


class ScannerTest(Fixture):
    def test_name_pattern(self):
        self.assertEqual(name_pattern("step_00012.png"), "step_#.png")
        self.assertEqual(name_pattern("3f2a9c1e-1b2c-4d5e-8f90-123456789abc.json"), "{id}.json")
        self.assertEqual(name_pattern("readme.md"), "readme.md")

    def test_categories_and_findings(self):
        rules = [
            dict(kind="path", match="~/.ollama/models", cat="ai", label="Ollama models", min_size=0),
            dict(kind="dirname", match="node_modules", cat="dev", label="node_modules", safety="safe", min_size=0),
            dict(kind="children", match="~/Downloads", cat="downloads", min_size=0),
            dict(kind="path", match="~/Downloads", cat="downloads", container=True),
        ]
        write(f"{self.home}/.ollama/models/blob", 3 * MB)
        write(f"{self.home}/proj/node_modules/a/index.js", 1 * MB)
        write(f"{self.home}/proj/node_modules/b/node_modules/c.js", 1 * MB)
        write(f"{self.home}/Downloads/big.zip", 2 * MB)
        write(f"{self.home}/notes.txt", 10)
        sc = self.scan(rules)
        labels = {f["label"]: f for f in sc.findings}
        self.assertIn("Ollama models", labels)
        self.assertIn("big.zip", labels)
        # nested node_modules is not reported twice
        self.assertEqual(sum(1 for f in sc.findings if f["label"] == "node_modules"), 1)
        self.assertGreaterEqual(sc.cat_totals["ai"], 3 * MB)
        self.assertGreaterEqual(sc.cat_totals["dev"], 2 * MB)
        self.assertGreaterEqual(sc.cat_totals["downloads"], 2 * MB)
        # category totals add up to the scanned size
        self.assertEqual(sum(sc.cat_totals.values()), sc.tree["size"])
        # big.zip is inside a finding, so it isn't double counted as a stand-alone large file
        big = [f for f in sc.large_files if f["name"] == "big.zip"][0]
        self.assertTrue(big["in_finding"])

    def test_specific_rule_beats_children_rule(self):
        rules = [
            dict(kind="children", match="~/Library/Application Support", cat="appdata", min_size=0),
            dict(kind="path", match="~/Library/Application Support/MobileSync/Backup", cat="backups",
                 label="Backups", min_size=0),
        ]
        write(f"{self.home}/Library/Application Support/MobileSync/Backup/x/Manifest.db", 1 * MB)
        sc = self.scan(rules)
        self.assertEqual([f["label"] for f in sc.findings], ["Backups"])

    def test_runaway_pattern_and_nesting(self):
        for i in range(scanner.RUNAWAY_MIN_FILES + 20):
            write(f"{self.home}/agent/out/shot_{i:05d}.png", data=b"x")
        p = f"{self.home}/exp"
        for _ in range(5):
            p += "/build/out"
        write(p + "/i.html", data=b"x")
        sc = self.scan([])
        types = {r["type"]: r for r in sc.runaway}
        self.assertIn("pattern", types)
        self.assertEqual(types["pattern"]["pattern"], "shot_#.png")
        self.assertTrue(types["pattern"]["path"].endswith("agent/out"))
        self.assertIn("nesting", types)
        self.assertEqual(sum(1 for r in sc.runaway if r["type"] == "nesting"), 1)

    def test_no_runaway_for_normal_folder(self):
        for i in range(50):
            write(f"{self.home}/photos/IMG_{i}.jpg", data=b"x")
        self.assertEqual(self.scan([]).runaway, [])

    def test_hotspots_recent(self):
        write(f"{self.home}/old/a.bin", 3 * MB, age_days=100)
        write(f"{self.home}/new/deep/b.bin", 3 * MB)
        sc = self.scan([])
        hs = sc.hotspots(min_bytes=1 * MB)
        self.assertEqual(len(hs), 1)
        self.assertTrue(hs[0]["path"].endswith("new/deep"))

    def test_hardlinks_counted_once(self):
        write(f"{self.home}/a.bin", 2 * MB)
        os.link(f"{self.home}/a.bin", f"{self.home}/b.bin")
        sc = self.scan([])
        self.assertLess(sc.tree["size"], 3 * MB)

    def test_remove_path_updates_tree(self):
        write(f"{self.home}/x/y/big.bin", 6 * MB)
        sc = self.scan([])
        before = sc.tree["size"]
        freed = sc.remove_path(f"{self.home}/x/y")
        self.assertGreaterEqual(freed, 6 * MB)
        self.assertEqual(sc.tree["size"], before - freed)
        self.assertNotIn(f"{self.home}/x/y", sc.index)

    def test_duplicates(self):
        data = os.urandom(int(1.5 * MB))
        write(f"{self.home}/a/one.mov", data=data, age_days=10)
        write(f"{self.home}/b/two.mov", data=data)
        write(f"{self.home}/c/other.mov", data=data[:-1] + b"z")
        sc = self.scan([])
        groups = find_duplicates(sc.dup_candidates)
        self.assertEqual(len(groups), 1)
        self.assertEqual(groups[0]["count"], 2)
        self.assertTrue(groups[0]["files"][0]["path"].endswith("one.mov"))  # oldest first

    def test_normalize_roots_drops_nested(self):
        os.makedirs(f"{self.home}/a/b")
        self.assertEqual(normalize_roots([f"{self.home}/a/b", f"{self.home}/a", "/does/not/exist"]), [f"{self.home}/a"])


class ServerSafetyTest(unittest.TestCase):
    def test_protected_paths_rejected(self):
        import storage_doctor as sd
        home = tempfile.mkdtemp()
        try:
            write(f"{home}/junk/file.bin", data=b"x")
            sd.STATE.scanner = Scanner([home], home=home).run()
            self.assertFalse(sd.allowed_target(home))
            self.assertFalse(sd.allowed_target("/etc/passwd"))
            self.assertFalse(sd.allowed_target(f"{home}/../etc"))
            self.assertTrue(sd.allowed_target(f"{home}/junk"))
        finally:
            shutil.rmtree(home)

    def test_serves_dashboard_and_only_web_assets(self):
        import threading
        import urllib.error
        import urllib.request
        from http.server import ThreadingHTTPServer
        import storage_doctor as sd
        sd.Handler.token = "t"
        httpd = ThreadingHTTPServer(("127.0.0.1", 0), sd.Handler)
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        base = f"http://127.0.0.1:{httpd.server_address[1]}"
        try:
            def get(path):
                try:
                    with urllib.request.urlopen(base + path) as r:
                        return r.status, r.read()
                except urllib.error.HTTPError as e:
                    return e.code, b""
            status, body = get("/")
            self.assertEqual(status, 200)
            self.assertIn(b"/static/app.js", body)
            self.assertEqual(get("/static/app.js")[0], 200)
            self.assertEqual(get("/static/style.css")[0], 200)
            self.assertEqual(get("/static/storage_doctor.py")[0], 404)
        finally:
            httpd.shutdown()
            httpd.server_close()



class CleanupSafetyTest(Fixture):
    def test_personal_and_managed_data_is_locked(self):
        photo = os.path.join(self.home, "Pictures", "Photos Library.photoslibrary", "originals", "IMG_0001.heic")
        write(photo, 3 * MB)
        write(os.path.join(self.home, "Movies", "clip.mov"), 3 * MB)
        sc = self.scan()
        self.assertTrue(sc.is_locked(photo))
        self.assertTrue(sc.contains_locked(os.path.join(self.home, "Pictures")))
        self.assertFalse(sc.is_locked(os.path.join(self.home, "Movies", "clip.mov")))
        locked = {f["path"]: f["locked"] for f in sc.large_files}
        self.assertTrue(locked[photo])
        self.assertFalse(locked[os.path.join(self.home, "Movies", "clip.mov")])

    def test_trash_accounting_moves_bytes_between_categories(self):
        os.makedirs(os.path.join(self.home, ".Trash"))
        nm = os.path.join(self.home, "proj", "node_modules")
        write(os.path.join(nm, "pkg", "big.js"), 3 * MB)
        write(os.path.join(nm, "package.json"), 1)
        write(os.path.join(self.home, "proj", "package.json"), 1)
        sc = self.scan()
        dev_before, total_before = sc.cat_totals["dev"], sc.tree["size"]
        self.assertGreater(dev_before, 0)
        size = sc.remove_path(nm)
        trash = os.path.join(self.home, ".Trash")
        sc.moved_to_trash(size, trash)
        self.assertEqual(sc.cat_totals["dev"], dev_before - size)
        self.assertEqual(sc.cat_totals["trash"], size)
        self.assertEqual(sc.tree["size"], total_before)
        self.assertEqual(next(f for f in sc.findings if f["path"] == trash)["size"], size)
        sc.trash_emptied(trash)
        self.assertEqual(sc.cat_totals["trash"], 0)
        self.assertEqual(sc.tree["size"], total_before - size)
        self.assertFalse(any(f["path"] == trash for f in sc.findings))


class GrowthTest(unittest.TestCase):
    def test_growth_of_folder_missing_from_baseline_is_a_lower_bound(self):
        import storage_doctor as sd
        prev = {"time": 0, "folders": {"/h/a": 200 * MB}, "snapshot_min": 50 * MB}
        g = sd.growth_since(prev, {"/h/a": 400 * MB, "/h/runs": 175 * MB})
        by = {f["path"]: f for f in g["folders"]}
        self.assertEqual(by["/h/a"]["growth"], 200 * MB)
        self.assertEqual(by["/h/a"]["was"], 200 * MB)
        self.assertEqual(by["/h/runs"]["growth"], 125 * MB)
        self.assertIsNone(by["/h/runs"]["was"])


class ReviewFixesTest(Fixture):
    def server_scan(self, roots=None, rules=None):
        import storage_doctor as sd
        sd.STATE.scanning = False
        sd.STATE.scanner = Scanner(roots or [self.home], home=self.home, rules=rules).run()
        return sd, sd.STATE.scanner

    def test_symlinked_folders_cannot_reach_outside_or_protected_data(self):
        outside = tempfile.mkdtemp()
        try:
            write(f"{outside}/precious.bin", data=b"x")
            lib = f"{self.home}/Pictures/Photos Library.photoslibrary"
            write(f"{lib}/originals/IMG_1.heic", data=b"x")
            write(f"{self.home}/junk/file.bin", data=b"x")
            os.symlink(outside, f"{self.home}/out")
            os.symlink(lib, f"{self.home}/junk/lib")
            sd, sc = self.server_scan()
            self.assertFalse(sd.allowed_target(f"{self.home}/out/precious.bin"))
            self.assertFalse(sd.allowed_target(f"{self.home}/junk/lib/originals/IMG_1.heic"))
            self.assertFalse(sd.allowed_target(f"{self.home}/junk/lib"))
            self.assertTrue(sd.allowed_target(f"{self.home}/junk/file.bin"))
        finally:
            shutil.rmtree(outside)

    def test_photos_library_in_downloads_is_locked(self):
        write(f"{self.home}/Downloads/Photos Library.photoslibrary/originals/IMG_1.heic", 3 * MB)
        write(f"{self.home}/Downloads/old/Old Photos.photoslibrary/originals/IMG_2.heic", 3 * MB)
        write(f"{self.home}/Downloads/setup.zip", 3 * MB)
        sd, sc = self.server_scan(rules=[dict(r, min_size=0) for r in __import__("rules").RULES])
        self.assertFalse(sd.allowed_target(f"{self.home}/Downloads/Photos Library.photoslibrary"))
        self.assertFalse(sd.allowed_target(f"{self.home}/Downloads/old"))
        self.assertTrue(sd.allowed_target(f"{self.home}/Downloads/setup.zip"))
        by = {f["path"]: f for f in sc.findings}
        self.assertEqual(by[f"{self.home}/Downloads/Photos Library.photoslibrary"]["category"], "media")
        self.assertFalse(by[f"{self.home}/Downloads/old"]["trashable"])

    def test_no_trashing_during_or_after_incomplete_scan(self):
        write(f"{self.home}/junk/file.bin", data=b"x")
        sd, sc = self.server_scan()
        self.assertTrue(sd.allowed_target(f"{self.home}/junk/file.bin"))
        sd.STATE.scanning = True
        self.assertFalse(sd.allowed_target(f"{self.home}/junk/file.bin"))
        sd.STATE.scanning = False
        sc.cancelled = True
        self.assertFalse(sd.allowed_target(f"{self.home}/junk/file.bin"))

    def test_trashing_mixed_folder_subtracts_each_category(self):
        rules = [dict(kind="dirname", match="node_modules", cat="dev", safety="safe", min_size=0)]
        write(f"{self.home}/proj/node_modules/pkg/big.js", 2 * MB)
        write(f"{self.home}/proj/src.bin", 1 * MB)
        write(f"{self.home}/keep.bin", 1 * MB)
        sc = self.scan(rules)
        self.assertEqual(sum(sc.cat_totals.values()), sc.tree["size"])
        sc.remove_path(f"{self.home}/proj")
        self.assertEqual(sc.cat_totals["dev"], 0)
        self.assertEqual(sum(sc.cat_totals.values()), sc.tree["size"])

    def test_careful_libraries_are_not_offered_for_trash(self):
        write(f"{self.home}/Movies/Edit.fcpbundle/media.mov", 3 * MB)
        write(f"{self.home}/Movies/Home.imovielibrary/clip.mov", 3 * MB)
        rules = [dict(r, min_size=0) for r in __import__("rules").RULES]
        sc = self.scan(rules)
        by = {f["label"]: f for f in sc.findings}
        self.assertFalse(by["Final Cut Pro library"]["trashable"])
        self.assertFalse(by["iMovie library"]["trashable"])

    def test_new_scan_cancels_and_ignores_old_duplicate_job(self):
        import storage_doctor as sd
        old = sd.STATE.dup = {"running": True, "results": None, "progress": {}}
        sd.STATE.scanning = False
        self.assertTrue(sd.STATE.start_scan([self.home]))
        self.assertTrue(old["progress"].get("cancelled"))
        self.assertIsNot(sd.STATE.dup, old)
        for _ in range(100):
            if not sd.STATE.scanning:
                break
            time.sleep(0.05)

    def test_custom_root_keeps_its_rule(self):
        rules = [dict(kind="dirname", match="node_modules", cat="dev", safety="safe", min_size=0)]
        write(f"{self.home}/proj/node_modules/pkg/big.js", 2 * MB)
        sc = Scanner([f"{self.home}/proj/node_modules"], home=self.home, rules=rules).run()
        self.assertGreater(sc.cat_totals["dev"], 0)
        self.assertEqual(sc.cat_totals["other"], 0)
        root = next(f for f in sc.findings if f["path"] == f"{self.home}/proj/node_modules")
        self.assertFalse(root["trashable"])

    def test_removing_file_in_pruned_folder_updates_totals(self):
        old = scanner.TREE_MAX_CHILDREN
        scanner.TREE_MAX_CHILDREN = 1
        try:
            write(f"{self.home}/a/big.bin", 4 * MB)
            write(f"{self.home}/b/sub/large.bin", 3 * MB)
            sc = self.scan()
            self.assertNotIn(f"{self.home}/b", sc.index)
            before = sc.tree["size"]
            size = sc.remove_path(f"{self.home}/b/sub/large.bin")
            self.assertGreater(size, 0)
            self.assertEqual(sc.tree["size"], before - size)
            self.assertEqual(sum(sc.cat_totals.values()), sc.tree["size"])
        finally:
            scanner.TREE_MAX_CHILDREN = old

    def test_plain_move_reports_no_put_back(self):
        import storage_doctor as sd
        write(f"{self.home}/junk/file.bin", data=b"x")
        os.environ["STORAGE_DOCTOR_TRASH"] = f"{self.home}/.Trash"
        try:
            if not sd.IS_MAC:
                self.assertFalse(sd.move_to_trash(f"{self.home}/junk/file.bin"))
                self.assertTrue(os.path.exists(f"{self.home}/.Trash/file.bin"))
        finally:
            del os.environ["STORAGE_DOCTOR_TRASH"]


    def test_refresh_results_during_new_scan_does_not_crash(self):
        write(f"{self.home}/junk/file.bin", data=b"x")
        sd, sc = self.server_scan()
        sd.STATE.results = {"scanned": 1}
        sd.STATE.scanner = Scanner([self.home], home=self.home)
        sd.STATE.scanning = True
        try:
            self.assertIsNone(sd.refresh_results())
            self.assertEqual(sd.trash_denial(f"{self.home}/junk/file.bin"), "wait for the scan to finish")
        finally:
            sd.STATE.scanning = False
            sd.STATE.results = None

    def test_cancelled_duplicate_job_stops(self):
        import duplicates
        for i in range(2):
            write(f"{self.home}/a{i}.bin", data=b"A" * 5000)
            write(f"{self.home}/b{i}.bin", data=b"B" * 6000)
        cands = [(f"{self.home}/{c}{i}.bin", 0) for c in "ab" for i in range(2)]
        self.assertEqual(len(find_duplicates(cands)), 2)
        with self.assertRaises(InterruptedError):
            find_duplicates(cands, {"cancelled": True})
        progress, real = {}, duplicates._full

        def cancel_mid_read(path, prog):
            prog["cancelled"] = True
            return real(path, prog)
        duplicates._full = cancel_mid_read
        try:
            with self.assertRaises(InterruptedError):
                find_duplicates(cands, progress)
        finally:
            duplicates._full = real


if __name__ == "__main__":
    unittest.main()
