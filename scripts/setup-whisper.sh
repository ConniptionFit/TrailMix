#!/usr/bin/env bash
set -euo pipefail

# setup-whisper.sh - Set up local whisper.cpp binary and models

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN_DIR="${PROJECT_DIR}/bin"
WHISPER_DIR="${BIN_DIR}/whisper.cpp"

echo "=== Setting up Whisper.cpp ==="
echo "Project Directory: ${PROJECT_DIR}"
echo "Binary Directory: ${BIN_DIR}"

mkdir -p "${BIN_DIR}"
export PATH="${BIN_DIR}/cmake/bin:${PATH}"

if [ ! -d "${WHISPER_DIR}" ]; then
  echo "Cloning whisper.cpp repository..."
  git clone --depth 1 https://github.com/ggerganov/whisper.cpp.git "${WHISPER_DIR}"
else
  echo "whisper.cpp repository already exists."
fi

echo "Compiling whisper.cpp..."
cd "${WHISPER_DIR}"
make -j$(nproc)

echo "Downloading GGML Whisper models..."
# Download ggml-tiny.bin and ggml-base.bin
./models/download-ggml-model.sh tiny
./models/download-ggml-model.sh base

echo "Whisper setup complete! Binary is at ${WHISPER_DIR}/main"
