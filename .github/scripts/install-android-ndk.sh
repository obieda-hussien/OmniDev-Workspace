#!/usr/bin/env bash
set -Eeuo pipefail

: "${ANDROID_NDK_VERSION:?ANDROID_NDK_VERSION must be set}"

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK_ROOT" ]]; then
  echo "::error::ANDROID_SDK_ROOT/ANDROID_HOME is not set"
  exit 1
fi

SDKMANAGER="$(command -v sdkmanager || true)"
if [[ -z "$SDKMANAGER" ]]; then
  echo "::error::sdkmanager is not available on PATH"
  exit 1
fi

NDK_DIR="$SDK_ROOT/ndk/$ANDROID_NDK_VERSION"
SOURCE_PROPERTIES="$NDK_DIR/source.properties"

is_valid_ndk() {
  [[ -s "$SOURCE_PROPERTIES" ]] || return 1
  local revision
  revision="$(awk -F= '/^[[:space:]]*Pkg\.Revision[[:space:]]*=/{gsub(/[[:space:]]/, "", $2); print $2; exit}' "$SOURCE_PROPERTIES")"
  [[ "$revision" == "$ANDROID_NDK_VERSION" ]]
}

if is_valid_ndk; then
  echo "NDK $ANDROID_NDK_VERSION is already installed and valid."
  exit 0
fi

# sdkmanager/AGP can leave a partially extracted NDK or a corrupt temporary
# archive after a network/CDN interruption. Remove only disposable SDK caches
# and the requested NDK version before each retry so Gradle never sees a
# half-installed toolchain.
for attempt in 1 2 3; do
  echo "::group::Install Android NDK $ANDROID_NDK_VERSION (attempt $attempt/3)"

  rm -rf "$NDK_DIR"
  rm -rf "$SDK_ROOT/.temp" || true
  rm -rf "$HOME/.android/cache" || true
  mkdir -p "$SDK_ROOT/ndk"

  set +e
  timeout 15m "$SDKMANAGER" --sdk_root="$SDK_ROOT" --install "ndk;$ANDROID_NDK_VERSION"
  rc=$?
  set -e

  if [[ $rc -eq 0 ]] && is_valid_ndk; then
    echo "NDK installed successfully: $NDK_DIR"
    cat "$SOURCE_PROPERTIES"
    echo "::endgroup::"
    exit 0
  fi

  echo "NDK install attempt $attempt failed (sdkmanager exit=$rc)."
  if [[ -e "$SOURCE_PROPERTIES" ]]; then
    echo "source.properties exists but failed validation:"
    cat "$SOURCE_PROPERTIES" || true
  fi
  echo "::endgroup::"

  rm -rf "$NDK_DIR"
  sleep $((attempt * 5))
done

echo "::error::Failed to install a valid Android NDK $ANDROID_NDK_VERSION after 3 attempts"
"$SDKMANAGER" --sdk_root="$SDK_ROOT" --list_installed | grep -E 'ndk;|cmake;' || true
exit 1
