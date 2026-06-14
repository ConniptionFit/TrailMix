#!/usr/bin/env bash
set -euo pipefail

# setup-whisper.sh - Set up local whisper.cpp binary and models

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN_DIR="${PROJECT_DIR}/bin"
WHISPER_DIR="${BIN_DIR}/whisper.cpp"
WHISPER_REPO="https://github.com/ggerganov/whisper.cpp.git"

echo "=== Setting up Whisper.cpp ==="
echo "Project Directory: ${PROJECT_DIR}"
echo "Binary Directory: ${BIN_DIR}"

mkdir -p "${BIN_DIR}"
export PATH="${BIN_DIR}/cmake/bin:${PATH}"

ensure_cmake() {
  if command -v cmake >/dev/null 2>&1; then
    echo "Using cmake: $(command -v cmake)"
    return 0
  fi

  echo "Error: cmake not found."
  echo "Expected bundled cmake at ${BIN_DIR}/cmake/bin/cmake, or install cmake on your system."
  exit 1
}

ensure_compiler() {
  if command -v g++ >/dev/null 2>&1 || command -v c++ >/dev/null 2>&1; then
    return 0
  fi

  echo "Error: C++ compiler not found (need g++ or c++)."
  echo "Debian/Ubuntu: sudo apt install build-essential"
  echo "Fedora:        sudo dnf install gcc-c++"
  exit 1
}

ensure_whisper_source() {
  if [ -f "${WHISPER_DIR}/CMakeLists.txt" ]; then
    echo "whisper.cpp source already present."
    return 0
  fi

  if [ -d "${WHISPER_DIR}/.git" ]; then
    echo "whisper.cpp checkout is incomplete (missing CMakeLists.txt). Re-cloning..."
  elif [ -d "${WHISPER_DIR}" ]; then
    echo "whisper.cpp directory exists but is not a valid clone. Re-cloning..."
  else
    echo "Cloning whisper.cpp repository..."
  fi

  rm -rf "${WHISPER_DIR}"
  git clone --depth 1 "${WHISPER_REPO}" "${WHISPER_DIR}"
}

build_whisper() {
  echo "Compiling whisper.cpp..."
  cd "${WHISPER_DIR}"

  if [ -f CMakeLists.txt ]; then
    cmake -B build -DCMAKE_BUILD_TYPE=Release
    cmake --build build -j"$(nproc 2>/dev/null || echo 4)"
  elif [ -f Makefile ]; then
    make -j"$(nproc 2>/dev/null || echo 4)"
  else
    echo "Error: no CMakeLists.txt or Makefile found in ${WHISPER_DIR}."
    echo "Try removing the directory and re-running this script:"
    echo "  rm -rf ${WHISPER_DIR}"
    echo "  ./scripts/setup-whisper.sh"
    exit 1
  fi
}

find_whisper_binary() {
  local candidates=(
    "${WHISPER_DIR}/build/bin/whisper-cli"
    "${WHISPER_DIR}/build/bin/main"
    "${WHISPER_DIR}/main"
    "${WHISPER_DIR}/build/bin/whisper"
  )

  for candidate in "${candidates[@]}"; do
    if [ -f "${candidate}" ]; then
      echo "${candidate}"
      return 0
    fi
  done

  return 1
}

ensure_cmake
ensure_compiler
ensure_whisper_source
build_whisper

echo "Downloading GGML Whisper models..."
./models/download-ggml-model.sh tiny
./models/download-ggml-model.sh base

echo "Whisper setup complete!"
if binary="$(find_whisper_binary)"; then
  echo "Binary: ${binary}"
else
  echo "Warning: whisper binary not found — check build output above."
  exit 1
fi
