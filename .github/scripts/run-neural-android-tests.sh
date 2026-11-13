#!/usr/bin/env bash
set -Eeuo pipefail
: "${ANDROID_HOME:?Android SDK required}"
if [[ $# -gt 1 || ( $# == 1 && $1 != --emulator ) ]]; then echo 'Usage: run-neural-android-tests.sh [--emulator]' >&2; exit 2; fi
neural_benchmark=${NEURAL_BENCHMARK:-false}
if [[ "$neural_benchmark" != true && "$neural_benchmark" != false ]]; then echo 'NEURAL_BENCHMARK must be true or false' >&2; exit 2; fi
gradle_args=(--no-daemon --max-workers 1 '-PincludeNeuralAndroidTests=true'
  "-Dorg.gradle.jvmargs=${CI_GRADLE_JVMARGS:--Xmx2g -Dfile.encoding=UTF-8}")
report_dir=neural-android/build/reports/probe
mkdir -p "$report_dir"
rm -f "$report_dir/correctness.json" "$report_dir/benchmark.json" "$report_dir/provenance.txt"
neural_run_id=$(python3 -c 'import uuid; print(uuid.uuid4().hex)')
bash .github/scripts/gradle-with-retry.sh "${gradle_args[@]}" :neural-android:assembleDebugAndroidTest
cp neural-android/build/generated/neuralFixtures/assets/provenance.txt "$report_dir/provenance.txt"
neural_emulator_pid=
neural_avd_dir=
cleanup() {
  if [[ -n "$neural_emulator_pid" ]]; then kill "$neural_emulator_pid" 2>/dev/null || true; fi
  if [[ -n "$neural_avd_dir" ]]; then rm -rf "$neural_avd_dir"; fi
}
trap cleanup EXIT
if [[ "${1:-}" == --emulator ]]; then
  neural_avd_dir=$(mktemp -d)
  export ANDROID_AVD_HOME="$neural_avd_dir"
  export ANDROID_SERIAL=emulator-5580
  if adb get-state >/dev/null 2>&1; then echo 'Emulator port 5580 is already in use' >&2; exit 1; fi
  printf 'no\n' | avdmanager create avd --force --name omni-neural-api30 \
    --package 'system-images;android-30;google_apis;x86_64' --device pixel_2
  "$ANDROID_HOME/emulator/emulator" -avd omni-neural-api30 -port 5580 -no-window -no-audio \
    -no-boot-anim -no-snapshot -gpu swiftshader -memory 2048 > "$report_dir/emulator.log" 2>&1 &
  neural_emulator_pid=$!
  timeout 180 adb wait-for-device
  for attempt in $(seq 1 90); do
    if [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then break; fi
    if ! kill -0 "$neural_emulator_pid" 2>/dev/null; then cat "$report_dir/emulator.log"; exit 1; fi
    sleep 2
  done
  if [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" != 1 ]]; then echo 'Emulator did not boot' >&2; exit 1; fi
else
  # Avoid measuring a different attached device accidentally. Select one explicitly if needed.
  if [[ -z "${ANDROID_SERIAL:-}" ]]; then
    mapfile -t neural_devices < <(adb devices | awk 'NR > 1 && NF {print $1}')
    if [[ ${#neural_devices[@]} != 1 ]]; then echo 'Select one authorized device with ANDROID_SERIAL' >&2; exit 1; fi
    export ANDROID_SERIAL=${neural_devices[0]}
  fi
  if [[ "$(adb get-state)" != device ]]; then echo 'Selected device is not authorized/ready' >&2; exit 1; fi
fi
neural_status=0
bash .github/scripts/gradle-with-retry.sh "${gradle_args[@]}" :neural-android:connectedDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  "-Pandroid.testInstrumentationRunnerArguments.neuralProbeRun=$neural_run_id" \
  "-Pandroid.testInstrumentationRunnerArguments.neuralBenchmark=$neural_benchmark" || neural_status=$?
for report in correctness benchmark; do
  if adb exec-out run-as com.omnidev.workspace.neuralprobe cat "files/$report.json" > "$report_dir/$report.tmp"; then
    mv "$report_dir/$report.tmp" "$report_dir/$report.json"
  else rm -f "$report_dir/$report.tmp"; fi
done
if [[ "$neural_status" != 0 ]]; then exit "$neural_status"; fi
python3 - "$report_dir" "$neural_benchmark" "$neural_run_id" <<'PY'
import json, pathlib, sys
directory = pathlib.Path(sys.argv[1])
report = json.loads((directory / 'correctness.json').read_text())
assert report['parity_passed'] and report['cascade_parity_passed'] and report['probes'] == 139 and report['run_id'] == sys.argv[3]
if sys.argv[2] == 'true':
    benchmark = json.loads((directory / 'benchmark.json').read_text())
    assert benchmark['encoding_included'] and benchmark['timing_assertions'] is False and benchmark['run_id'] == sys.argv[3]
    print(json.dumps(benchmark, indent=2))
print('Android correctness report verified; timing is descriptive, not a quality/phone-speed gate.')
PY
