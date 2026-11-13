#!/usr/bin/env bash

# EXIT handler: finish the owned emulator before deleting its writable AVD.
# Cleanup diagnostics must preserve the original build/test/report exit status.
cleanup_neural_emulator() {
  local neural_original_status=$?
  local neural_attempt
  trap - EXIT
  if [[ -n "${neural_emulator_pid:-}" ]]; then
    kill "$neural_emulator_pid" 2>/dev/null || true
    for neural_attempt in {1..20}; do
      if ! kill -0 "$neural_emulator_pid" 2>/dev/null; then break; fi
      sleep 0.1
    done
    if kill -0 "$neural_emulator_pid" 2>/dev/null; then
      kill -KILL "$neural_emulator_pid" 2>/dev/null || true
    fi
    wait "$neural_emulator_pid" 2>/dev/null || true
  fi
  if [[ -n "${neural_avd_dir:-}" ]]; then
    if ! rm -rf "$neural_avd_dir"; then
      echo '::warning::Could not remove the temporary neural AVD after emulator shutdown.' >&2
    fi
  fi
  exit "$neural_original_status"
}
