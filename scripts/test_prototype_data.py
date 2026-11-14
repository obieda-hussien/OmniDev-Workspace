"""Frozen authored challenges, honest quality flags and exported support-bank provenance."""
import copy
import hashlib
import json
import unittest
from prepare_prototype_challenges import ROOT, DEST, validate, verify, read


class PrototypeDataTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows = read(DEST / 'authored.tsv')

    def reject(self, change):
        rows = copy.deepcopy(self.rows)
        change(rows)
        with self.assertRaises(ValueError):
            validate(rows)

    def test_frozen_authored_provenance_and_three_language_groups(self):
        verify()
        self.assertEqual(len({r['group'] for r in self.rows}), 25)
        for language in ['ar', 'en', 'mixed']:
            subset = [r for r in self.rows if r['language'] == language]
            self.assertEqual(len(subset), 25)
            self.assertEqual(sum(r['expected_domain'] != '-' for r in subset), 20)

    def test_duplicate_cannot_hide_behind_new_identity(self):
        self.reject(lambda rows: rows[3].update(text=rows[0]['text']))

    def test_prior_training_text_cannot_enter_fresh_challenges(self):
        old = read(ROOT / 'neural-core/fixtures/massive-refusal/corpus.tsv')[0]
        self.reject(lambda rows: rows[0].update(text=old['text']))

    def test_group_translation_label_conflict_is_rejected(self):
        self.reject(lambda rows: rows[0].update(expected_domain='build'))

    def test_missing_translation_is_rejected(self):
        self.reject(lambda rows: rows.pop())

    def test_invalid_language_or_output_is_rejected(self):
        self.reject(lambda rows: rows[0].update(language='unknown'))
        self.reject(lambda rows: rows[0].update(text='request\tsecret'))

    def test_frozen_failure_flags_and_checkpoint_hashes(self):
        report = json.loads((ROOT / 'neural-core/checkpoints/prototype-support-v1/report.json').read_text())
        self.assertFalse(report['production_eligible'])
        self.assertTrue(report['old_observed_retention_passed'])
        self.assertFalse(report['authored_retention_passed'])
        self.assertFalse(report['all_known_retention_passed'])
        old = report['evaluations']['old_observed']
        self.assertEqual(old['utility_v2']['previous_correct_lost'], 3)
        self.assertEqual(old['prototype']['previous_correct_lost'], 0)
        self.assertEqual(report['evaluations']['authored_mixed']['prototype']['previous_correct_lost'], 1)
        for path, expected in report['artifact_sha256'].items():
            self.assertEqual(hashlib.sha256((ROOT / path).read_bytes()).hexdigest(), expected)


if __name__ == '__main__':
    unittest.main()
