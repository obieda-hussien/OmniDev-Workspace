# Public Arabic/English training: measured gains and failed promotion

These are desktop experiments and isolated Android test fixtures. Neither checkpoint is connected to the normal APK, tool authorization, provider routing or AgentPipeline. Passing training/export/ART checks does not mean a quality gate passed. Both reports explicitly set `production_eligible: false`.

## Source, scope and separation

Use the [original MASSIVE 1.1 release](https://github.com/alexa/massive) from Amazon, CC BY 4.0. Retain the original LICENSE and NOTICE in `neural-core/fixtures/massive-refusal`. The 40,251,390-byte archive SHA256 is `4cba5faa11c71437928e17cb1b9b3d8b8e727e7ea363a3a9a8045e19c0491577`. The importer separately checks the exact Arabic/English JSONL and attribution-file hashes. CI checks the committed subset offline; no data downloads, credentials or private chat records are needed.

The subset contains 2,240 unchanged utterances in 1,120 parallel semantic groups: 1,366 train, 138 development, 176 calibration and 560 test rows. Saudi Arabic and US English translations share an ID and always remain together. Original upstream training/test partitions stay intact; only upstream development is divided by SHA256(ID) parity. Remove an entire pair on normalized duplication or overlap with all older corpora/challenges, including the previous extra English evaluation. Store no source worker IDs, annotation judgments or slots.

Remap volume up/down/mute to device and greet/joke to chat. Taxi booking, takeaway orders, music playback, sending email and making coffee are unsupported **under this narrow experimental five-domain contract**. Calendar-set and weather are test-only topics. Ambiguous knowledge and other-volume labels are excluded. These mappings do not describe which tools Omni can execute. There are no new repository/build/knowledge positives, no Egyptian dialect validation and no standard MASSIVE benchmark claim. Selection limits and whole-pair exclusions are recorded in `provenance.json`.

The refusal protocol was fixed before its fit. A separate decision-model follow-up protocol was fixed after refusal evaluation and before the follow-up fit. Therefore MASSIVE test results are new observations for the refusal experiment, but already observed regressions for the later decision experiment. Existing test sets are regressions throughout. Neither repeated checks nor the later classifier may call them fresh independent validation.

## Two bounded experiments

Both reuse the frozen rich-text-v1 encoder and its ordered-vocabulary/FP32-IDF SHA256; no vocabulary fitting uses new test data. Group disjointness is enforced before fitting. Each experiment trains two seeded linear candidates, at most 200 epochs, checking development macro NLL every five epochs with patience 25. Only development selects the seed; only calibration selects temperature.

1. **Refusal gate:** combine old and new training rows, balance language/outcome strata, select seed 42 at epoch 5, temperature 0.75. Fold calibrated two-logit softmax into one sigmoid: 1,025 extra parameters, 14,493 total weights across the complete existing cascade. Select cutoff 0.99 on development with zero additional lost known choices. Extend `RichIntentRefusalModel` with an optional bounded head, sharing normalization/features and skipping work for prior refusals. Its legacy constructor remains unchanged; the extra head can only veto and cannot change original labels/probabilities or undo abstention.
2. **Separate proposal classifier:** six labels (five domains plus `__reject__`), language/class-balanced training, seed 42 at epoch 5, temperature 1.0, 6,150 parameters. This learns new proposals and is not a refusal-only drop-in. No policy in the prespecified probability/margin grid retained all prior correct development choices. Mark `development_feasible: false`; export fixed 0.6 probability / 0.15 margin for analysis only. `RichTextDecisionModel` always turns a reject label or empty-feature input into abstention. It grants no permissions and executes no tools.

## Results

MASSIVE test counts are 52 supported and 228 unsupported requests per language. The known denominator includes prior abstentions; accepted-correct counts below are not accuracy on all requests.

| Observation | Existing rich model | Extra refusal gate | Separate classifier (observed follow-up) |
|---|---:|---:|---:|
| Arabic supported choices accepted correctly / 52 | 14 | 14 | 23 |
| Arabic unsupported requests refused / 228 | 89 | 89 | 224 |
| English supported choices accepted correctly / 52 | 20 | 20 | 30 |
| English unsupported requests refused / 228 | 87 | 97 | 224 |
| Original authored set accepted correctly / 30 | 25 | 25 | 12 |
| Same-domain compositions accepted correctly / 30 | 30 | 30 | 14 |
| Cross-domain compositions refused / 120 | 44 | 44 | 116 |
| Prior extra English known set accepted correctly / 34 | 25 | 25 | 32 |
| Prior extra English unsupported refused / 80 | 49 | 49 | 80 |

The conservative gate improves ten English refusals, retains all observed known choices, and does not improve Arabic or held-out topics. Do not describe this as a broad quality breakthrough.

The separate classifier accepts 23 correct out of 27 total accepted Arabic test requests, and 30 correct out of 34 English acceptances. Those are **selective** correctness numbers; it still handles only 23/52 and 30/52 known requests correctly with the configured abstention policy. It also loses six previously correct Arabic and five English known cases, despite a net increase. On the 531 older regression inputs it reduces total accepted errors 164 -> 10, but accepted correct also falls 143 -> 131, losing 34 previously correct individual cases while gaining others. Raw Arabic known classification increases 24/52 -> 38/52, but original authored raw classification falls 27/30 -> 19/30. The model fails preservation requirements and must not replace the existing proposal path.

One warmed, rotated host run (192 texts/batch, 64 each Arabic/English/mixed, eight raw batches) measured rich prior ~19.5 us/text and separate classifier ~14.9 us/text; the gate experiment measured ~19.6 us vs ~20.8 us with its added head. Encoding/inference/score consumption are included; construction and I/O are excluded. Raw batch means are versioned. These are shared-host observations, not p95, heap, cold-start, Android or physical-device benchmarks. Model parameter counts exclude the encoder vocabulary and object overhead.

## Reproduction and evidence

```bash
python3 scripts/prepare_massive_refusal.py --verify
python3 scripts/test_massive_refusal.py
bash scripts/train_public_refusal.sh
bash scripts/train_public_decision.sh
bash scripts/prepare_neural_android_fixtures.sh build/public-android-fixtures
```

Training defaults to real JDK `--release 8`. A host without the Java 8 release signature supports `--source-target-only`; this weaker local check is explicitly distinguished from strict CI. `--verify-artifacts` reuses generated outputs and checks them without fitting again. Full runs fit and compare newly exported and committed metadata, all numeric scores within 2e-6, exact typed decisions and exact dense/sparse inference on 3,930 observations per checkpoint. Sigmoid folding differs from calibrated binary softmax by at most 4.77e-8 on those observations. Actual weights, reports and data/protocol hashes are in `neural-core/checkpoints/public-{refusal,decision}-v1`.

To reproduce the subset, fetch the original archive URL recorded in provenance, verify its archive hash, extract only `1.1/data/ar-SA.jsonl`, `1.1/data/en-US.jsonl`, `1.1/LICENSE`, `1.1/NOTICE.md` into one local directory, then pass that directory to `prepare_massive_refusal.py`. Reimport is deterministic; normal CI requires no source network access. Do not execute dataset-supplied scripts.

The isolated Android test APK additionally packages both checkpoints and attribution. Preserve the existing 139 regression probes, and add 699 public probes (the same 139 inputs plus all 560 new upstream test utterances). Host/ART checks feature hashes, calibrated scores, typed decisions, reject semantics and exact dense/sparse consistency. Host corrupt-fixture cases cover changed hashes, missing rows and forged statuses. The harness requires fresh report identity plus `public_parity_passed` and exactly 699 public probes. Existing six-mode Android descriptive timings are unchanged; no new phone timing is claimed.

Locally: four candidate fits, full exported/committed parity, 53 actual JUnit tests (seven added), 12 public provenance tests, prior 7+8 data guards, four emulator-cleanup tests, legacy training/export and host Android-fixture verification passed using source/target 8. New strict release-8, Gradle/JUnit and ART results must come from the PR checks. No physical Infinix test was performed.

## Next quality step

The independent data exposes a weak proposal model, not merely a retry issue. A next experiment should separately preserve the existing known-domain classifier (for example through calibrated residual adaptation or measured distillation) while learning additional Arabic support and out-of-scope rejection. Freeze a new protocol and reserve untouched utterance groups before selecting that candidate. Measure individual known-case retention, rejection, coverage and whole-text cost together. Do not repeatedly tune these now-observed tests, hide known regressions behind selective accuracy, or activate either checkpoint merely because compilation passes.
