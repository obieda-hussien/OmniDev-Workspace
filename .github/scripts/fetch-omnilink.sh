#!/usr/bin/env bash
set -euo pipefail

VERSION="${OMNILINK_VERSION:-v2.0.0}"
GROUP_PATH="com/github/obieda-hussien/OmniLinkSDK"
DEST_ROOT="${OMNILINK_LOCAL_REPO:-$PWD/ci-m2}"

fetch_module() {
  local artifact="$1"
  local extension="$2"
  local dest_dir="$DEST_ROOT/$GROUP_PATH/$artifact/$VERSION"
  local base_url="https://jitpack.io/$GROUP_PATH/$artifact/$VERSION/$artifact-$VERSION"
  mkdir -p "$dest_dir"

  fetch_one() {
    local ext="$1"
    local target="$dest_dir/$artifact-$VERSION.$ext"
    local tmp="$target.tmp"
    local attempt=1
    local max_attempts=8

    if [[ -s "$target" ]]; then
      echo "$artifact $VERSION .$ext already available in local CI Maven repo."
      return 0
    fi

    while (( attempt <= max_attempts )); do
      echo "Fetching $artifact $VERSION .$ext (attempt $attempt/$max_attempts)..."
      rm -f "$tmp"
      if curl \
        --fail \
        --location \
        --silent \
        --show-error \
        --connect-timeout 20 \
        --max-time 120 \
        --retry 1 \
        --retry-delay 3 \
        --retry-all-errors \
        --output "$tmp" \
        "$base_url.$ext"; then
        test -s "$tmp"
        mv "$tmp" "$target"
        echo "Fetched $target"
        return 0
      fi

      rm -f "$tmp"
      if (( attempt == max_attempts )); then
        echo "::error::Failed to fetch $artifact $VERSION .$ext after $max_attempts attempts."
        return 1
      fi

      local sleep_for=$(( 5 * (1 << (attempt - 1)) ))
      if (( sleep_for > 90 )); then sleep_for=90; fi
      echo "JitPack unavailable/building/rate-limited; retrying in ${sleep_for}s..."
      sleep "$sleep_for"
      attempt=$(( attempt + 1 ))
    done
  }

  fetch_one pom
  fetch_one "$extension"
}

# Stage every v2 module Workspace resolves. Doing this once in run-quality lets downstream jobs use
# the uploaded ci-m2 repository without repeatedly hitting JitPack.
fetch_module "omni-link-sdk" "aar"
fetch_module "omni-link-public" "aar"
fetch_module "omni-link-transport" "jar"

echo "OmniLinkSDK $VERSION modules staged under $DEST_ROOT"
