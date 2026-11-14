"""Public source groups, negative-only disclosure, contamination and immutable failed-quality evidence."""
import copy
import hashlib
import json
import unittest
from reserve_trust_eval import ROOT, DEST, TOPICS, blocked, read, validate, verify


class TrustDataTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows = read(DEST / 'fresh.tsv')

    def reject(self, change):
        rows = copy.deepcopy(self.rows)
        change(rows)
        with self.assertRaises(ValueError):
            validate(rows)

    def test_pinned_parallel_official_test_negatives(self):
        verify()
        self.assertEqual(len(self.rows), 80)
        self.assertTrue(all(r['source_partition'] == 'test' and r['expected_domain'] == '-' for r in self.rows))
        self.assertEqual(len({r['source_id'] for r in self.rows}), 40)

    def test_old_source_identity_is_rejected(self):
        self.reject(lambda rows: rows[0].update(source_id=next(iter(blocked()[1]))))

    def test_old_text_is_rejected(self):
        self.reject(lambda rows: rows[0].update(text=next(iter(blocked()[0]))))

    def test_new_identity_cannot_hide_duplicate_text(self):
        self.reject(lambda rows: rows[2].update(text=rows[0]['text']))

    def test_positive_or_training_partition_claim_is_rejected(self):
        self.reject(lambda rows: rows[0].update(expected_domain='device'))
        self.reject(lambda rows: rows[0].update(source_partition='train'))

    def test_missing_translation_and_changed_topic_are_rejected(self):
        self.reject(lambda rows: rows.pop())
        self.reject(lambda rows: rows[0].update(intent='general_joke'))

    def test_checkpoint_manifest_keeps_the_known_failure(self):
        report = json.loads((ROOT / 'neural-core/checkpoints/expert-trust-v1/report.json').read_text())
        self.assertFalse(report['production_eligible'])
        self.assertFalse(report['observed_retention_passed'])
        self.assertEqual(report['evaluations']['observed_holdout']['trust']['previous_correct_lost'], 2)
        for language in ['ar', 'en']:
            self.assertEqual(report['evaluations']['new_negative_' + language]['known'], 0)
        for path, expected in report['artifact_sha256'].items():
            self.assertEqual(hashlib.sha256((ROOT / path).read_bytes()).hexdigest(), expected)


if __name__ == '__main__':
    unittest.main()
