#!/usr/bin/env bash
# Retry repository transport/artifact failures, preserving real compiler/test failures.
set -Eeuo pipefail
log="$(mktemp)"
trap 'rm -f "$log"' EXIT
refresh=()
for attempt in 1 2 3; do
  status=0
  bash "${OMNI_GRADLE_EXECUTABLE:-./gradlew}" "${refresh[@]}" "$@" 2>&1 | tee "$log" || status=$?
  if [[ "$status" == 0 ]]; then exit 0; fi
  retryable=false
  if grep -Eq 'Could not (find .*\.(jar|aar)|GET |HEAD )|Could not resolve all files for configuration|Read timed out|Connection reset|Received status code (429|50[0234])' "$log"; then
    retryable=true
  elif [[ "$attempt" -gt 1 ]] && grep -Fq "Plugin Repositories (could not resolve plugin artifact" "$log"; then
    # The first attempt already resolved plugins and reached an artifact failure.
    # Refreshing metadata can then expose a transient plugin-repository miss.
    # A plugin typo on the initial attempt still fails immediately.
    retryable=true
  fi
  if [[ "$attempt" == 3 ]] || [[ "$retryable" != true ]]; then
    # Republish the first compile errors / failing tests as check annotations and a step summary.
    # Job logs live in blob storage that API clients and sandboxed reviewers often cannot reach.
    # Informational only: it never changes the exit status.
    annotations="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../../scripts/ci_failure_annotations.py"
    if [[ -f "$annotations" ]]; then python3 "$annotations" "$log" || true; fi
    exit "$status"
  fi
  # Refresh cached negative artifact lookups on the next attempt. No cache deletion,
  # version changes, untrusted mirrors or bypass of failing tests.
  echo "::warning::Gradle dependency download failed; refreshing dependency metadata (attempt $((attempt + 1))/3)."
  refresh=(--refresh-dependencies)
  sleep "${OMNI_GRADLE_RETRY_DELAY_SECONDS:-$((attempt * 5))}"
done
