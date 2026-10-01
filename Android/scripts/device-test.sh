#!/usr/bin/env bash
# Live device-test helpers for TrailMix (v1.22.0 Granola-style notes pass).
#
# Usage (from the repo's Android/ directory, with JAVA_HOME and ANDROID_HOME set as in CLAUDE.md):
#   scripts/device-test.sh preflight          # device, installed version, signer, permissions
#   scripts/device-test.sh install            # build the release APK and update IN PLACE (never uninstalls)
#   scripts/device-test.sh logs-start <name>  # start a filtered logcat capture into test-artifacts/
#   scripts/device-test.sh logs-stop          # stop the capture
#   scripts/device-test.sh crashes            # print the crash buffer (empty = good)
#   scripts/device-test.sh shot <name>        # screenshot into test-artifacts/
#   scripts/device-test.sh ui <name>          # uiautomator dump into test-artifacts/
#   scripts/device-test.sh state              # FGS type, mic recorders, pump threads
#
# Never add an uninstall path here: uninstalling wipes every note (allowBackup=false). See the
# "Never adb uninstall" Trap row in CLAUDE.md.
set -euo pipefail

PKG=com.trailmix.app
EXPECTED_SIGNER_SHA256=b6a53a1665231cde60fbde87918de47b5acf4b0dc0dbb70f59146deef3ae5bbd
ART=test-artifacts
ADB="${ANDROID_HOME:?set ANDROID_HOME}/platform-tools/adb"
mkdir -p "$ART"

latest_build_tool() { ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1; }
digest_of() { grep -oiE '[0-9a-f]{2}(:?[0-9a-f]{2}){31}' | head -1 | tr -d ':' | tr 'A-F' 'a-f'; }

case "${1:-}" in
  preflight)
    "$ADB" devices -l
    echo "--- installed version"
    "$ADB" shell dumpsys package "$PKG" | grep -E "versionName|versionCode|firstInstallTime|lastUpdateTime" || echo "NOT INSTALLED"
    echo "--- installed signer (must be $EXPECTED_SIGNER_SHA256)"
    APK_PATH=$("$ADB" shell pm path "$PKG" | head -1 | sed 's/package://' | tr -d '\r')
    if [ -n "$APK_PATH" ]; then
      "$ADB" pull "$APK_PATH" "$ART/installed.apk" >/dev/null
      "$(latest_build_tool)/apksigner" verify --print-certs "$ART/installed.apk" | digest_of
    fi
    echo "--- runtime permissions"
    "$ADB" shell dumpsys package "$PKG" | grep -E "RECORD_AUDIO|POST_NOTIFICATIONS|READ_CALENDAR|READ_MEDIA" || true
    echo "--- screen state (must be unlocked; never bypass the lock)"
    "$ADB" shell dumpsys window | grep -E "mDreamingLockscreen|isKeyguardShowing|mShowingLockscreen" || true
    ;;
  install)
    ./gradlew :app:assembleRelease
    APK=app/build/outputs/apk/release/app-release.apk
    SIGNER=$("$(latest_build_tool)/apksigner" verify --print-certs "$APK" | digest_of)
    if [ "$SIGNER" != "$EXPECTED_SIGNER_SHA256" ]; then
      echo "REFUSING: release APK signer $SIGNER is not the real release key."
      echo "Check TRAILMIX_* in ~/.gradle/gradle.properties. Do NOT uninstall to work around this."
      exit 1
    fi
    echo "--- permissions in the new APK (no INTERNET / ACCESS_NETWORK_STATE allowed)"
    "$(latest_build_tool)/aapt2" dump permissions "$APK" | tee "$ART/apk-permissions.txt"
    if grep -qE "INTERNET|ACCESS_NETWORK_STATE" "$ART/apk-permissions.txt"; then
      echo "REFUSING: a network permission leaked into the APK."; exit 1
    fi
    "$ADB" install -r "$APK"
    "$ADB" shell dumpsys package "$PKG" | grep -E "versionName|versionCode|firstInstallTime|lastUpdateTime"
    ;;
  logs-start)
    NAME="${2:?name}"
    "$ADB" logcat -c
    nohup "$ADB" logcat -v time "TrailMixAudio:V" "TrailMixEngine:V" "TrailMixSession:V" "TrailMixAsr:V" \
      "TrailMixSpeech:V" "TrailMixJournal:V" "TrailMixDiarize:V" "AndroidRuntime:E" "*:S" \
      > "$ART/logcat-$NAME.txt" 2>&1 &
    echo $! > "$ART/.logcat.pid"
    echo "capturing to $ART/logcat-$NAME.txt"
    ;;
  logs-stop)
    [ -f "$ART/.logcat.pid" ] && kill "$(cat "$ART/.logcat.pid")" 2>/dev/null || true
    rm -f "$ART/.logcat.pid"
    ;;
  crashes)
    "$ADB" logcat -b crash -d
    ;;
  shot)
    "$ADB" exec-out screencap -p > "$ART/${2:?name}.png" && echo "$ART/$2.png"
    ;;
  ui)
    "$ADB" shell uiautomator dump /sdcard/tm-ui.xml >/dev/null
    "$ADB" pull /sdcard/tm-ui.xml "$ART/${2:?name}.xml" >/dev/null && echo "$ART/$2.xml"
    ;;
  state)
    PID=$("$ADB" shell pidof "$PKG" | tr -d '\r' || true)
    echo "pid: ${PID:-not running}"
    echo "--- foreground service (0x80 = microphone, 0x1 = dataSync)"
    "$ADB" shell dumpsys activity services "$PKG" | grep -E "isForeground|types=" || echo "no service"
    echo "--- active recorders"
    "$ADB" shell dumpsys audio | sed -n '/RecordActivityMonitor/,/^$/p' | grep -i "$PKG" || echo "none"
    if [ -n "${PID:-}" ]; then
      echo "--- trailmix threads"
      "$ADB" shell ps -T -p "$PID" | grep -i trailmix || echo "none"
    fi
    ;;
  *)
    sed -n '2,16p' "$0"; exit 1 ;;
esac
