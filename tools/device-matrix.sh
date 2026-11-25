#!/usr/bin/env bash
# Runs the instrumented device suite on a matrix of Android emulators, one at a time.
#
#   tools/device-matrix.sh            # default matrix: API 26 30 34 36
#   tools/device-matrix.sh 34 36      # selected API levels
#
# For each API level: create a Pixel 6 AVD, boot it headless, disable animations, run
# connectedDebugAndroidTest, re-run the accessibility audit at 2x font scale, collect JUnit XML
# and screenshots into android/build/device-matrix/apiNN, then shut the emulator down.
# Requires system images "system-images;android-NN;google_apis;arm64-v8a" to be installed.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
SDK="$ANDROID_HOME"
ADB="$SDK/platform-tools/adb"
EMU="$SDK/emulator/emulator"
AVDMGR="$SDK/cmdline-tools/latest/bin/avdmanager"
OUT="$ROOT/android/build/device-matrix"
if [ $# -gt 0 ]; then APIS=("$@"); else APIS=(26 30 34 36); fi
PORT=5560
SERIAL="emulator-$PORT"

mkdir -p "$OUT"
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }

wait_for_boot() {
  "$ADB" -s "$SERIAL" wait-for-device
  for _ in $(seq 1 180); do
    [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && return 0
    sleep 2
  done
  return 1
}

collect() { # $1 = destination
  local dest="$1"; mkdir -p "$dest"
  cp "$ROOT"/android/app/build/outputs/androidTest-results/connected/debug/*.xml "$dest/" 2>/dev/null || true
  find "$ROOT/android/app/build/outputs/connected_android_test_additional_output" -name "*.png" -exec cp {} "$dest/" \; 2>/dev/null || true
}

for API in "${APIS[@]}"; do
  NAME="smartcart_api$API"
  IMG="system-images;android-$API;google_apis;arm64-v8a"
  DEST="$OUT/api$API"
  rm -rf "$DEST"; mkdir -p "$DEST"
  log "API $API: creating AVD"
  echo no | "$AVDMGR" create avd -n "$NAME" -k "$IMG" -d pixel_6 --force >/dev/null 2>&1 || { log "API $API: image missing, skipped"; echo "skipped" > "$DEST/status"; continue; }
  printf 'hw.ramSize=2048\nhw.keyboard=yes\n' >> "$HOME/.android/avd/$NAME.avd/config.ini"

  log "API $API: booting"
  "$EMU" -avd "$NAME" -port "$PORT" -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 2048 >"$DEST/emulator.log" 2>&1 &
  EMU_PID=$!
  if ! wait_for_boot; then log "API $API: boot timed out"; echo "boot-timeout" > "$DEST/status"; kill "$EMU_PID" 2>/dev/null; continue; fi
  for k in window_animation_scale transition_animation_scale animator_duration_scale; do "$ADB" -s "$SERIAL" shell settings put global $k 0; done
  "$ADB" -s "$SERIAL" shell svc power stayon true
  "$ADB" -s "$SERIAL" shell input keyevent 82
  "$ADB" -s "$SERIAL" shell settings put system font_scale 1.0

  log "API $API: full device suite"
  rm -rf "$ROOT"/android/app/build/outputs/androidTest-results "$ROOT"/android/app/build/outputs/connected_android_test_additional_output
  (cd "$ROOT/android" && ANDROID_SERIAL="$SERIAL" ./gradlew :app:connectedDebugAndroidTest --console=plain -q) >"$DEST/gradle.log" 2>&1
  echo $? > "$DEST/exit-code"
  collect "$DEST"

  log "API $API: accessibility audit at 2x font"
  "$ADB" -s "$SERIAL" shell settings put system font_scale 2.0
  rm -rf "$ROOT"/android/app/build/outputs/androidTest-results "$ROOT"/android/app/build/outputs/connected_android_test_additional_output
  (cd "$ROOT/android" && ANDROID_SERIAL="$SERIAL" ./gradlew :app:connectedDebugAndroidTest --console=plain -q \
     -Pandroid.testInstrumentationRunnerArguments.class=com.smartcart.app.AccessibilityAuditTest) >"$DEST/gradle-font2.log" 2>&1
  echo $? > "$DEST/exit-code-font2"
  collect "$DEST/font2"
  "$ADB" -s "$SERIAL" shell settings put system font_scale 1.0

  log "API $API: shutting down"
  "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1
  wait "$EMU_PID" 2>/dev/null
  echo "done" > "$DEST/status"
done

python3 - "$OUT" <<'EOF'
import glob, os, sys, xml.etree.ElementTree as ET
out = sys.argv[1]
print("\n| API | Suite | Tests | Failed | Notes |\n|---|---|---|---|---|")
for d in sorted(glob.glob(os.path.join(out, "api*"))):
    api = os.path.basename(d)[3:]
    status = open(os.path.join(d, "status")).read().strip() if os.path.exists(os.path.join(d, "status")) else "?"
    for label, sub in (("full", d), ("a11y @2x font", os.path.join(d, "font2"))):
        xs = glob.glob(os.path.join(sub, "*.xml"))
        t = f = 0; notes = []
        for x in xs:
            r = ET.parse(x).getroot()
            for tc in r.iter("testcase"):
                t += 1
                for fl in tc.findall("failure") + tc.findall("error"):
                    f += 1; notes.append(tc.get("name"))
        print(f"| {api} | {label} | {t} | {f} | {status if not xs else ', '.join(notes)} |")
EOF
