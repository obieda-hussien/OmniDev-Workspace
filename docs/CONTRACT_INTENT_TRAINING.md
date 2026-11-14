# Source-anchored intent coverage experiment

Broader supported training examples improve several measured outcomes at the same 6,150-parameter expert size. The selected protected policy still loses three earlier-correct old cases and one new authored case. It remains **ineligible for production**. Normal app dependencies, permissions/flavors, provider routing, Room and P0/P2/P5/P6 authority are unchanged.

## Why change the data?

The preceding trust experiment exposed only 39 keep counterfactuals and no mixed-language keep stratum in its initial training source. Repeatedly changing a selector cannot establish missing language/task coverage. Public Arabic tool-use data was investigated: the [Arabic Function Calling dataset card](https://huggingface.co/datasets/HeshamHaroon/Arabic_Function_Calling/blob/main/README.md) explicitly describes GPT-4o-generated data and domains such as travel, finance and utilities. Those examples cannot simply be relabelled as Git/Gradle positives. No external rows from that dataset are imported here.

This revision adds deliberately authored examples tied to actual Omni source definitions, and tests a data change while keeping the representation, optimizer and runtime fixed. Source anchors establish definition presence and annotation provenance; they **do not prove execution, postconditions, permissions or independent human review**.

## Frozen authored corpus

`fixtures/contract-intents/corpus.tsv` contains 216 rows: 72 scenarios, each with English, Egyptian-style Arabic and mixed variants. Each of six labels has 12 scenarios: six train, two development, two calibration and two test. Thus the new partitions are 108/36/36/36 rows; the test contains 30 supported and six refusal rows.

Tool-related labels reference pinned source lines in AgentRuntimeTool, GitHubManagerTool, BuildDoctorTools, AdvancedSystemTools, WebSearchTool or MemoryManager. Chat and refusal labels are explicit experimental annotations. The narrow five-domain ontology does not determine which real product capabilities are permitted.

The protocol, corpus, provenance and preparation code were committed before the first fit. All variants of one scenario retain a partition, label and source anchor. Validation rejects normalized duplicates against the older corpora/reservations, missing class/language strata, mismatched anchors and changed source hashes. Similar task families can exist in older data: this is a new authored wording challenge, **not semantic-OOD evaluation or independently labelled user traffic**. After this scoring, its test rows must be treated as observed for future revisions.

## Training and selection

Add only the 108 new train rows to the existing 2,162 rows, producing 2,270 training rows. The existing 1,024-dimensional rich-text-v1 representation, vocabulary and IDF remain frozen. Balance the 18 language/class strata to 12,564 examples per seeded shuffle.

Reuse `DistilledAdamTrainer`: exactly four new mini-batch AdamW fits, linear or tanh16, seeds 42/97, with its existing learning rate, clipping, smoothing, correct-teacher distillation and bounded early stopping. Supported, correctly classified new authored training rows receive the same teacher anchor as earlier authored rows; teacher mistakes are not distilled as truth. All four select epoch 5 and run 20 epochs. No held-out scores select weights.

Add the new development/calibration partitions only to their matching existing partitions. Select temperature independently on calibration. Reuse `ExpertPolicyTrainer` and its teacher-correct TRAIN/DEVELOPMENT retention constraints. Choose development policy utility first, then smaller parameter count, NLL and fixed fit order. Retune the frozen utility-v2 network under the same calibration/policy process as an explicit control.

| Candidate | Parameters | Temperature | Development policy utility |
|---|---:|---:|---:|
| New linear seed42 | 6,150 | .5 | .703666 |
| New linear seed97 | 6,150 | .5 | **.709696** |
| New tanh16 seed42 | 16,502 | 1.5 | .666708 |
| New tanh16 seed97 | 16,502 | 1.5 | .657168 |
| Retuned frozen utility-v2 control | 6,150 | 1 | .641769 |

Selected: new linear seed97, temperature .5. Reject cutoffs [.5,.7,.9,.8,.6], change [.99,.8,1.1,1.1,.8], protective domain support [.6,1.1,1.1,1.1,.5]. The expert and total frozen-teacher-plus-expert weight counts remain 6,150 and 20,643. Raw FP32 weight storage is 82,572 bytes, excluding vocabulary, objects and temporary allocations; no peak-heap measurement is claimed.

## Measured outcomes and failed gates

Both network and policy sources are exported before the new test rows are scored. The old evaluation now contains 1,542 held-out observations: the prior 1,462 plus the previous 80 public negatives. There are 399 supported and 1,143 required-refusal rows. This denominator differs from earlier reports that did not yet include those 80 rows.

| Old observed subset | Teacher | Original utility-v2 | New protected | Raw diagnostic |
|---|---:|---:|---:|---:|
| Accepted-correct /399 supported | 233 | 253 | **257** | 318 |
| Accepted errors /1,542 total | 666 | 414 | **378** | 127 |
| Unsupported refused /1,143 | 548 | 772 | **804** | 1,021 |
| Earlier-correct supported cases lost | 0 | 4 | **3** | **29** |
| Earlier teacher abstentions upgraded | 0 | 0 | **0** | **115** |

The protected policy preserves the exact original teacher refusal and caller confidence/margin. Its losses are a mixed device storage/display composition and two public English examples. The raw six-class head is a diagnostic comparison only: it upgrades prior refusals and loses 29 earlier-correct cases. Its larger aggregate gains do not make it an eligible replacement or authorize any tool.

| New authored language subset (10 supported +2 refusal each) | Teacher correct | Original utility-v2 correct | New protected correct | Raw diagnostic correct | New protected refused /2 | New protected earlier-correct losses |
|---|---:|---:|---:|---:|---:|---:|
| Arabic | 5 | 5 | **6** | 7 | 1 | 0 |
| English | 1 | 1 | **3** | 6 | 1 | 0 |
| Mixed | 3 | 2 | **5** | 5 | 1 | **1** |

The mixed AAR metadata check remains falsely refused. Counts are small and authored by the same engineering process; they do not establish broad bilingual accuracy. Reports retain all loss identities and `old_observed_retention_passed:false`, `new_authored_retention_passed:false`, `all_known_retention_passed:false`, `production_eligible:false`. No test-based repair follows this evaluation.

## Integration and verification

No new production runtime class or model path is added. The experiment reuses `UtilityExpertDecisionModel` for protected proposals and `RichTextDecisionModel` for raw diagnostics. The existing utility policy trainer helper gains package visibility for reuse. Network/policy factories are separately exported and paired by the complete runtime fingerprint, and their temperatures/cutoff accessors are checked for equality.

Local checks performed with the available JVM and Java8 source/target compilation:

- Existing 80 JUnit runtime tests and 73 Python data/provenance/cleanup tests, including eleven new source/annotation/contamination tests.
- Four actual fits plus the retuned frozen control; exported and committed checkpoints verified on **4,597 observations** with feature-independent metadata pairing, numeric checks, exact dense/sparse and shared/unshared decisions, caller thresholds and original teacher-refusal preservation.
- Actual teacher-correct constraints checked on precisely the original/new TRAIN and DEVELOPMENT rows.
- Original numerical/text/refusal training tests and utility-v2 four-fit/export regression checks remain passing.
- Standalone Android test packaging adds **1,366 cases**, including all previous 1,150 trust probe inputs and all 216 authored rows. The real packaged contract passes on the host alongside the existing 139/699/859/995/1,070/1,150 suites and 26 corrupted-fixture rejections.

The generated factories live only in `androidTest` for the opt-in neural test APK. Fresh ART reports must include `contract_parity_passed` and exactly 1,366 probes. A source anchor cannot impersonate execution evidence; tests reject that claim in the provenance manifest. Strict Java8 API checks, full Gradle/flavor builds and API30 ART/UI verification are delegated to this PR's CI and reported separately. No physical-device verification was performed.

A descriptive first-run host benchmark over 144 fixed training inputs, 512 warm-ups/mode and eight rotating batches reported median batch means of 22.71µs teacher, 24.76µs original utility-v2 and 24.64µs new protected policy, including encoding. Raw batches remain in the report. These noisy host measurements are not phone performance, peak heap or a reliable speedup claim.

## Reproduce

With a full JDK17:

```sh
python3 scripts/test_contract_intents.py
bash scripts/train_contract_intents.sh
bash scripts/prepare_neural_android_fixtures.sh /tmp/omni-neural-fixtures
./gradlew :neural-core:test
```

`--source-target-only` is a constrained-host fallback; CI uses strict `--release 8`. CI uploads the generated factories, complete observations and quality failures. This is measurable data-coverage progress, not P9 completion. Independent supported and mixed-language labels, stronger refusal coverage, the remaining individual regressions and physical-device validation are still required before production consideration.
