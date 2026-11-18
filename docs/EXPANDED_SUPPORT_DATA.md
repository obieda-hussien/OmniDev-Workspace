# Expanded documentation support scenarios (v4)

More training data is a hypothesis to test, not an automatic quality improvement. This experiment
adds 642 explicitly authored English/Arabic/mixed scenario questions, fits the unchanged v3
representation and trainer, and compares it with a matched old-only training arm. It remains
optional experimental documentation retrieval. It does not generate answers or titles, enable tools,
change permissions, modify P0/P2/P5/P6 or activate weights in the normal APK.

## Data reservation and ownership

The only answer corpus remains `knowledge/omni-product.jsonl`: 58 reviewed bilingual FAQ references,
29 topics, unchanged original text, status and edition constraints. No source-code files, private
conversations, credentials, logs or databases become training text. Implementation hashes bind
provenance, not training content.

New fixture `neural-core/fixtures/support-data-v4/queries.tsv` contains 214 explicitly authored
scenario groups with three separate translations each. These are assistant-authored labels, not
independent human review, production user traffic or thousands of prefix-template combinations.
Scenarios include confusing source/topic filters, account-vs-model access, local-vs-remote Git,
unknown writes, worker evidence, voice/screen prerequisites and unsupported questions with overlapping
words. Twelve new compound groups have two primary FAQ requirements each.

| New partition | Supported | Unsupported | Total |
|---|---:|---:|---:|
| Train | 261 | 36 | 297 |
| Development | 87 | 12 | 99 |
| Calibration | 87 | 12 | 99 |
| Fresh evaluation | 123 | 24 | 147 |
| Total | 558 | 84 | 642 |

Original TRAIN114 is added to the new TRAIN297: 411 training rows. The pairwise objective actually
has usable positive/negative candidate pairs on 343, compared with87 on old-only TRAIN114.
Unknown-label TRAIN rows have zero pairwise loss; their count must not be advertised as learned
refusal examples. Refusal is selected with new CAL, including its12 unsupported queries.

Scenario translations never cross partitions. Every FAQ topic appears in every new partition:
this is scenario/wording generalization over a fixed searchable corpus, **not held-out-topic,
unseen-document, semantic-OOD or independent-intent evaluation**. Related same-topic scenarios
can share vocabulary and meaning across partitions. Guards reject exact normalized overlap with
all459 older queries and canonical FAQ questions; that does not prove semantic independence.
The fixture/protocol were committed locally before fitting and scoring. The fresh147 have now
been observed; never retune repeatedly against them.

## Controlled fitting and selection

Keep `HybridSupportRetriever`, its feature/version contract, eight features and
`HybridSupportExperiment.fit` unchanged. Four projected-SGD fits in each arm use learning rates
.05/.15 and seeds42/97. Both arms share the new DEV99 and CAL99. This matters: comparing only a
newly selected model against frozen v3 would mix training-data changes with selection-data changes.

LEGACY/TITLE/RRF/UNIFORM zero-fit controls compete with each arm's four fits. Selection uses DEV
per-language lexical top-1 floors, then top-1/MRR/parameter count. New CAL selects minimum score,
margin and coverage using correct minus3wrong. Original DEV/CAL/TEST and v2/v3 evaluation probes
are regression data only. Source and policies are exported before new FRESH scoring.

Selected expanded fit: seed42, rate.05, epoch1 (11 run), DEV71/87. Matched old-only: seed42,
rate.15, epoch6 (16 run), DEV72/87. Both choose minimum0, margin0, coverage.5. The expanded
head stores eight fitted coefficients and one fixed zero bias, 36 raw FP32 bytes, excluding
indexes, documents, JVM objects and all other application memory. No capacity or speed increase
is established by expanding the dataset.

## Results, including the negative result

All columns below use the same new authored147 probes: 123 supported,24 unsupported.
Top-1 accepts any primary requirement; all-primary@3 requires every primary requirement in the
first three retrieved topics. A selective acceptance is correct only for its first topic, not
proof all requirements were answered or any user task completed. MRR uses each method's full
candidate ranking; the runtime still returns at most ten topics per call.

