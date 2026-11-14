"""Create a fake Mac-like home folder with realistic clutter for manual testing.

Usage: python3 tests/make_fake_home.py /tmp/fakehome [--scale 1.0]
Files are allocated with real blocks (fallocate/zero-fill), so they use disk space.
"""
import os
import subprocess
import sys
import time

MB = 1000 ** 2


def alloc(path, size, age_days=0):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if subprocess.run(["fallocate", "-l", str(size), path], capture_output=True).returncode != 0:
        with open(path, "wb") as f:
            chunk = b"\0" * MB
            for _ in range(size // MB):
                f.write(chunk)
    if age_days:
        t = time.time() - age_days * 86400
        os.utime(path, (t, t))


def small(path, text="x", age_days=0):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)
    if age_days:
        t = time.time() - age_days * 86400
        os.utime(path, (t, t))


def main(home, scale=1.0):
    s = lambda mb: int(mb * scale * MB)  # noqa: E731
    alloc(f"{home}/.ollama/models/blobs/sha256-7462734796d67c40ecec2ca98eddf970e171dbb6b370e43fd633ee75b69abe1b", s(4200), 40)
    alloc(f"{home}/.ollama/models/blobs/sha256-0a1b2c3d4e5f60718293a4b5c6d7e8f9", s(2000), 3)
    alloc(f"{home}/.cache/huggingface/hub/models--meta-llama--Llama-3.2-3B/blobs/a1", s(2500), 20)
    alloc(f"{home}/Library/Application Support/Cursor/User/workspaceStorage/abc/state.vscdb", s(700), 1)
    for i in range(50):
        small(f"{home}/.claude/projects/-Users-me-proj/{i:04d}-session.jsonl", "{}" * 2000)
    alloc(f"{home}/.claude/projects/-Users-me-proj/big-session.jsonl", s(150), 2)
    alloc(f"{home}/Library/Caches/ms-playwright/chromium-1140/chrome", s(450), 10)
    # dev
    for i in range(30):
        alloc(f"{home}/Projects/webapp/node_modules/pkg{i}/index.js", s(12), 60)
    alloc(f"{home}/Projects/webapp/node_modules/@next/swc/next-swc.node", s(120), 60)
    small(f"{home}/Projects/ml/.venv/pyvenv.cfg", "home = /usr/bin")
    alloc(f"{home}/Projects/ml/.venv/lib/python3.12/site-packages/torch/lib/libtorch.dylib", s(900), 90)
    alloc(f"{home}/Library/Developer/Xcode/DerivedData/MyApp-abc/Build/x", s(1500), 15)
    alloc(f"{home}/Library/Caches/Homebrew/downloads/ffmpeg.tar.gz", s(300), 100)
    alloc(f"{home}/Library/Containers/com.docker.docker/Data/vms/0/data/Docker.raw", s(6000), 1)
    # media
    alloc(f"{home}/Pictures/Photos Library.photoslibrary/originals/A/IMG_0001.heic", s(3500), 200)
    alloc(f"{home}/Movies/vacation-2023.mov", s(1800), 500)
    alloc(f"{home}/Downloads/vacation-2023 (1).mov", s(1800), 30)   # duplicate of the Movies file (both zeros)
    alloc(f"{home}/Downloads/Xcode_16.xip", s(3000), 120)
    alloc(f"{home}/Downloads/ubuntu-24.04.iso", s(2500), 400)
    for i in range(5):
        small(f"{home}/Downloads/receipt-{i}.pdf", "pdf")
    alloc(f"{home}/Library/Application Support/MobileSync/Backup/00008110-001/Manifest.db", s(2200), 300)
    alloc(f"{home}/Library/Caches/com.spotify.client/Data/cache.bin", s(800), 2)
    alloc(f"{home}/.Trash/old-project.zip", s(900), 5)
    small(f"{home}/Documents/notes.txt", "hello")
    alloc(f"{home}/Documents/Archive/presentation-final.key", s(400), 700)
    # runaway: an agent writing thousands of screenshots in a loop
    for i in range(2500):
        small(f"{home}/Projects/agent-bot/output/screenshots/step_{i:05d}.png", "p" * 4000)
    # runaway: burst of new files across many folders
    for i in range(600):
        for j in range(10):
            small(f"{home}/Projects/agent-bot/runs/run-{i:04d}/artifact_{j}.json", "{}" * 100)
    # runaway: folder nested inside itself
    p = f"{home}/Projects/exporter/build"
    for _ in range(6):
        p += "/out/build"
    small(p + "/index.html", "<html>")


if __name__ == "__main__":
    home = sys.argv[1]
    scale = float(sys.argv[3]) if len(sys.argv) > 3 and sys.argv[2] == "--scale" else 1.0
    main(home, scale)
    print("created", home)
