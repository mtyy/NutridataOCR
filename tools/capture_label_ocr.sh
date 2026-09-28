#!/bin/zsh
# Records ML Kit OCR for label-samples/photos into label-samples/ocr on a device or emulator.
# Usage: tools/capture_label_ocr.sh [adb-serial]   (LABEL_SAMPLES overrides the samples folder)
set -euo pipefail
cd "${0:A:h}/.."
samples="${LABEL_SAMPLES:-label-samples}"
adb_bin="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
[[ -x "$adb_bin" ]] || adb_bin=adb
adb_cmd=("$adb_bin")
[[ -n "${1:-${ANDROID_SERIAL:-}}" ]] && adb_cmd+=(-s "${1:-$ANDROID_SERIAL}")
[[ -d "$samples/photos" ]] || { print -u2 "No $samples/photos folder"; exit 1; }

./gradlew -q :app:assembleDebug :app:assembleDebugAndroidTest
"${adb_cmd[@]}" install -r -t app/build/outputs/apk/debug/app-debug.apk >/dev/null
"${adb_cmd[@]}" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null

remote=/sdcard/Android/data/ee.mty.nutidataocr/files
"${adb_cmd[@]}" shell rm -rf "$remote/labels" "$remote/ocr"
"${adb_cmd[@]}" shell mkdir -p "$remote/labels"
"${adb_cmd[@]}" push "$samples/photos/." "$remote/labels/" >/dev/null
result=$("${adb_cmd[@]}" shell am instrument -w -e class ee.mty.nutidataocr.LabelOcrCapture \
    ee.mty.nutidataocr.test/androidx.test.runner.AndroidJUnitRunner)
print -r -- "$result"
[[ "$result" == *"OK (1 test)"* ]] || exit 1

rm -rf "$samples/ocr.new"
"${adb_cmd[@]}" pull "$remote/ocr" "$samples/ocr.new" >/dev/null
rm -rf "$samples/ocr" && mv "$samples/ocr.new" "$samples/ocr"
"${adb_cmd[@]}" shell rm -rf "$remote/labels" "$remote/ocr"
print "OCR recorded in $samples/ocr"
