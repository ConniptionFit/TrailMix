#!/usr/bin/env bash
# Install a start-menu (XDG) desktop entry that launches TrailMix from this
# source checkout. Re-run after moving the project directory. Remove with:
#   rm ~/.local/share/applications/trailmix.desktop
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ELECTRON_BIN="$PROJECT_DIR/node_modules/.bin/electron"
ICON_PATH="$PROJECT_DIR/assets/logo.png"
APPS_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
DESKTOP_FILE="$APPS_DIR/trailmix.desktop"

if [ ! -x "$ELECTRON_BIN" ]; then
  echo "Electron not found at $ELECTRON_BIN — run 'npm install' first." >&2
  exit 1
fi

mkdir -p "$APPS_DIR"

# env -u guards against launchers that inherit ELECTRON_RUN_AS_NODE (e.g. from
# an IDE-spawned session), which makes require('electron') return a path string.
cat > "$DESKTOP_FILE" <<EOF
[Desktop Entry]
Type=Application
Name=TrailMix
Comment=Local-first AI note-taking and live transcription
Exec=env -u ELECTRON_RUN_AS_NODE "$ELECTRON_BIN" "$PROJECT_DIR"
Path=$PROJECT_DIR
Icon=$ICON_PATH
Terminal=false
Categories=Office;
Keywords=transcription;notes;meeting;whisper;ollama;
StartupNotify=true
EOF

chmod +x "$DESKTOP_FILE"

if command -v desktop-file-validate >/dev/null 2>&1; then
  desktop-file-validate "$DESKTOP_FILE"
fi
if command -v update-desktop-database >/dev/null 2>&1; then
  update-desktop-database "$APPS_DIR" 2>/dev/null || true
fi

echo "Installed $DESKTOP_FILE"
echo "TrailMix should now appear in your application launcher."
