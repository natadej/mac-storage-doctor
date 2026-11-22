#!/bin/bash
# Installs (or updates) Mac Storage Doctor in ~/Applications and starts it.
#   curl -fsSL https://github.com/natadej/mac-storage-doctor/releases/latest/download/install.sh | bash
set -euo pipefail
URL="https://github.com/natadej/mac-storage-doctor/releases/latest/download/MacStorageDoctor-mac.zip"
DEST="$HOME/Applications/Mac Storage Doctor"
if ! python3 -c 'import sys; sys.exit(sys.version_info < (3, 8))' >/dev/null 2>&1; then
  echo "Mac Storage Doctor needs python3. Run:  xcode-select --install"
  echo "Click Install in the window that opens, then run this command again."
  exit 1
fi
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
echo "Downloading Mac Storage Doctor..."
curl -fsSL "$URL" -o "$tmp/app.zip"
unzip -q "$tmp/app.zip" -d "$tmp"
mkdir -p "$HOME/Applications"
rm -rf "$DEST"
mv "$tmp/Mac Storage Doctor" "$DEST"
chmod +x "$DEST/start.command"
echo "Installed V.$(cat "$DEST/VERSION") in $DEST"
echo "Next time, double-click 'start.command' in that folder (or run this command again to update)."
echo "Starting... (press Ctrl+C here to stop the app)"
cd "$DEST"
exec python3 storage_doctor.py </dev/null
