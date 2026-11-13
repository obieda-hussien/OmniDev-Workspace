# Experimental neural Android validation

`neural-android` is a standalone library with an instrumentation test APK, enabled
only with `-PincludeNeuralAndroidTests=true`. Its main manifest is empty; neural
runtime dependencies and generated checkpoints are **androidTest only**. No app
flavor depends on it. Normal builds do not configure it. This does not enable
neural tool selection, change permissions, or modify P0/P2/P5/P6.

The separate refusal gate preserves the original domain distribution, but its
rejection quality remains insufficient for production. Android compatibility and
latency evidence cannot fix that limitation. See [the experiment](SEPARATE_REJECTION_GATE.md).

## Correctness contract

Fixture preparation reproduces the existing seeded domain/gate experiments with
unchanged training, development, calibration and refusal settings. It packages the
generated Java checkpoints and 139 public probes: 30 authored holdout rows, 80
CLINC-derived test rows, 25 prior challenges, and four empty/non-lexical cases.
All are previously observed regression inputs, **not an independent accuracy test**.
CLINC attribution/license and source/checkpoint/probe hashes accompany the assets.
No network fetch, personal data, model reasoning or credentials enter preparation.

The same contract runs on the host before test packaging and on Android ART:

- Exact encoder feature hashes and exact dense/sparse logits and decisions.
- JVM/ART probability agreement within `1e-6`, with identical choices/refusals.
- The gate preserves domain probabilities and never invents or upgrades a choice.
- Fixed probe count/order and bounded, finite, normalized observations.

The committed bilingual refusal checkpoint adds a separate golden status/choice
file over the same 139 inputs. Correctness additionally checks the two-refusal-head
cascade against JVM observations, exact dense/sparse parity and preservation of
every prior refusal. This is still regression validation, not a fresh quality test.
The timing benchmark covers the original single gate and the rich presence path;
the two-refusal-head binary cascade has no timing claim in this experiment.

An additional ART test rejects null, oversized or normalization-expanded text and
non-finite thresholds before inference. The benchmark is a third opt-in test.

Host smoke also corrupts feature hashes, row counts and statuses to verify rejection.
Cross-runtime probability tolerance accounts for math-library differences; encoding
or decisions that differ fail the test and must be investigated, not silently accepted.

## Run on a connected Android device

Use JDK 17, Android SDK/platform 36 and an authorized API 24+ device. Run at the
repository root (ADB/device selection is explicit when multiple devices exist):

```bash
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SERIAL=your-adb-serial
NEURAL_BENCHMARK=true bash .github/scripts/run-neural-android-tests.sh
```

The script builds before measuring, preserves test APKs for report extraction,
and writes `neural-android/build/reports/probe/{correctness,benchmark}.json` plus
provenance. It installs only a standalone test package (`com.omnidev.workspace.neuralprobe`),
does not launch Omni, and does not change system performance settings or permissions.
Omit `NEURAL_BENCHMARK=true` for correctness only. No remote provider is required.
Each scripted run passes a fresh ID and rejects reports from another invocation.

## Rich representation extension

The rich fitted-vocabulary checkpoint is also androidTest only. Additional goldens on
the same 139 inputs verify paired vocabulary hashes, exact feature hashes, five sigmoid
scores within `2e-6`, typed decisions and exact dense/sparse behavior. These are runtime
regressions, not an additional language-quality test. The runner requires the new rich
parity field and the invocation ID before accepting a correctness report.

The opt-in Android benchmark now rotates six modes over twelve batches: the original
dense/sparse single and gated paths, plus rich dense/sparse. It still excludes model
construction/cold start and has no timing assertions or physical-phone claim. See
[the full experiment](RICH_TEXT_PRESENCE.md) for quality and host-cost tradeoffs.

## Measurement limits

The opt-in measurement compares dense/sparse single-head, shared gated and rich
presence classification, including encoding, inference, decision allocation and
consumption of all returned probabilities. Construction, correctness checks and
file I/O are outside the timed region. Each mode receives 256 warmup calls and
twelve batches of 139 calls over all probes. Six mode orders rotate each round.
Reports contain raw batch averages and their median, not individual-request p95.
There are no flaky timing assertions or automatic promotion based on speed.

Reports identify SDK, model, hardware and ABIs and include an emulator heuristic.
They conservatively mark physical-device verification false. CI executes an API 30
x86_64 emulator in the neural workflow, separate from the main application's API 30
UI regression suite. Emulator timing is **not** Infinix/Helio G85 timing. These
measurements do not establish cold startup, peak heap, battery use, real app latency,
or production quality. The physical device, thermal/power conditions and repeated
runs still need separate inspection before making a phone performance claim.

Local fallback `--source-target-only` preparation can exercise generation on an
incomplete JDK; it does not prove `--release 8` or ART compatibility. CI uses full
JDK 17 and the stricter release-8 compilation before Android tests.

## Public checkpoint parity extension

The standalone test APK also verifies the public refusal and separate six-class checkpoints on 699 observations (139 prior inputs plus 560 public test utterances). `public_parity_passed` and an exact `public_probes` count are required in the fresh correctness report. These are runtime-parity checks; the separate classifier failed its quality-preservation gate and remains experimental. No normal APK dependency or physical-device claim is introduced. See [PUBLIC_BILINGUAL_TRAINING.md](PUBLIC_BILINGUAL_TRAINING.md).
