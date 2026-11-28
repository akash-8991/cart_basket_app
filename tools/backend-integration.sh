#!/usr/bin/env bash
# Runs the Android app's HTTP layer against a real, freshly started local backend (Phase 3.7).
#
#   tools/backend-integration.sh          # JVM: :core:data BackendIntegrationTest
#   tools/backend-integration.sh device   # also the end-to-end journey on an API 36 emulator
#
# Needs: PostgreSQL with the `smartcart` role and database (backend/README.md), JDK 21, and for `device`
# the API 36 system image. The backend runs in development mode with in-memory rate limits, so every
# run starts clean. Blinkit is enabled (with automation on and a test approval record) through the operator API.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
PORT="${SMARTCART_IT_PORT:-8091}"
ADMIN="$(openssl rand -hex 24)"
OUT="$ROOT/android/build/backend-integration"
mkdir -p "$OUT"
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }

admin() { curl -sf -X PUT "localhost:$PORT/admin/$1" -H "Authorization: Bearer $ADMIN" -H "X-Operator: integration" -H "Content-Type: application/json" -d "$2" >/dev/null; }

# A fresh backend (in-memory rate limits and stub data start clean) with Blinkit enabled, automation on and a
# test approval record for Blinkit (tasks need one, Phase 4.5).
start_backend() {
  pkill -f "com.smartcart.server.ApplicationKt" >/dev/null 2>&1; sleep 1
  log "starting backend on :$PORT"
  (cd "$ROOT/backend" && SMARTCART_HOST=0.0.0.0 SMARTCART_PORT="$PORT" SMARTCART_ADMIN_TOKEN="$ADMIN" ./gradlew run --console=plain -q) >>"$OUT/backend.log" 2>&1 &
  BACKEND=$!
  for _ in $(seq 1 90); do curl -sf "localhost:$PORT/readyz" >/dev/null && break; sleep 2; done
  curl -sf "localhost:$PORT/readyz" >/dev/null || { log "backend did not start; see $OUT/backend.log"; exit 1; }
  admin flags/provider.blinkit.enabled '{"enabled":true,"reason":"integration run"}'
  admin flags/automation.global.enabled '{"enabled":true,"reason":"integration run"}'
  admin providers/blinkit/approval '{"adapter_version":"teststore-1","approved_by":"integration","record_url":"https://example.invalid/approvals/integration","reason":"integration run against SmartCart Test Store"}'
}
trap 'pkill -f "com.smartcart.server.ApplicationKt" >/dev/null 2>&1; kill $BACKEND >/dev/null 2>&1' EXIT
: > "$OUT/backend.log"
start_backend

log "JVM: BackendIntegrationTest"
(cd "$ROOT/android" && ./gradlew :core:data:test --console=plain -q -PbackendUrl="http://127.0.0.1:$PORT" --tests "com.smartcart.data.http.BackendIntegrationTest") >"$OUT/jvm.log" 2>&1
JVM=$?
python3 - "$ROOT/android/core/data/build/test-results/test" <<'EOF'
import glob, sys, xml.etree.ElementTree as ET
t = f = s = 0
for x in glob.glob(sys.argv[1] + "/*BackendIntegrationTest*.xml"):
    for tc in ET.parse(x).getroot().iter("testcase"):
        t += 1; s += bool(tc.findall("skipped"))
        for fl in tc.findall("failure") + tc.findall("error"):
            f += 1; print("  FAIL", tc.get("name"), (fl.get("message") or "")[:300])
print(f"  JVM integration: {t} tests, {f} failed, {s} skipped")
EOF

if [ "${1:-}" = "device" ]; then
  start_backend # the JVM tests' sign-ups would otherwise count against the device journeys' rate limits
  SERIAL=emulator-5560
  log "device: booting API 36 emulator"
  "$ANDROID_HOME/emulator/emulator" -avd smartcart_api36 -port 5560 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 2048 >"$OUT/emulator.log" 2>&1 &
  EMU=$!
  "$ANDROID_HOME/platform-tools/adb" -s $SERIAL wait-for-device
  for _ in $(seq 1 120); do [ "$("$ANDROID_HOME/platform-tools/adb" -s $SERIAL shell getprop sys.boot_completed | tr -d '\r')" = "1" ] && break; sleep 2; done
  for k in window_animation_scale transition_animation_scale animator_duration_scale; do "$ANDROID_HOME/platform-tools/adb" -s $SERIAL shell settings put global $k 0; done
  "$ANDROID_HOME/platform-tools/adb" -s $SERIAL shell settings put system font_scale 1.0
  "$ANDROID_HOME/platform-tools/adb" -s $SERIAL shell input keyevent 82
  log "device: BackendEndToEndTest (emulator reaches the host at 10.0.2.2)"
  rm -rf "$ROOT"/android/app/build/outputs/androidTest-results
  (cd "$ROOT/android" && ANDROID_SERIAL=$SERIAL ./gradlew :app:connectedDebugAndroidTest --console=plain -q \
     -Pandroid.testInstrumentationRunnerArguments.class=com.smartcart.app.BackendEndToEndTest \
     -Pandroid.testInstrumentationRunnerArguments.backendUrl="http://10.0.2.2:$PORT") >"$OUT/device.log" 2>&1
  DEVICE=$?
  find "$ROOT/android/app/build/outputs/connected_android_test_additional_output" -name "*.png" -exec cp {} "$OUT/" \; 2>/dev/null
  "$ANDROID_HOME/platform-tools/adb" -s $SERIAL emu kill >/dev/null 2>&1; wait $EMU 2>/dev/null
  log "device: exit $DEVICE (logs and screenshots in $OUT)"
  [ $DEVICE -eq 0 ] || exit $DEVICE
fi
exit $JVM