| Metric | Lexical | Retained v1 guard | Frozen v3 | Matched old-only | Expanded |
|---|---:|---:|---:|---:|---:|
| First topic correct /123 | 97 | 93 | 103 | 105 | 103 |
| All primary requirements in first3 /123 | 103 | 101 | 108 | 108 | 108 |
| Accepted correct | 56 | 33 | 62 | 62 | 62 |
| Accepted incorrect | 2 | 0 | 2 | 2 | 2 |
| Unsupported refused /24 | 24 | 24 | 24 | 24 | 24 |

| Language (41 supported each) | Lexical top-1 | Frozen v3 | Matched old-only | Expanded |
|---|---:|---:|---:|---:|
| English | 38 | 38 | 38 | 38 |
| Arabic | 24 | 28 | 28 | 28 |
| Mixed | 35 | 37 | 39 | 37 |

Expanded data does not improve over frozen v3 on these headline metrics and trails matched
old-only by two first results. Gains over lexical are already present in v3; do not attribute
them to this data expansion. The two incorrect acceptances (one Arabic,one mixed) exceed the
retained guard's zero. Small authored samples and zero observed unsupported acceptance do not
establish a real-world false acceptance rate. All quality failures remain in quality-provenance.json.

Across all1,101 old/new probes, the compiled candidate matches stored predictions; the unchanged
retained default matches independently for every query. There are19 candidate losses of previously
accepted-correct results, with every ID retained in parity-retention.json. Default behavior stays
frozen v1; `TrainedExpandedSupport.createCandidate` is explicit experimental access only.

The counterfactual supports a limited conclusion: more examples alone did not help this fixed
representation/training protocol on this authored challenge. It does not prove that more diverse,
independently reviewed data is useless, or establish underfitting/overfitting on its own. Primary
background: [scikit-learn's learning-curve guide](https://scikit-learn.org/stable/modules/learning_curve.html)
explains assessing the benefit of extra data through training/validation measurements. This
implementation reuses the Java trainer and does not depend on scikit-learn.

## Reproduction, integration and checks

```sh
python3 scripts/test_support_data_v4.py
bash scripts/train_expanded_support.sh
bash scripts/prepare_neural_android_fixtures.sh /tmp/omni-expanded-fixtures
./gradlew :neural-core:test
```

Use JDK17 for strict Java8 API checks. `--source-target-only` is a local fallback and is not strict
API proof. All training classes compile without generated-checkpoint dependencies. The script fits
eight models, compares reports/checkpoint bytes, then checks compiled prediction/default/source
parity. Data guards exercise hash drift, group/language completeness, contamination, foreign labels,
false readiness/review claims and origin restrictions. The training smoke uses24 finite-difference
coordinates from actual new training rows, partition barriers, query uniqueness and metric parity.

New `ExpandedSupportProbeContract` is packaged in the standalone test APK, alongside older unchanged
contracts. It validates1,101 predictions/defaults, original references and corpus identity; its host
smoke rejects five corrupt fixtures. API30 instrumentation writes expanded-correctness.json and the
harness requires the current run ID. Passing the host contract does not establish ART execution.
CI includes a dedicated refit job, module tests and API30 instrumentation. No app Room migration,
flavor capability, service or provider changes are introduced.

No physical Infinix measurements, battery/peak-heap measurements or phone latency are claimed.
Follow-up should independently review confusing labels, reserve genuinely new scenarios, and
investigate missing/indistinguishable lexical features and selective coverage before further fitting.
Do not promote this checkpoint or alter the existing safety gates to turn the negative result green.

Local verification on 2026-11-14: 95 real JUnit tests, 11 new data guards, 24 actual finite-difference
coordinates, two complete eight-fit reproductions, 1,101 compiled predictions/default comparisons
and 19,856 original-reference checks passed. The packaged host contract also passed and rejected
five corrupt fixtures. This environment used JDK21 with Java8 source/target; strict API compatibility,
Gradle and new ART instrumentation are CI checks, not local device results.
