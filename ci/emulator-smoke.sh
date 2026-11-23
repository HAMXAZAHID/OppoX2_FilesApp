#!/usr/bin/env bash
# End-to-end check on a plain Android 11 emulator with no Google services (like ColorOS Watch):
# install the release APK, grant file access over adb, then browse, view, copy, rename and delete.
set -euo pipefail
PKG=dev.hamza.explorer
APK=out/Explorer-release.apk
T=/sdcard/Download/ExplorerTest
SHOTS=shots
mkdir -p "$SHOTS"
step=0

shot() { step=$((step+1)); adb exec-out screencap -p > "$SHOTS/$(printf %02d $step)-$1.png" || true; }

dump() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  adb shell cat /sdcard/ui.xml 2>/dev/null > ui.xml || true
}

# Prints "x y" of the first node whose text (or description) contains $1.
find_text() {
  python3 - "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
try:
    root = ET.parse("ui.xml").getroot()
except Exception:
    sys.exit(0)
for n in root.iter("node"):
    if sys.argv[1] in (n.get("text") or "") or sys.argv[1] in (n.get("content-desc") or ""):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
}

wait_text() {
  for _ in $(seq 1 ${2:-30}); do
    dump
    if [ -n "$(find_text "$1")" ]; then return 0; fi
    sleep 1
  done
  echo "Timed out waiting for text: $1" >&2
  shot "missing-$(echo "$1" | tr -c 'a-zA-Z0-9' '_')"
  echo "== Crash log" >&2
  adb logcat -d -b crash >&2 || true
  adb logcat -d -s AndroidRuntime:E >&2 || true
  return 1
}

tap_text() {
  wait_text "$1" "${2:-30}"
  adb shell input tap $(find_text "$1")
}

long_press_text() {
  wait_text "$1"
  read -r x y <<<"$(find_text "$1")"
  adb shell input swipe "$x" "$y" "$x" "$y" 1200
}

back() { adb shell input keyevent KEYCODE_BACK; sleep 1; }

echo "== Device"
adb shell getprop ro.build.version.release
adb shell pm list packages | grep -i -E "gms|wearable" || echo "No Google services or Wear OS on this device (good)"

echo "== Test files"
adb shell mkdir -p $T
adb shell "echo 'hello from the explorer test' > $T/hello.txt"
# A tiny valid PNG, to check the image viewer opens it.
python3 - <<'PY'
import struct, zlib
def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
raw = b"".join(b"\x00" + b"\xe8\x71\x0a" * 64 for _ in range(64))
png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 64, 64, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")
open("pic.png", "wb").write(png)
PY
adb push pic.png $T/pic.png

adb logcat -c || true
echo "== Install and grant All files access (what the README tells you to do on the watch)"
adb install -r "$APK"
adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow
adb shell am start -W -n $PKG/.MainActivity
wait_text "Watch storage"
shot home

echo "== Browse"
tap_text "Download"
tap_text "ExplorerTest"
wait_text "hello.txt"
wait_text "pic.png"
shot browse

echo "== Text viewer"
tap_text "hello.txt"
wait_text "hello from the explorer test"
shot text
back

echo "== Image viewer"
tap_text "pic.png"
sleep 3
shot image
adb shell pidof $PKG >/dev/null
back

echo "== Copy hello.txt up into Download"
long_press_text "hello.txt"
tap_text "Copy"
wait_text "Paste here"
back
tap_text "Paste here"
wait_text "Copied" 60
shot copied
tap_text "Done"
adb shell ls /sdcard/Download/hello.txt
[ "$(adb shell cat /sdcard/Download/hello.txt | tr -d '\r')" = "hello from the explorer test" ]
echo "Copy matches."

echo "== Rename the copy"
long_press_text "hello.txt"
tap_text "Rename"
wait_text "Save"
tap_text "hello.txt"
adb shell input keyevent KEYCODE_MOVE_END
for _ in $(seq 1 12); do adb shell input keyevent KEYCODE_DEL; done
adb shell input text "renamed.txt"
adb shell input keyevent KEYCODE_ENTER
sleep 2
adb shell ls /sdcard/Download/renamed.txt
shot renamed
echo "Renamed."

echo "== Move renamed.txt into ExplorerTest"
dump
if [ -z "$(find_text "renamed.txt")" ]; then back; fi
long_press_text "renamed.txt"
tap_text "Move"
wait_text "Paste here"
tap_text "ExplorerTest"
tap_text "Paste here"
wait_text "Moved" 60
tap_text "Done"
adb shell ls $T/renamed.txt
if adb shell ls /sdcard/Download/renamed.txt 2>/dev/null; then echo "Original still there after move" >&2; exit 1; fi
echo "Moved."

echo "== Delete it"
long_press_text "renamed.txt"
tap_text "Delete"
tap_text "Tap again to delete"
wait_text "Deleted" 60
shot deleted
tap_text "Done"
if adb shell ls $T/renamed.txt 2>/dev/null; then echo "File still there after delete" >&2; exit 1; fi
echo "Deleted."

echo "== App still running"
adb shell pidof $PKG
echo "Smoke test passed."
