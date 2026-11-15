#!/usr/bin/env bash
# Fill an emulator's shared storage with realistic test data (about 3 GB).
# Usage: tools/fill_emulator.sh   (needs adb and a running emulator; never run against a real phone)
set -euo pipefail
adb shell getprop ro.kernel.qemu | grep -q 1 || { echo "Not an emulator, refusing." >&2; exit 1; }
adb shell 'set -e; S=/sdcard
mk() { mkdir -p "$(dirname "$1")"; head -c "$2" /dev/urandom > "$1"; }
mk $S/DCIM/Camera/VID_20260101_120000.mp4 400000000
for i in $(seq 1 30); do mk $S/DCIM/Camera/IMG_202601$(printf %02d $i)_0900.jpg 3000000; done
for i in $(seq 1 20); do mk $S/Pictures/Screenshots/Screenshot_2026-11-0$((i%9+1))-$i.png 1500000; done
mk $S/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp\ Video/VID-20261101-WA0001.mp4 300000000
mk $S/Android/media/org.telegram.messenger/Telegram/Telegram\ Video/clip.mp4 200000000
mk $S/Download/old_game.apk 150000000
mk $S/Download/backup_photos.zip 250000000
mk $S/Download/models/gemma-2b-it-q4.gguf 600000000
mk $S/Movies/.thumbnails/cache.bin 60000000
mk $S/Movies/trip_4k.mp4 350000000
cp $S/Movies/trip_4k.mp4 $S/Download/trip_4k_copy.mp4
mkdir -p $S/Documents/AgentRuns/renders
for i in $(seq 1 400); do head -c 20000 /dev/urandom > $S/Documents/AgentRuns/renders/frame_$(printf %05d $i).png; done
mkdir -p $S/Documents/AgentRuns/build/out/build/out/build/out/build
'
echo done
adb shell du -sh /sdcard/
