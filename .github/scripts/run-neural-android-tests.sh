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
source "$(dirname "${BASH_SOURCE[0]}")/neural-emulator-cleanup.sh"
trap cleanup_neural_emulator EXIT
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
for report in correctness benchmark support-correctness hybrid-correctness expanded-correctness memory-correctness reliability-correctness subword-correctness answerability-correctness recall-correctness contrastive-correctness quantity-evidence; do
  if adb exec-out run-as com.omnidev.workspace.neuralprobe cat "files/$report.json" > "$report_dir/$report.tmp"; then
    mv "$report_dir/$report.tmp" "$report_dir/$report.json"
  else rm -f "$report_dir/$report.tmp"; fi
done
if [[ "$neural_status" != 0 ]]; then exit "$neural_status"; fi
python3 - "$report_dir" "$neural_benchmark" "$neural_run_id" <<'PY'
import json, pathlib, sys
directory = pathlib.Path(sys.argv[1])
report = json.loads((directory / 'correctness.json').read_text())
assert report['parity_passed'] and report['cascade_parity_passed'] and report['rich_parity_passed'] and report['probes'] == 139 and report['run_id'] == sys.argv[3]
assert report['public_parity_passed'] and report['public_probes'] == 699
assert report['residual_parity_passed'] and report['residual_probes'] == 859
assert report['utility_parity_passed'] and report['utility_probes'] == 995
assert report['prototype_parity_passed'] and report['prototype_probes'] == 1070
assert report['trust_parity_passed'] and report['trust_probes'] == 1150
support = json.loads((directory / 'support-correctness.json').read_text())
assert support['support_parity_passed'] and support['predictions'] == 1755 and support['queries'] == 351 and support['documents'] == 58 and support['retained_default_checks'] == 351 and support['run_id'] == sys.argv[3]
hybrid = json.loads((directory / 'hybrid-correctness.json').read_text())
assert hybrid['hybrid_parity_passed'] and hybrid['predictions'] == 459 and hybrid['retained_default_checks'] == 459 and hybrid['documents'] == 58 and hybrid['run_id'] == sys.argv[3]
expanded = json.loads((directory / 'expanded-correctness.json').read_text())
assert expanded['expanded_parity_passed'] and expanded['predictions'] == 1101 and expanded['retained_default_checks'] == 1101 and expanded['documents'] == 58 and expanded['run_id'] == sys.argv[3]
memory = json.loads((directory / 'memory-correctness.json').read_text())
assert memory['memory_parity_passed'] and memory['predictions'] == 1239 and memory['retained_default_checks'] == 1239 and memory['documents'] == 58 and memory['run_id'] == sys.argv[3]
reliability = json.loads((directory / 'reliability-correctness.json').read_text())
assert reliability['reliability_parity_passed'] and reliability['predictions'] == 1368 and reliability['retained_default_checks'] == 1368 and reliability['documents'] == 58 and reliability['run_id'] == sys.argv[3]
subword = json.loads((directory / 'subword-correctness.json').read_text())
assert subword['subword_parity_passed'] and subword['predictions'] == 1488 and subword['retained_default_checks'] == 1488 and subword['documents'] == 58 and subword['run_id'] == sys.argv[3]
answerability = json.loads((directory / 'answerability-correctness.json').read_text())
assert answerability['answerability_parity_passed'] and answerability['predictions'] == 1782 and answerability['retained_default_checks'] == 1782 and answerability['documents'] == 58 and answerability['run_id'] == sys.argv[3]
recall = json.loads((directory / 'recall-correctness.json').read_text())
assert recall['recall_parity_passed'] and recall['predictions'] == 1872 and recall['retained_default_checks'] == 1872 and recall['documents'] == 58 and recall['run_id'] == sys.argv[3]
quantity = json.loads((directory / 'quantity-evidence.json').read_text())
assert quantity['cases'] == 36 and quantity['run_id'] == sys.argv[3]
contrastive = json.loads((directory / 'contrastive-correctness.json').read_text())
assert contrastive['contrastive_parity_passed'] and contrastive['predictions'] == 1968 and contrastive['matched_arm_predictions'] == 7872 and contrastive['retained_default_checks'] == 1968 and contrastive['documents'] == 58 and contrastive['run_id'] == sys.argv[3]
assert report['contract_parity_passed'] and report['contract_probes'] == 1366
if sys.argv[2] == 'true':
    benchmark = json.loads((directory / 'benchmark.json').read_text())
    assert benchmark['encoding_included'] and benchmark['timing_assertions'] is False and benchmark['run_id'] == sys.argv[3]
    print(json.dumps(benchmark, indent=2))
print('Android correctness report verified; timing is descriptive, not a quality/phone-speed gate.')
PY
