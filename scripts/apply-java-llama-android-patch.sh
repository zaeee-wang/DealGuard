#!/usr/bin/env bash
# Apply Android NDK CMake fix to java-llama.cpp submodule.
# Run from repo root after: git submodule update --init
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SUBMODULE="${ROOT}/app/java-llama.cpp"
PATCH="${ROOT}/patches/java-llama-android-cmake.patch"
if [ ! -f "$PATCH" ]; then
  echo "Patch not found: $PATCH"
  exit 1
fi
if [ ! -f "${SUBMODULE}/CMakeLists.txt" ]; then
  echo "Submodule not initialized. Run: git submodule update --init"
  exit 1
fi
cd "$SUBMODULE"
if git apply --check "$PATCH" 2>/dev/null; then
  git apply "$PATCH"
  echo "Applied java-llama-android-cmake.patch"
else
  echo "Patch already applied or not applicable (e.g. submodule already has the fix)."
fi
