# Bilingual refusal experiment provenance

Public English utterances come from the CLINC150 data at
https://github.com/clinc/oos-eval, revision
`828f8093932c8fe6ca7936c3d2e52903b1c523de`, under Creative Commons Attribution 3.0
Unported. The complete license and author/paper attribution are retained at
[`../clinc-subset/LICENSE`](../clinc-subset/LICENSE) and
[`../clinc-subset/NOTICE.md`](../clinc-subset/NOTICE.md).
Utterances are unchanged; this adaptation selects additional rows and remaps labels.
This is not the standard CLINC benchmark and does not imply the original authors'
endorsement. Unsupported means outside this experiment's narrow five-domain contract,
not outside Omni's overall capabilities.

Arabic/mixed rows in `authored.tsv` are newly authored synthetic examples, not collected
user data, independent translations, human-reviewed annotations or representative
traffic. Original authored positives retain the earlier scenario-group splits.
Composed examples concatenate same-split, same-language original parents: matching
domains remain supported, different domains require the single-label classifier to
abstain. Repeating the same command in small development/calibration groups is a
deliberate synthetic control. Shared generation templates limit generalization claims.

Additional CLINC rows use original partition IDs. Development takes positions 10-14
per selected intent in upstream validation, calibration takes 15-19, new training
takes up to 25 previously unused rows starting at position 25, and new tests take ten
previously unused rows starting at position 10. Duplicate filtering precedes fitting
and never uses model scores. Old original holdout and prior CLINC tests are marked
`regression` and never enter fitting or selection. Exact old challenge texts are
excluded from additional public sampling. Normalized duplicate filtering does not
guarantee that natural-language paraphrases have been eliminated from CLINC.

Four new English test topics (`weather`, `calendar`, `calculator`, `shopping_list`)
are absent from the selected fitting/selection intents. Their authored translations
are also test-only. This is a small category-held-out diagnostic; it is not proof
against arbitrary unknown topics. The new authored tests were created for this
experiment by the same author as its data generator. Old tests/challenges have
already been observed. Neither supplies an independent human-reviewed Arabic score.

`protocol.json` fixes search, balancing, calibration and cutoff policy before fitting.
`provenance.json` hashes source/corpus/protocol inputs and lists exclusions/counts.
Reproduce from an explicit local upstream checkout, without network calls:

```bash
python3 scripts/prepare_bilingual_refusal.py /path/to/pinned/oos-eval
python3 scripts/test_bilingual_refusal.py
bash scripts/train_bilingual_refusal.sh
```

No personal conversations, private repository content, credentials or model reasoning
are included. Training/checkpoints are experimental and outside the production APK.
