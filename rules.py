"""Classification rules: which folders/files belong to which storage category.

Paths starting with "~/" are relative to the user's home folder.
"""

CATEGORIES = [
    # id, label, color
    ("ai", "AI agents & models", "#a855f7"),
    ("dev", "Developer files", "#3b82f6"),
    ("vms", "Virtual machines & containers", "#06b6d4"),
    ("media", "Photos & media", "#f59e0b"),
    ("backups", "iPhone/iPad backups", "#ef4444"),
    ("messages", "Messages & Mail", "#ec4899"),
    ("docs", "Documents & Desktop", "#84cc16"),
    ("downloads", "Downloads & installers", "#f97316"),
    ("cache", "Caches & logs", "#14b8a6"),
    ("appdata", "App data", "#8b5cf6"),
    ("apps", "Applications", "#6366f1"),
    ("cloud", "Cloud sync folders", "#0ea5e9"),
    ("trash", "Trash", "#78716c"),
    ("other", "Other files", "#94a3b8"),
    ("system", "macOS system & not scanned", "#475569"),
]

MB = 1000 ** 2
GB = 1000 ** 3

# safety: "safe"    -> regenerates automatically / pure cache
#         "review"  -> can be re-downloaded or is probably unneeded; check first
#         "careful" -> personal data; manage it inside the owning app
#
# kind:  "path"     -> exact folder (match = path)
#        "children" -> every direct child (folder or file) of match
#        "dirname"  -> any folder with this name (optional "requires" file inside,
#                      or "sibling" file next to it)
#        "suffix"   -> any folder whose name ends with match
#        "file_suffix" -> any file whose name ends with match
#
# container: True -> only used for category totals; never suggested as a single
#            cleanup item (things inside it can still be suggested).
# stop: False -> keep looking for more specific rules inside (default True).

