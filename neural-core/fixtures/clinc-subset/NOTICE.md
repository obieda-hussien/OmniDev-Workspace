# CLINC150 subset attribution

Source: https://github.com/clinc/oos-eval
Revision: `828f8093932c8fe6ca7936c3d2e52903b1c523de`.

Stefan Larson, Anish Mahendran, Joseph J. Peper, Christopher Clarke, Andrew Lee,
Parker Hill, Jonathan K. Kummerfeld, Kevin Leach, Michael A. Laurenzano, Lingjia Tang,
and Jason Mars. 2019. *An Evaluation Dataset for Intent Classification and Out-of-Scope
Prediction*. EMNLP-IJCNLP. https://aclanthology.org/D19-1131/

The upstream data is distributed under Creative Commons Attribution 3.0 Unported.
The complete upstream license is retained in LICENSE. Utterance text is unchanged;
this adaptation selects rows, adds original source IDs and maps eight upstream intents
to Omni's narrow domains or explicit rejection. It is not the standard CLINC150 task,
and the labels do not imply Omni cannot handle the rejected topics through other routes.

`provenance.json` records the source hash, revision, mapping and selection exclusions.
Reproduce from an explicitly supplied local upstream checkout with:

```
python3 scripts/import_clinc_rejection.py /path/to/oos-eval neural-core/fixtures/clinc-subset neural-core/fixtures/text-intents.tsv
```

Selection is fixed by intent and original row order, never model scores: at most 25
train rows per intent, first five validation rows for development, the next five
for calibration, and ten test rows per intent. Normalized duplicates against the
original authored corpus or earlier selected rows are excluded before selection.
Development does not borrow from the calibration range. Original upstream partition
IDs are preserved; no official test row is used in fitting or model selection.

All upstream utterances are English. Arabic/mixed-language data remains the older
authored fixture and its previously observed regression tests; no fresh independent
Arabic accuracy claim is supported. This small selected subset is not an endorsement
by Clinc or the paper authors.
