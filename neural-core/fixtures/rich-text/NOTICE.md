# Reserved additional English evaluation

Utterances are unchanged public CLINC150 text from https://github.com/clinc/oos-eval,
revision `828f8093932c8fe6ca7936c3d2e52903b1c523de`, `data/data_full.json`.
The full Creative Commons Attribution 3.0 Unported license and author/paper attribution
are retained in [the existing subset](../clinc-subset/NOTICE.md) and
[its license](../clinc-subset/LICENSE).

This adaptation selects previously unused test utterances and maps supported greeting,
thanks/jokes and media-volume intents to chat/device. The other eight selected intents
require refusal under this narrow classifier contract, which does not limit Omni's
overall capabilities. It is not the standard CLINC150 benchmark or an author endorsement.

Before richer-model fitting, the last ten upstream test positions (20-29) per mapped
intent were reserved. Exact normalized duplicates against all previous 1,551 rows and
old challenges are excluded, without consulting model scores. Six exclusions leave 114
rows: 34 supported, 80 unsupported. Existing topic categories have been seen in prior
evaluation; the new evidence is unused utterances, not entirely new unknown categories.
All data are English and no claim of representative Arabic evaluation follows.

`provenance.json` pins source/corpus/protocol hashes, exclusions and counts. Reproduce
using an explicit local upstream checkout with no network calls:

```bash
python3 scripts/prepare_fresh_intent_eval.py /path/to/pinned/oos-eval
python3 scripts/prepare_fresh_intent_eval.py --verify
```

Model/policy selection uses only the prior fitting/development/calibration partitions.
Additional test scores are read after final export. Repeated verification of the same
frozen model/data is a regression check, not a new independent test.
