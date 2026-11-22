#!/bin/bash
# Build the release downloads into dist/: the Mac zip and the Android APK.
# Usage: tools/make_release.sh   (version comes from storage_doctor.py)
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION=$(python3 storage_doctor.py --version | sed 's/.*V\.//')
grep -q "versionName = \"$VERSION\"" android/app/build.gradle.kts || { echo "android versionName is not $VERSION"; exit 1; }
rm -rf dist && mkdir -p "dist/Mac Storage Doctor"
cp storage_doctor.py scanner.py rules.py duplicates.py index.html app.js style.css start.command README.md "dist/Mac Storage Doctor/"
echo "$VERSION" > "dist/Mac Storage Doctor/VERSION"
chmod +x "dist/Mac Storage Doctor/start.command"
(cd dist && zip -qr MacStorageDoctor-mac.zip "Mac Storage Doctor" && rm -rf "Mac Storage Doctor")
(cd android && ./gradlew :app:assembleRelease --console=plain -q)
cp android/app/build/outputs/apk/release/app-release.apk dist/StorageDoctor-android.apk
cp docs/install.sh dist/install.sh
echo "Built V.$VERSION:"; ls -l dist
