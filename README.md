# Storage Doctor (Mac and Android)

Shows **what is filling your storage** and helps you keep it at a target (default **50 %** used).

| Platform | What it is | Docs |
|---|---|---|
| **Mac** | A small local web app (Python 3, no installs): `storage_doctor.py`, `index.html`, `app.js`, `style.css` | this file |
| **Android** | A native app that scans the phone's own storage | [android/README.md](android/README.md) |

The rest of this file is about the Mac app.

It answers questions like:

- Is it **AI agent files** (Ollama / Hugging Face / LM Studio models, Claude Code, Codex, Cursor, VS Code workspace data, Playwright browsers)?
- Is it **photos and videos**, iPhone backups, Messages attachments?
- Is it a few **very large files** (disk images, installers, old videos), or **duplicates**?
- Is an agent or script **generating files in a loop**? It looks for folders filling up with machine-named files (`step_00001.png`, `run-0001/…`), sudden bursts of new files, and folders nested inside themselves (`build/out/build/out/…`).
- **What grew since last time?** Every scan is saved, so the next scan shows exactly which folders grew.

Everything runs on your Mac. Nothing is uploaded, and nothing is deleted unless you click **Trash** yourself. Items go to the macOS Trash, so you can still use *Put Back*.

## Run it

You need `python3`. It's already installed if you use git or Homebrew. If not, run `xcode-select --install`. There are no packages to install.

```bash
git clone https://github.com/natadej/mac-storage-doctor.git
cd mac-storage-doctor
python3 storage_doctor.py          # opens http://127.0.0.1:8765 in your browser
```

Or double-click `start.command` in Finder. The first time, you may have to right-click it and choose *Open*.

**For a complete scan**, give your terminal app Full Disk Access: *System Settings → Privacy & Security → Full Disk Access → turn on Terminal* (or iTerm/Warp). Then restart the app. Without it, macOS hides Mail, Messages, Safari and some app data.

## What you get

| Tab | What it shows |
| --- | --- |
| **Overview** | Disk gauge with your target, how many GB to free, a breakdown by category, and quick answers |
| **Cleanup plan** | Ranked items marked `safe` / `review` / `careful`, with a running total and the projected disk % after cleanup. *Select recommended* picks safe items until your target is reached |
| **AI & runaway files** | Runaway-generation suspects, folders with the most data written in the last 7 days, growth since the last scan, and AI agent/model folders |
| **Large files** | Files of 200 MB or more, filterable by size, age and category |
| **Duplicates** | Identical files of 20 MB or more (compared by content hash) |
| **Explore** | Drill down into any folder, sorted by size |

Notes:
- Moving to the Trash doesn't free space until you click **Empty Trash**.
- "macOS system & not scanned" covers the OS itself, Time Machine local snapshots, swap, other users and unreadable folders.
- Things like the Photos library, iPhone backups, Messages, iCloud Drive and Docker can't be trashed from the app. You get the right way to clean them up (for example Photos → *Optimize Mac Storage*, `docker system prune`).

## Keep it at 50 % automatically

```bash
python3 storage_doctor.py install-monitor     # checks every 6 hours
python3 storage_doctor.py uninstall-monitor
```

The monitor (a launchd agent) sends a macOS notification when the disk goes over your target, or when usage grows by 10 GB or more in about a day, which often means something is writing files in a loop.

## Other commands

```bash
python3 storage_doctor.py report              # scan and print a summary in the terminal
python3 storage_doctor.py check               # one-line disk check (exit code 1 if over target)
python3 storage_doctor.py serve --port 9000 --no-browser
```

Settings and scan history are stored in `~/.storage-doctor/`.

## Development

```bash
python3 -m unittest discover -s tests -v
python3 tests/make_fake_home.py /tmp/fakehome          # Mac-like test folder (~34 GB, Linux fallocate)
HOME=/tmp/fakehome python3 storage_doctor.py
```

Classification rules live in `rules.py`. Add a line there to teach the app about a new tool's folders.
