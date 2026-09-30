#!/usr/bin/env bash
set -Eeuo pipefail

: "${ANDROID_HOME:?Android SDK required}"
: "${CI_GRADLE_JVMARGS:?Gradle memory settings required}"

# Build before starting the emulator so it does not compete with compilation for memory.
bash ./gradlew --no-daemon --max-workers 1 \
  -Dorg.gradle.jvmargs="$CI_GRADLE_JVMARGS" \
  -Pkotlin.compiler.execution.strategy=in-process \
  -Pkotlin.incremental=false \
  :app:assembleLiteDebug :app:assembleLiteDebugAndroidTest

# Use one explicit AVD location for both SDK tools and the emulator.
export ANDROID_AVD_HOME="$RUNNER_TEMP/omni-avd"
mkdir -p "$ANDROID_AVD_HOME"
printf 'no\n' | avdmanager create avd --force --name omni-ui-api30 \
  --package 'system-images;android-30;google_apis;x86_64' --device pixel_2
"$ANDROID_HOME/emulator/emulator" -avd omni-ui-api30 -no-window -no-audio \
  -no-boot-anim -no-snapshot -gpu swiftshader -memory 2048 \
  > "$RUNNER_TEMP/omni-emulator.log" 2>&1 &
emulator_pid=$!
trap 'kill "$emulator_pid" 2>/dev/null || true' EXIT

if ! timeout 180 adb wait-for-device; then
  cat "$RUNNER_TEMP/omni-emulator.log"
  echo '::error::Emulator did not connect to adb'
  exit 1
fi
for attempt in $(seq 1 90); do
  if [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
    break
  fi
  if ! kill -0 "$emulator_pid" 2>/dev/null; then
    cat "$RUNNER_TEMP/omni-emulator.log"
    exit 1
  fi
  sleep 2
done
if [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" != "1" ]]; then
  cat "$RUNNER_TEMP/omni-emulator.log"
  echo '::error::Emulator did not finish booting'
  exit 1
fi
adb shell input keyevent 82
# Keep system motion enabled: these tests also verify reduced-motion behavior explicitly.
adb shell settings put global window_animation_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global animator_duration_scale 1
bash ./gradlew --no-daemon --max-workers 1 \
  -Dorg.gradle.jvmargs="$CI_GRADLE_JVMARGS" \
  -Pkotlin.compiler.execution.strategy=in-process \
  :app:connectedLiteDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=com.omnidev.workspace.ui
