"""Exercise the real public-data preparation guards without a network or training dependency."""
import copy
import hashlib
import json
import unittest
from prepare_bilingual_refusal import DEST, ROOT, read, validate, verify


class CorpusGuardsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows = read(DEST / 'corpus.tsv')

    def test_frozen_provenance(self):
        verify()

    def test_committed_checkpoint_is_identified_and_not_promoted(self):
        directory = ROOT / 'neural-core/checkpoints/bilingual-refusal-v1'
        report = json.loads((directory / 'report.json').read_text())
        self.assertEqual(report['checkpoint_sha256'], hashlib.sha256((directory / 'TrainedBilingualRefusalCheckpoint.java').read_bytes()).hexdigest())
        self.assertFalse(report['production_eligible'])
        self.assertEqual(len(report['candidates']), 6)

    def test_paraphrases_cannot_cross_splits(self):
        rows = copy.deepcopy(self.rows)
        rows[1]['split'] = 'calibration'
        with self.assertRaisesRegex(ValueError, 'Group leakage'):
            validate(rows)

    def test_composition_cannot_use_a_foreign_parent(self):
        rows = copy.deepcopy(self.rows)
        next(r for r in rows if r['kind'] == 'composed_cross')['parent_a'] = 'original:know-quote'
        with self.assertRaisesRegex(ValueError, 'Composition crosses'):
            validate(rows)

    def test_upstream_test_cannot_enter_fitting(self):
        rows = copy.deepcopy(self.rows)
        next(r for r in rows if r['kind'] == 'unseen_topic')['split'] = 'train'
        with self.assertRaisesRegex(ValueError, 'partition leakage'):
            validate(rows)

    def test_normalized_duplicates_cannot_be_hidden_by_new_ids(self):
        rows = copy.deepcopy(self.rows)
        duplicate = dict(rows[0], group='different-group', text=rows[0]['text'].upper())
        rows.append(duplicate)
        with self.assertRaisesRegex(ValueError, 'Normalized duplicate'):
            validate(rows)

    def test_supported_rows_require_domain_labels(self):
        rows = copy.deepcopy(self.rows); rows[0]['expected_domain'] = '-'
        with self.assertRaisesRegex(ValueError, 'domain mismatch'):
            validate(rows)


if __name__ == '__main__':
    unittest.main()
