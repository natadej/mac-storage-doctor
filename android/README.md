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
- **Large files** (over 100 MB), **Duplicates** (same content, compared by hash), **Apps** (size of
  every app, needs Usage access), **Explore** (folder tree by size).
- **Check every 6 hours** (optional): a notification when storage is over target or grew 5 GB or more.

## Install

Download `StorageDoctor-android.apk` from the [latest release](https://github.com/natadej/mac-storage-doctor/releases/latest) on the phone and open it (allow "Install unknown apps" for your browser). Or build it yourself (needs JDK 17 and the Android SDK; set `sdk.dir` in `local.properties` or `ANDROID_HOME`):

```bash
cd android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or copy `app-debug.apk` to the phone and open it (allow "Install unknown apps" for your file manager).

On first launch, tap **Allow all files access**. For per-app sizes, also tap **Allow usage access**.

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
- Google Play only allows "All files access" for some app types, so this app is meant to be sideloaded.
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
