#!/usr/bin/env bash
# Retry repository transport/artifact failures, preserving real compiler/test failures.
set -Eeuo pipefail
log="$(mktemp)"
trap 'rm -f "$log"' EXIT
max_attempts=4
refresh=()
dependency_failure_seen=false
central_failure_seen=false
central_fallback="${OMNI_MAVEN_CENTRAL_FALLBACK:-0}"
for ((attempt = 1; attempt <= max_attempts; attempt++)); do
  status=0
  OMNI_MAVEN_CENTRAL_FALLBACK="$central_fallback" bash "${OMNI_GRADLE_EXECUTABLE:-./gradlew}" "${refresh[@]}" "$@" 2>&1 | tee "$log" || status=$?
  if [[ "$status" == 0 ]]; then exit 0; fi

  retryable=false
  missing_artifact=false
  ksp_bootstrap=false
  if grep -Fq "Plugin [id: 'com.google.devtools.ksp', version:" "$log" &&
     grep -Fq "Plugin Repositories (could not resolve plugin artifact" "$log"; then
    ksp_bootstrap=true
  fi
  if grep -Eq 'Could not find ([^[:space:]]+\.(jar|aar)([[:space:].]|$)|[^[:space:]:]+:[^[:space:]:]+:[^[:space:]]+\.)' "$log"; then
    missing_artifact=true
    retryable=true
  elif grep -Eq 'Could not (GET|HEAD) |Read timed out|Connection reset|Received status code (429|50[0234])' "$log"; then
    retryable=true
  elif [[ "$ksp_bootstrap" == true ]]; then
    retryable=true
  elif [[ "$dependency_failure_seen" == true ]] && grep -Fq "Plugin Repositories (could not resolve plugin artifact" "$log"; then
    # A plugin already resolved earlier in the invocation can be temporarily unavailable.
    retryable=true
  fi
  if grep -Eq '^e: |\.java:[0-9]+: error:| > .+ FAILED$|There were failing tests|Lint found [1-9][0-9]* errors?' "$log"; then
    # --continue can report a source, test or lint failure alongside missing dependencies.
    retryable=false
  fi
  if [[ "$attempt" == "$max_attempts" ]] || [[ "$retryable" != true ]]; then
    # Republish the first compile errors / failing tests as check annotations and a step summary.
    # Job logs live in blob storage that API clients and sandboxed reviewers often cannot reach.
    # Informational only: it never changes the exit status.
    annotations="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../../scripts/ci_failure_annotations.py"
    if [[ -f "$annotations" ]]; then python3 "$annotations" "$log" || true; fi
    exit "$status"
  fi
  dependency_failure_seen=true
  if [[ "$ksp_bootstrap" == true ]] || grep -Eq 'https://(repo\.maven\.apache\.org|repo1\.maven\.org|maven-central\.storage-download\.googleapis\.com)/maven2/' "$log"; then
    central_failure_seen=true
  fi
  refresh=()
  retry_mode="reusing the dependency cache"
  if [[ "$central_failure_seen" == true && "$central_fallback" == 0 &&
        ( "$attempt" -ge 2 || "$ksp_bootstrap" == true ) ]]; then
    central_fallback=2
    retry_mode="switching to the Google Cloud Maven Central mirror"
  elif [[ "$central_failure_seen" == true && "$central_fallback" == 2 ]]; then
    central_fallback=1
    retry_mode="switching to Maven Central at repo1.maven.org"
  elif [[ "$missing_artifact" == true && ( "$attempt" == 2 || ( "$attempt" == 3 && "$central_failure_seen" == true ) ) ]]; then
    refresh=(--refresh-dependencies)
    retry_mode="refreshing dependency metadata"
  fi
  echo "::warning::Gradle dependency lookup failed; $retry_mode (attempt $((attempt + 1))/$max_attempts)."
  sleep "${OMNI_GRADLE_RETRY_DELAY_SECONDS:-$((attempt * 5))}"
done
