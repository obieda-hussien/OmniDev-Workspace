#!/usr/bin/env bash
set -Eeuo pipefail

: "${ANDROID_CMAKE_VERSION:?ANDROID_CMAKE_VERSION must be set}"
if [[ ! "$ANDROID_CMAKE_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "::error::Invalid Android CMake version: $ANDROID_CMAKE_VERSION"
  exit 1
fi

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK_ROOT" || "$SDK_ROOT" != /* ]]; then
  echo "::error::ANDROID_SDK_ROOT/ANDROID_HOME must be an absolute SDK path"
  exit 1
fi
SDK_ROOT="$(realpath -m "$SDK_ROOT")"
if [[ "$SDK_ROOT" == / ]]; then
  echo "::error::The filesystem root cannot be used as the Android SDK path"
  exit 1
fi

SDKMANAGER="$(command -v sdkmanager || true)"
if [[ -z "$SDKMANAGER" ]]; then
  echo "::error::sdkmanager is not available on PATH"
  exit 1
fi

CMAKE_DIR="$SDK_ROOT/cmake/$ANDROID_CMAKE_VERSION"
SOURCE_PROPERTIES="$CMAKE_DIR/source.properties"

is_valid_cmake() {
  [[ -s "$SOURCE_PROPERTIES" && -x "$CMAKE_DIR/bin/cmake" && -x "$CMAKE_DIR/bin/ninja" ]] || return 1
  local revision cmake_version
  revision="$(awk -F= '/^[[:space:]]*Pkg\.Revision[[:space:]]*=/{gsub(/[[:space:]]/, "", $2); print $2; exit}' "$SOURCE_PROPERTIES")"
  [[ "$revision" == "$ANDROID_CMAKE_VERSION" ]] || return 1
  cmake_version="$("$CMAKE_DIR/bin/cmake" --version)" || return 1
  # Android's CMake binary includes a build suffix (e.g. 3.22.1-g37088a8).
  # Keep the exact release pinned while accepting that upstream build metadata.
  local reported="${cmake_version%%$'\n'*}"
  reported="${reported#cmake version }"
  [[ "${reported%%-*}" == "$ANDROID_CMAKE_VERSION" ]] || {
    echo "Unexpected CMake binary version: $reported"
    return 1
  }
  "$CMAKE_DIR/bin/ninja" --version >/dev/null
}

if is_valid_cmake; then
  echo "CMake $ANDROID_CMAKE_VERSION is already installed and valid."
  exit 0
fi

# A truncated SDK download can leave both a partial installation and a cached
# archive. Retry with a clean package directory and disposable download caches.
for attempt in 1 2 3; do
  echo "::group::Install Android CMake $ANDROID_CMAKE_VERSION (attempt $attempt/3)"
  rm -rf "$CMAKE_DIR" "$SDK_ROOT/.temp"
  rm -rf "$HOME/.android/cache"
  mkdir -p "$SDK_ROOT/cmake"

  rc=0
  timeout 10m "$SDKMANAGER" --sdk_root="$SDK_ROOT" --install "cmake;$ANDROID_CMAKE_VERSION" || rc=$?
  if [[ $rc -eq 0 ]] && is_valid_cmake; then
    echo "CMake installed successfully: $CMAKE_DIR"
    echo "::endgroup::"
    exit 0
  fi

  echo "CMake install attempt $attempt failed (sdkmanager exit=$rc or invalid installation)."
  echo "::endgroup::"
  rm -rf "$CMAKE_DIR"
  if [[ $attempt -lt 3 ]]; then
    sleep $((attempt * 5))
  fi
done

echo "::error::Failed to install a valid Android CMake $ANDROID_CMAKE_VERSION after 3 attempts"
exit 1
