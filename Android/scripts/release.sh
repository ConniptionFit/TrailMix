#!/bin/bash
# BLD-05: the release-cut checklist, made a script instead of a set of steps someone has to
# remember. Before this, versionCode/versionName were hand-edited and tags/releases cut by
# hand — the error class that let v1.12.0-v1.17.0 accumulate with no tags/GitHub Releases,
# leaving Obtainium's Latest stale for six versions (see [[Build and Deployment]]).
#
# What this script does NOT do: it never publishes anything. It builds, verifies, and prints
# the exact `git tag` / `gh release create` commands for you to run — publishing a release is
# a deliberate, confirmed action, not something a script should do silently.
#
# Usage: Android/scripts/release.sh
# Run from anywhere; paths below are resolved relative to this script's location.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ANDROID_DIR"

: "${JAVA_HOME:=$HOME/.jdks/jdk-17.0.20+8}"
: "${ANDROID_HOME:=$HOME/Android/Sdk}"
export JAVA_HOME ANDROID_HOME

# The live contract for in-place Obtainium updates (REL-07) — every published release APK
# must be signed with this key. Never change this value to "make a build pass"; if it stops
# matching, the release key is missing or wrong, not this script.
EXPECTED_SIGNER_SHA256="B6:A5:3A:16:65:23:1C:DE:60:FB:DE:87:91:8D:E4:7B:5A:CF:4B:0D:C0:DB:B7:0F:59:14:6D:EE:F3:AE:5B:BD"

BUILD_TOOLS="$(ls -d "$ANDROID_HOME"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
if [ -z "$BUILD_TOOLS" ]; then
  echo "FAIL: no build-tools found under $ANDROID_HOME/build-tools" >&2
  exit 1
fi
APKSIGNER="${BUILD_TOOLS}apksigner"
AAPT2="${BUILD_TOOLS}aapt2"

VERSION_NAME="$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts)"
VERSION_CODE="$(grep -oP 'versionCode\s*=\s*\K[0-9]+' app/build.gradle.kts)"
if [ -z "$VERSION_NAME" ] || [ -z "$VERSION_CODE" ]; then
  echo "FAIL: could not read versionName/versionCode from app/build.gradle.kts" >&2
  exit 1
fi
echo "== Releasing TrailMix v$VERSION_NAME (versionCode $VERSION_CODE) =="

echo "-- git: confirm clean tree, expected branch"
if [ -n "$(git status --porcelain)" ]; then
  echo "FAIL: working tree is not clean — commit or stash before cutting a release" >&2
  git status --short >&2
  exit 1
fi
CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
if [ "$CURRENT_BRANCH" != "main" ]; then
  echo "FAIL: on branch '$CURRENT_BRANCH', expected 'main'" >&2
  exit 1
fi
EXISTING_TAG="$(git tag -l "v$VERSION_NAME")"
if [ -n "$EXISTING_TAG" ]; then
  echo "FAIL: tag v$VERSION_NAME already exists locally" >&2
  exit 1
fi

echo "-- gradle: full check gate (unit tests + lint + ktlint)"
./gradlew check --console=plain

echo "-- gradle: assembleRelease"
./gradlew :app:assembleRelease --console=plain

APK="app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$APK" ]; then
  echo "FAIL: expected APK not found at $APK" >&2
  exit 1
fi

echo "-- verify: signer fingerprint"
SIGNER_LINE="$("$APKSIGNER" verify --print-certs "$APK" | grep 'SHA-256 digest' | head -1)"
if [[ "$SIGNER_LINE" != *"$EXPECTED_SIGNER_SHA256"* ]]; then
  echo "FAIL: signer does not match the expected release key." >&2
  echo "  Expected: $EXPECTED_SIGNER_SHA256" >&2
  echo "  Got:      $SIGNER_LINE" >&2
  echo "  This APK is not publishable — it cannot upgrade real installs." >&2
  echo "  Check ~/.gradle/gradle.properties for TRAILMIX_STORE_FILE/PASSWORD/KEY_ALIAS/KEY_PASSWORD." >&2
  exit 1
fi
echo "   OK: $SIGNER_LINE"

echo "-- verify: no INTERNET / ACCESS_NETWORK_STATE"
PERMS="$("$AAPT2" dump permissions "$APK")"
if echo "$PERMS" | grep -qE "name='android.permission.(INTERNET|ACCESS_NETWORK_STATE)'"; then
  echo "FAIL: a network permission leaked into the shipped APK." >&2
  echo "$PERMS" >&2
  exit 1
fi
echo "   OK: no INTERNET / ACCESS_NETWORK_STATE"

echo "-- verify: arm64-v8a only (BLD-07)"
ABIS="$(unzip -l "$APK" | grep -oE 'lib/[a-zA-Z0-9_-]+/' | sort -u)"
if [ "$ABIS" != "lib/arm64-v8a/" ]; then
  echo "FAIL: release APK does not ship exactly arm64-v8a. Found:" >&2
  echo "$ABIS" >&2
  exit 1
fi
echo "   OK: arm64-v8a only"

APK_SIZE="$(du -h "$APK" | cut -f1)"
echo "-- verify: size = $APK_SIZE"

ASSET_NAME="TrailMix-v${VERSION_NAME}.apk"
STAGED_APK="app/build/outputs/apk/release/${ASSET_NAME}"
cp "$APK" "$STAGED_APK"

echo ""
echo "== All checks passed =="
echo "Staged asset: $STAGED_APK ($APK_SIZE)"
echo ""
echo "Next steps (not run by this script — review and run by hand):"
echo ""
echo "  git tag -a v$VERSION_NAME -m \"v$VERSION_NAME\""
echo "  git push github main --tags"
echo "  git push forgejo main --tags"
echo "  gh release create v$VERSION_NAME \"$STAGED_APK\" --repo ConniptionFit/TrailMix \\"
echo "      --title \"v$VERSION_NAME\" --latest --notes-file <path-to-release-notes.md>"
echo ""
echo "Then: adb install -r \"$STAGED_APK\" to verify the exact published asset on a device"
echo "before telling Obtainium users it's ready."