RULES = [
    # ---------------- AI agents & local models ----------------
    dict(kind="path", match="~/.ollama/models", cat="ai", label="Ollama models", safety="review",
         advice="Local LLM weights. Remove models you no longer use.", command="ollama list && ollama rm <model>"),
    dict(kind="path", match="~/.cache/huggingface", cat="ai", label="Hugging Face model cache", safety="review",
         advice="Downloaded models/datasets. They re-download on demand.", command="huggingface-cli delete-cache"),
    dict(kind="path", match="~/.cache/lm-studio", cat="ai", label="LM Studio models", safety="review",
         advice="Local LLM weights. Delete unused models inside LM Studio."),
    dict(kind="path", match="~/.lmstudio", cat="ai", label="LM Studio models", safety="review",
         advice="Local LLM weights. Delete unused models inside LM Studio."),
    dict(kind="path", match="~/.cache/torch", cat="ai", label="PyTorch hub cache", safety="safe",
         advice="Cached model weights; re-downloaded when needed."),
    dict(kind="path", match="~/.cache/whisper", cat="ai", label="Whisper models", safety="review",
         advice="Speech-to-text model weights; re-downloaded when needed."),
    dict(kind="path", match="~/.cache/chroma", cat="ai", label="Chroma embeddings cache", safety="safe",
         advice="Cached embedding models."),
    dict(kind="path", match="~/.claude", cat="ai", label="Claude Code data", safety="review",
         advice="Session transcripts, todos, shell snapshots. Old files in projects/ can be removed."),
    dict(kind="path", match="~/Library/Application Support/Claude", cat="ai", label="Claude desktop app data", safety="review",
         advice="Includes VM bundles and caches used by the Claude app."),
    dict(kind="path", match="~/.codex", cat="ai", label="OpenAI Codex CLI data", safety="review",
         advice="Session logs and caches. Old sessions/ can be removed."),
    dict(kind="path", match="~/.gemini", cat="ai", label="Gemini CLI data", safety="review",
         advice="Session history and caches."),
    dict(kind="path", match="~/.cursor", cat="ai", label="Cursor extensions & data", safety="review",
         advice="Extensions and agent data for Cursor."),
    dict(kind="path", match="~/Library/Application Support/Cursor", cat="ai", label="Cursor app data", safety="review",
         advice="Workspace storage, history and caches. workspaceStorage/ often grows large."),
    dict(kind="path", match="~/.windsurf", cat="ai", label="Windsurf data", safety="review", advice="Editor/agent data."),
    dict(kind="path", match="~/Library/Application Support/Windsurf", cat="ai", label="Windsurf app data", safety="review",
         advice="Workspace storage, history and caches."),
    dict(kind="path", match="~/.codeium", cat="ai", label="Codeium data", safety="review", advice="Agent data and caches."),
    dict(kind="path", match="~/.continue", cat="ai", label="Continue.dev data", safety="review", advice="Agent data and indexes."),
    dict(kind="path", match="~/.local/share/opencode", cat="ai", label="opencode data", safety="review", advice="Agent sessions and logs."),
    dict(kind="path", match="~/Library/Application Support/Code", cat="ai", label="VS Code app data", safety="review",
         advice="Workspace storage, extension caches, Copilot data. workspaceStorage/ and CachedData/ grow large."),
    dict(kind="path", match="~/.vscode/extensions", cat="ai", label="VS Code extensions", safety="review",
         advice="Old extension versions pile up here."),
    dict(kind="path", match="~/Library/Caches/ms-playwright", cat="ai", label="Playwright browsers (agent browsing)", safety="safe",
         advice="Browser binaries downloaded by agents/tests.", command="npx playwright uninstall --all"),
    dict(kind="path", match="~/.cache/ms-playwright", cat="ai", label="Playwright browsers (agent browsing)", safety="safe",
         advice="Browser binaries downloaded by agents/tests.", command="npx playwright uninstall --all"),
    dict(kind="path", match="~/.cache/puppeteer", cat="ai", label="Puppeteer browsers", safety="safe",
         advice="Browser binaries downloaded by agents/tests."),
    dict(kind="dirname", match=".aider.tags.cache.v3", cat="ai", label="Aider tags cache", safety="safe", advice="Regenerated by aider."),

    # ---------------- Developer files ----------------
    dict(kind="dirname", match="node_modules", cat="dev", label="node_modules", safety="safe",
         advice="Reinstall with npm/pnpm/yarn install when you work on the project again."),
    dict(kind="dirname", match=".venv", cat="dev", label="Python virtualenv", safety="safe",
         advice="Recreate with python -m venv + pip install when needed."),
    dict(kind="dirname", match="venv", requires="pyvenv.cfg", cat="dev", label="Python virtualenv", safety="safe",
         advice="Recreate with python -m venv + pip install when needed."),
    dict(kind="dirname", match="env", requires="pyvenv.cfg", cat="dev", label="Python virtualenv", safety="safe",
         advice="Recreate with python -m venv + pip install when needed."),
    dict(kind="dirname", match="__pycache__", cat="dev", label="Python bytecode cache", safety="safe", advice="Regenerated automatically."),
    dict(kind="dirname", match=".next", cat="dev", label="Next.js build cache", safety="safe", advice="Rebuilt on next build."),
    dict(kind="dirname", match=".nuxt", cat="dev", label="Nuxt build cache", safety="safe", advice="Rebuilt on next build."),
    dict(kind="dirname", match=".turbo", cat="dev", label="Turborepo cache", safety="safe", advice="Rebuilt on next build."),
    dict(kind="dirname", match=".parcel-cache", cat="dev", label="Parcel cache", safety="safe", advice="Rebuilt on next build."),
    dict(kind="dirname", match=".pytest_cache", cat="dev", label="pytest cache", safety="safe", advice="Regenerated automatically."),
    dict(kind="dirname", match=".mypy_cache", cat="dev", label="mypy cache", safety="safe", advice="Regenerated automatically."),
    dict(kind="dirname", match=".ruff_cache", cat="dev", label="ruff cache", safety="safe", advice="Regenerated automatically."),
    dict(kind="dirname", match=".tox", cat="dev", label="tox environments", safety="safe", advice="Recreated by tox."),
    dict(kind="dirname", match="target", sibling="Cargo.toml", cat="dev", label="Rust build output", safety="safe",
         advice="Rebuilt by cargo.", command="cargo clean"),
    dict(kind="dirname", match="DerivedData", cat="dev", label="Xcode DerivedData", safety="safe", advice="Rebuilt by Xcode."),
    dict(kind="path", match="~/Library/Developer/Xcode/Archives", cat="dev", label="Xcode archives", safety="review",
         advice="Old app builds. Keep the ones you may need for crash symbolication."),
    dict(kind="path", match="~/Library/Developer/Xcode/iOS DeviceSupport", cat="dev", label="iOS DeviceSupport", safety="safe",
         advice="Debug symbols per iOS version; re-created when a device connects."),
    dict(kind="path", match="~/Library/Developer/Xcode/watchOS DeviceSupport", cat="dev", label="watchOS DeviceSupport", safety="safe",
         advice="Re-created when a device connects."),
    dict(kind="path", match="~/Library/Developer/CoreSimulator", cat="dev", label="iOS Simulators", safety="review",
         advice="Simulator devices and runtimes.", command="xcrun simctl delete unavailable"),
    dict(kind="path", match="~/Library/Caches/Homebrew", cat="dev", label="Homebrew download cache", safety="safe",
         advice="Old package downloads.", command="brew cleanup --prune=all"),
    dict(kind="path", match="~/.cache/pip", cat="dev", label="pip cache", safety="safe", advice="Package download cache.", command="pip cache purge"),
    dict(kind="path", match="~/Library/Caches/pip", cat="dev", label="pip cache", safety="safe", advice="Package download cache.", command="pip cache purge"),
    dict(kind="path", match="~/.cache/uv", cat="dev", label="uv cache", safety="safe", advice="Package cache.", command="uv cache clean"),
    dict(kind="path", match="~/.npm", cat="dev", label="npm cache", safety="safe", advice="Package download cache.", command="npm cache clean --force"),
    dict(kind="path", match="~/Library/pnpm", cat="dev", label="pnpm store", safety="safe", advice="Shared package store.", command="pnpm store prune"),
    dict(kind="path", match="~/.pnpm-store", cat="dev", label="pnpm store", safety="safe", advice="Shared package store.", command="pnpm store prune"),
    dict(kind="path", match="~/.local/share/pnpm", cat="dev", label="pnpm store", safety="safe", advice="Shared package store.", command="pnpm store prune"),
    dict(kind="path", match="~/Library/Caches/Yarn", cat="dev", label="Yarn cache", safety="safe", advice="Package cache.", command="yarn cache clean"),
    dict(kind="path", match="~/.yarn/berry/cache", cat="dev", label="Yarn cache", safety="safe", advice="Package cache.", command="yarn cache clean"),
    dict(kind="path", match="~/.bun/install/cache", cat="dev", label="Bun cache", safety="safe", advice="Package cache.", command="bun pm cache rm"),
    dict(kind="path", match="~/.cargo/registry", cat="dev", label="Cargo registry cache", safety="safe", advice="Crate downloads; re-downloaded when needed."),
    dict(kind="path", match="~/go/pkg/mod", cat="dev", label="Go module cache", safety="safe", advice="Module downloads.", command="go clean -modcache"),
    dict(kind="path", match="~/.gradle/caches", cat="dev", label="Gradle caches", safety="safe", advice="Build caches."),
    dict(kind="path", match="~/.m2/repository", cat="dev", label="Maven repository", safety="review", advice="Downloaded Java dependencies."),
    dict(kind="path", match="~/.conda/pkgs", cat="dev", label="Conda package cache", safety="safe", advice="Package cache.", command="conda clean --all"),
    dict(kind="path", match="~/miniconda3/pkgs", cat="dev", label="Conda package cache", safety="safe", advice="Package cache.", command="conda clean --all"),
    dict(kind="path", match="~/anaconda3/pkgs", cat="dev", label="Conda package cache", safety="safe", advice="Package cache.", command="conda clean --all"),
    dict(kind="path", match="~/.rustup/toolchains", cat="dev", label="Rust toolchains", safety="review", advice="Old toolchains.", command="rustup toolchain list"),
    dict(kind="path", match="~/.pyenv/versions", cat="dev", label="pyenv Python versions", safety="review", advice="Old Python versions.", command="pyenv versions"),
    dict(kind="path", match="~/.nvm/versions", cat="dev", label="nvm Node versions", safety="review", advice="Old Node versions.", command="nvm ls"),
    dict(kind="path", match="~/Library/Android/sdk", cat="dev", label="Android SDK", safety="review", advice="Old system images and build tools."),
    dict(kind="path", match="~/.android/avd", cat="dev", label="Android emulators", safety="review", advice="Emulator disk images."),
    dict(kind="path", match="~/Library/Caches/JetBrains", cat="dev", label="JetBrains caches", safety="safe", advice="IDE caches."),
    dict(kind="path", match="~/Library/Caches/com.apple.dt.Xcode", cat="dev", label="Xcode cache", safety="safe", advice="Regenerated by Xcode."),

    # ---------------- VMs & containers ----------------
    dict(kind="path", match="~/Library/Containers/com.docker.docker", cat="vms", label="Docker Desktop disk image", safety="review",
         trashable=False, advice="Images, containers, volumes and build cache. Clean up with the command or Docker Desktop > Troubleshoot > Clean / Purge data.", command="docker system prune -a --volumes"),
    dict(kind="path", match="~/.orbstack", cat="vms", label="OrbStack data", safety="review", advice="Containers and images.", command="docker system prune -a"),
    dict(kind="path", match="~/Library/Group Containers/HUAQ24HBR6.dev.orbstack", cat="vms", label="OrbStack data", safety="review",
         advice="Containers and images.", command="docker system prune -a"),
    dict(kind="path", match="~/.colima", cat="vms", label="Colima VM", safety="review", advice="Container VM disk.", command="docker system prune -a"),
    dict(kind="path", match="~/.lima", cat="vms", label="Lima VMs", safety="review", advice="VM disks.", command="limactl list"),
    dict(kind="path", match="~/Library/Containers/com.utmapp.UTM", cat="vms", label="UTM virtual machines", safety="review", advice="VM disks."),
    dict(kind="suffix", match=".vmwarevm", cat="vms", label="VMware virtual machine", safety="review", advice="Whole VM disk."),
    dict(kind="suffix", match=".pvm", cat="vms", label="Parallels virtual machine", safety="review", advice="Whole VM disk."),
    dict(kind="suffix", match=".utm", cat="vms", label="UTM virtual machine", safety="review", advice="Whole VM disk."),

    # ---------------- Photos & media ----------------
    dict(kind="suffix", match=".photoslibrary", cat="media", label="Photos library", safety="careful", trashable=False,
         advice="Don't delete this file. In Photos: Settings > iCloud > 'Optimize Mac Storage', delete unwanted photos/videos, then empty 'Recently Deleted'."),
    dict(kind="suffix", match=".fcpbundle", cat="media", label="Final Cut Pro library", safety="careful",
         advice="In Final Cut: File > Delete Generated Library Files."),
    dict(kind="suffix", match=".imovielibrary", cat="media", label="iMovie library", safety="careful", advice="Remove old projects inside iMovie."),
    dict(kind="path", match="~/Pictures", cat="media", container=True, stop=False),
    dict(kind="path", match="~/Movies", cat="media", container=True, stop=False),
    dict(kind="path", match="~/Music", cat="media", container=True, stop=False),
    dict(kind="path", match="~/Library/Application Support/GarageBand", cat="media", label="GarageBand sound library", safety="review",
         advice="Remove via GarageBand > Sound Library settings."),
    dict(kind="path", match="/Library/Application Support/GarageBand", cat="media", label="GarageBand sound library", safety="review",
         advice="Remove via GarageBand > Sound Library settings."),
    dict(kind="path", match="/Library/Application Support/Logic", cat="media", label="Logic sound library", safety="review",
         advice="Remove via Logic > Sound Library settings."),

    # ---------------- Device backups ----------------
    dict(kind="path", match="~/Library/Application Support/MobileSync/Backup", cat="backups", label="iPhone/iPad backups", safety="careful",
         trashable=False, advice="Delete old backups in Finder: select your iPhone > Manage Backups."),

    # ---------------- Messages & Mail ----------------
    dict(kind="path", match="~/Library/Messages", cat="messages", label="Messages attachments", safety="careful", trashable=False,
         advice="System Settings > General > Storage > Messages: review large attachments; or keep messages for 1 year."),
    dict(kind="path", match="~/Library/Mail", cat="messages", label="Mail data", safety="careful", trashable=False,
         advice="Delete large emails / attachments inside Mail; avoid deleting this folder."),
    dict(kind="path", match="~/Library/Containers/com.apple.mail", cat="messages", label="Mail downloads", safety="careful", trashable=False,
         advice="Mail attachment cache."),

    # ---------------- Documents ----------------
    dict(kind="path", match="~/Documents", cat="docs", container=True, stop=False),
    dict(kind="path", match="~/Desktop", cat="docs", container=True, stop=False),

    # ---------------- Downloads & installers ----------------
    dict(kind="children", match="~/Downloads", cat="downloads", label=None, safety="review", min_size=50 * MB,
         advice="Downloaded file. Delete it if you no longer need it."),
    dict(kind="path", match="~/Downloads", cat="downloads", container=True, stop=False),
    dict(kind="file_suffix", match=(".dmg", ".pkg", ".iso", ".ipsw", ".xip", ".mpkg"), cat="downloads", label=None, safety="review",
         min_size=100 * MB, advice="Installer / disk image. Usually not needed after installing."),
    dict(kind="path", match="/Library/Updates", cat="downloads", label="macOS update downloads", safety="review",
         trashable=False, advice="Managed by macOS. Finish or cancel the pending update in Software Update."),

    # ---------------- Caches & logs ----------------
    dict(kind="children", match="~/Library/Caches", cat="cache", label=None, safety="safe", min_size=100 * MB,
         advice="App cache. Quit the app first; it rebuilds what it needs."),
    dict(kind="children", match="~/.cache", cat="cache", label=None, safety="safe", min_size=100 * MB,
         advice="Tool cache. Usually rebuilt on demand."),
    dict(kind="path", match="~/Library/Logs", cat="cache", label="User logs", safety="safe", advice="Diagnostic logs."),
    dict(kind="path", match="/Library/Caches", cat="cache", container=True),
    dict(kind="path", match="/Library/Logs", cat="cache", container=True),
    dict(kind="path", match="/private/var/folders", cat="cache", label="macOS temporary files", safety="review", trashable=False,
         advice="Per-app temp files. A restart lets macOS clean most of this."),

    # ---------------- App data ----------------
    dict(kind="children", match="~/Library/Application Support", cat="appdata", label=None, safety="review", min_size=500 * MB,
         advice="App data. Remove it only for apps you've deleted, or clean up from inside the app."),
    dict(kind="children", match="~/Library/Containers", cat="appdata", label=None, safety="review", min_size=500 * MB,
         advice="Sandboxed app data. Remove it only for apps you've deleted, or clean up from inside the app."),
    dict(kind="children", match="~/Library/Group Containers", cat="appdata", label=None, safety="review", min_size=500 * MB,
         advice="Shared app data. Remove it only for apps you've deleted, or clean up from inside the app."),

    # ---------------- Applications ----------------
    dict(kind="children", match="/Applications", cat="apps", label=None, safety="review", min_size=300 * MB,
         advice="Application. Uninstall it if you don't use it."),
    dict(kind="children", match="~/Applications", cat="apps", label=None, safety="review", min_size=300 * MB,
         advice="Application. Uninstall it if you don't use it."),

    # ---------------- Cloud ----------------
    dict(kind="path", match="~/Library/CloudStorage", cat="cloud", label="Cloud drives (Dropbox, Google Drive, OneDrive...)", safety="careful",
         trashable=False, advice="Use each app's 'online-only' / 'free up space' option instead of deleting."),
    dict(kind="path", match="~/Library/Mobile Documents", cat="cloud", label="iCloud Drive (local copies)", safety="careful", trashable=False,
         advice="Turn on 'Optimize Mac Storage' for iCloud Drive, or right-click files > Remove Download."),
    dict(kind="path", match="~/Dropbox", cat="cloud", label="Dropbox", safety="careful", trashable=False,
         advice="Make large folders online-only in Dropbox."),

    # ---------------- Trash ----------------
    dict(kind="path", match="~/.Trash", cat="trash", label="Trash", safety="safe", trashable=False, empty_trash=True,
         advice="Space is only freed after you empty the Trash."),
]

# Folders where lots of small machine-named files are normal; skip "runaway file" detection there.
QUIET_NAMES = {".git", "node_modules", "site-packages", "__pycache__", ".venv", "venv", "Caches", ".cache",
               "DerivedData", "CoreSimulator", ".Trash", "Mail", "Messages", "Metadata", ".npm", ".pnpm-store",
               "pnpm", ".gradle", ".m2", ".cargo", "pkgs", "Spotlight-V100", ".Spotlight-V100", ".fseventsd"}
QUIET_SUFFIXES = (".photoslibrary", ".app", ".fcpbundle", ".imovielibrary", ".xcarchive", ".bundle", ".framework",
                  ".vmwarevm", ".pvm", ".utm", ".sparsebundle", ".musiclibrary", ".tvlibrary")

# Large/duplicate file checks skip these (internal app files, not user files).
DUP_SKIP_PARTS = (".photoslibrary", ".app/", "/node_modules/", "/.git/", "com.docker.docker", ".vmwarevm", ".pvm/", ".utm/",
                  "/.Trash/", "/.ollama/", "/huggingface/")
