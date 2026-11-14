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


class ScannerTest(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
