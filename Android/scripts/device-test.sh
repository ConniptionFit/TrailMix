#!/usr/bin/env bash
# Live device-test helpers for TrailMix (v1.22.0 Granola-style notes pass).
#
# Usage (from the repo's Android/ directory, with JAVA_HOME and ANDROID_HOME set as in CLAUDE.md):
#   scripts/device-test.sh preflight          # device, both TrailMix packages, signers, permissions
#   scripts/device-test.sh install-preview    # download the CI preview APK from GitHub and install it
#   scripts/device-test.sh install            # build the REAL release APK and update IN PLACE (needs the release key)
#   scripts/device-test.sh logs-start <name>  # start a filtered logcat capture into test-artifacts/
#   scripts/device-test.sh logs-stop          # stop the capture
#   scripts/device-test.sh crashes            # print the crash buffer (empty = good)
#   scripts/device-test.sh shot <name>        # screenshot into test-artifacts/
#   scripts/device-test.sh ui <name>          # uiautomator dump into test-artifacts/
#   scripts/device-test.sh state              # FGS type, mic recorders, pump threads
#
# Two packages exist on the test phone:
#   com.trailmix.app          the REAL app with the user's notes. NEVER uninstall or clear it
#                             (allowBackup=false: every note is lost). See CLAUDE.md's Trap row.
#   com.trailmix.app.preview  "TrailMix Preview", the CI build from the GitHub pre-release. Its own
#                             separate data; safe to uninstall when a newer preview needs replacing.
# preflight/state/logs target the preview package by default; set TM_PKG=com.trailmix.app to
# point them at the real app.
set -euo pipefail

REAL_PKG=com.trailmix.app
PREVIEW_PKG=com.trailmix.app.preview
PKG="${TM_PKG:-$PREVIEW_PKG}"
REPO=ConniptionFit/TrailMix
EXPECTED_SIGNER_SHA256=b6a53a1665231cde60fbde87918de47b5acf4b0dc0dbb70f59146deef3ae5bbd
ART=test-artifacts
ADB="${ANDROID_HOME:?set ANDROID_HOME}/platform-tools/adb"
mkdir -p "$ART"

latest_build_tool() { ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1; }
digest_of() { grep -oiE '[0-9a-f]{2}(:?[0-9a-f]{2}){31}' | head -1 | tr -d ':' | tr 'A-F' 'a-f'; }

case "${1:-}" in
  preflight)
    "$ADB" devices -l
    for P in "$REAL_PKG" "$PREVIEW_PKG"; do
      echo "--- $P"
      "$ADB" shell dumpsys package "$P" | grep -E "versionName|versionCode|firstInstallTime|lastUpdateTime" || echo "NOT INSTALLED"
      APK_PATH=$("$ADB" shell pm path "$P" 2>/dev/null | head -1 | sed 's/package://' | tr -d '\r')
      if [ -n "$APK_PATH" ]; then
        "$ADB" pull "$APK_PATH" "$ART/installed-$P.apk" >/dev/null
        echo "signer: $("$(latest_build_tool)/apksigner" verify --print-certs "$ART/installed-$P.apk" | digest_of)"
      fi
    done
    echo "(real app signer must be $EXPECTED_SIGNER_SHA256)"
    echo "--- runtime permissions ($PKG)"
    "$ADB" shell dumpsys package "$PKG" | grep -E "RECORD_AUDIO|POST_NOTIFICATIONS|READ_CALENDAR|READ_MEDIA" || true
    echo "--- screen state (must be unlocked; never bypass the lock)"
    "$ADB" shell dumpsys window | grep -E "mDreamingLockscreen|isKeyguardShowing|mShowingLockscreen" || true
    ;;
  install-preview)
    # Downloads the APK attached to the moving pre-release v<version>-preview (published by
    # .github/workflows/preview.yml on every push to a claude/** branch). Needs `gh auth login`.
    VERSION=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
    TAG="v${VERSION}-preview"
    rm -f "$ART"/TrailMix-*-preview-*.apk
    gh release download "$TAG" --repo "$REPO" --pattern 'TrailMix-*-preview-*.apk' --dir "$ART"
    APK=$(ls "$ART"/TrailMix-*-preview-*.apk | head -1)
    echo "downloaded: $APK"
    gh release view "$TAG" --repo "$REPO" | sed -n '1,25p'
    GOT_PKG=$("$(latest_build_tool)/aapt2" dump badging "$APK" | grep -oP "package: name='\K[^']+")
    if [ "$GOT_PKG" != "$PREVIEW_PKG" ]; then
      echo "REFUSING: APK package is $GOT_PKG, expected $PREVIEW_PKG."; exit 1
    fi
    "$(latest_build_tool)/aapt2" dump permissions "$APK" | tee "$ART/apk-permissions.txt"
    if grep -qE "INTERNET|ACCESS_NETWORK_STATE" "$ART/apk-permissions.txt"; then
      echo "REFUSING: a network permission leaked into the APK."; exit 1
    fi
    if ! "$ADB" install -r "$APK"; then
      echo ""
      echo "Install failed. If the error is INSTALL_FAILED_UPDATE_INCOMPATIBLE, an older preview"
      echo "build with a different CI debug key is installed. Re-run with:"
      echo "  scripts/device-test.sh replace-preview"
      echo "which uninstalls ONLY $PREVIEW_PKG (never $REAL_PKG) and installs this APK."
      exit 1
    fi
    "$ADB" shell dumpsys package "$PREVIEW_PKG" | grep -E "versionName|versionCode|lastUpdateTime"
    ;;
  replace-preview)
    APK=$(ls "$ART"/TrailMix-*-preview-*.apk | head -1)
    GOT_PKG=$("$(latest_build_tool)/aapt2" dump badging "$APK" | grep -oP "package: name='\K[^']+")
    [ "$GOT_PKG" = "$PREVIEW_PKG" ] || { echo "REFUSING: $APK is $GOT_PKG"; exit 1; }
    "$ADB" uninstall "$PREVIEW_PKG"
    "$ADB" install "$APK"
    "$ADB" shell dumpsys package "$PREVIEW_PKG" | grep -E "versionName|versionCode|lastUpdateTime"
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
    "$ADB" shell dumpsys package "$REAL_PKG" | grep -E "versionName|versionCode|firstInstallTime|lastUpdateTime"
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
    sed -n '2,27p' "$0"; exit 1 ;;
esac
