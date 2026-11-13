"""Verify the actual reserved-test guards and committed model provenance."""
import copy
import hashlib
import json
import unittest
from prepare_fresh_intent_eval import ROOT, CORPUS, DEST, canonical, read, validate_rows, verify


class RichTextDataTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows = read(DEST / 'fresh-english.tsv')
        cls.blocked = {canonical(r['text']) for r in read(CORPUS)}

    def test_frozen_source_and_reservation_provenance(self):
        verify()
        self.assertEqual(len(self.rows), 114)

    def test_old_text_cannot_impersonate_fresh_evaluation(self):
        rows = copy.deepcopy(self.rows); rows[0]['text'] = read(CORPUS)[0]['text']
        with self.assertRaisesRegex(ValueError, 'contamination'):
            validate_rows(rows, self.blocked)

    def test_duplicate_source_identity_is_rejected(self):
        rows = copy.deepcopy(self.rows); rows[1]['id'] = rows[0]['id']
        with self.assertRaisesRegex(ValueError, 'contamination'):
            validate_rows(rows, self.blocked)

    def test_new_ids_cannot_hide_normalized_duplicates(self):
        rows = copy.deepcopy(self.rows); rows[1]['text'] = rows[0]['text'].upper()
        with self.assertRaisesRegex(ValueError, 'contamination'):
            validate_rows(rows, self.blocked)

    def test_upstream_training_rows_are_not_fresh_tests(self):
        rows = copy.deepcopy(self.rows); rows[0]['id'] = 'clinc-train-0'
        with self.assertRaisesRegex(ValueError, 'contamination'):
            validate_rows(rows, self.blocked)

    def test_domain_mapping_cannot_be_changed(self):
        rows = copy.deepcopy(self.rows); rows[0]['expected_domain'] = 'repository'
        with self.assertRaisesRegex(ValueError, 'remapping'):
            validate_rows(rows, self.blocked)

    def test_versioned_weights_and_protocol_are_identified(self):
        directory = ROOT / 'neural-core/checkpoints/rich-text-v1'
        report = json.loads((directory / 'report.json').read_text())
        self.assertFalse(report['production_eligible'])
        self.assertEqual(report['fused_parameters'], 5125)
        for path, expected in report['artifact_sha256'].items():
            self.assertEqual(expected, hashlib.sha256((ROOT / path).read_bytes()).hexdigest())


if __name__ == '__main__':
    unittest.main()
