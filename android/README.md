# Storage Doctor for Android

A native Android app (Kotlin, Jetpack Compose) that does for your phone what the Mac app does for
your Mac: shows what is filling storage, flags files something keeps generating, and builds a cleanup
plan to get you back under a target (50% used by default). Everything runs on the phone; nothing is
uploaded.

## What it shows

- **Overview**: how full the phone is, how much to free to reach your target, and a breakdown:
  photos & videos, chat app media (WhatsApp, Telegram, LINE, Signal…), downloads & installers,
  AI model files (`.gguf`, `.tflite`, `.safetensors`…), documents, music, caches, trash, apps, and
  "Android system & not scanned".
- **AI & runaway**: folders where something keeps writing files (hundreds of `frame_00001.png`-style
  names, thousands of new files in a week, folders nested inside themselves), what grew since the
  last scan, the busiest folders this week, and AI model files.
- **Cleanup**: ranked safe / review / careful items. "Select recommended" picks safe items (caches,
  installers, leftovers) until the target is reached. "Empty trash" frees space held by the system trash.
- **Open** on any file in Large files, Duplicates or the Cleanup plan shows it in the phone's viewer
  (Gallery, video player, PDF app) so you can check it before deleting.
- **Large files** (over 100 MB), **Duplicates** (same content, compared by hash), **Apps** (sizes of
  packages Android makes visible to the app, optional Usage access), **Explore** (folder tree by size).
- **Check every 6 hours** (optional): a notification when storage is over target or grew 5 GB or more.

## Install

Download `StorageDoctor-android.apk` from the [latest release](https://github.com/natadej/mac-storage-doctor/releases/latest) on the phone and open it (allow "Install unknown apps" for your browser). Or build it yourself (needs JDK 17 and the Android SDK; set `sdk.dir` in `local.properties` or `ANDROID_HOME`):

```bash
cd android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or copy `app-debug.apk` to the phone and open it (allow "Install unknown apps" for your file manager).

On first launch, read the disclosure and choose **Agree and open file access settings**, or
**Not now — use storage totals**. Without file access, total/free space, the target and optional
storage alerts work; file scans and cleanup are off. Usage access for visible-app sizes is optional.
The in-app privacy policy is available on the disclosure screen and at the bottom of Overview.

## How deleting works

- Photos, videos and audio go to the **system trash** (Android 11+). Android shows its own confirmation;
  you can restore them from Files/Photos for 30 days. The space is only freed when the trash is
  emptied, either by Android after 30 days or with **Empty trash**.
- Other files and folders are **deleted permanently** after you confirm in the app.
- Locked, never deleted by the app: the storage root, top-level folders (DCIM, Download…), `Android/`
  internals, the Camera folder itself, and WhatsApp / LINE / Signal media (clean those in the chat app,
  so its database stays consistent).

## Limits (Android rules, not app bugs)

- Apps cannot read other apps' private data or `Android/data` / `Android/obb`. That space appears
  under "Apps & app data" (with Usage access) or "Android system & not scanned".
- Google Play permits file management (including maintenance) as a possible All-files-access use,
  subject to declaration and approval. See [Play declaration and submission checklist](PLAY_DECLARATION.md).
  A storage-cleaner label alone does not guarantee eligibility.
- Android package visibility filters the Apps tab; it is not a complete installed-app inventory.
  Other packages remain in the system/unscanned residual. The app does not request `QUERY_ALL_PACKAGES`.
- Per-app sizes include app code, data and cache as reported by Android; they can overlap a little with
  chat media folders that were also scanned.

## Development

```bash
./gradlew testDebugUnitTest   # scanner, rules, protection, plan and growth tests (JVM, no device)
./gradlew lintDebug
tools/fill_emulator.sh        # fill an emulator with ~2.5 GB of realistic test data
```

The scanning and classification logic lives in `app/src/main/java/com/natadej/storagedoctor/core/`
(plain Kotlin, no Android dependencies): `Rules.kt` (categories and folder rules), `Scanner.kt`,
`Duplicates.kt`, `Growth.kt`, `Protection.kt` (what may be deleted) and `Plan.kt`.
