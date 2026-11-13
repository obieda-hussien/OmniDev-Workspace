"""Exercise the actual unseen-group reservation and data/protocol hashes."""
import copy
import hashlib
import json
import unittest
from reserve_residual_eval import DEST, ROOT, read, blocked, validate, verify


class ResidualDataTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows = read(DEST / 'fresh.tsv')

    def rejects(self, mutate):
        rows = copy.deepcopy(self.rows); mutate(rows)
        with self.assertRaises(ValueError):
            validate(rows)

    def test_source_hashes_and_no_fresh_positive_claim(self):
        verify(); self.assertEqual(len(self.rows), 160)
        self.assertTrue(all(r['expected_domain'] == '-' for r in self.rows))

    def test_old_source_id_is_not_fresh(self):
        self.rejects(lambda r: r[0].update(source_id=next(iter(blocked()[1]))))

    def test_old_text_is_not_fresh(self):
        self.rejects(lambda r: r[0].update(text=next(iter(blocked()[0]))))

    def test_upstream_training_cannot_impersonate_test(self):
        self.rejects(lambda r: r[0].update(source_partition='train'))

    def test_translation_cannot_be_removed(self):
        self.rejects(lambda r: r.pop(0))

    def test_mapping_cannot_change(self):
        self.rejects(lambda r: r[0].update(expected_domain='device'))

    def test_duplicate_cannot_be_hidden_behind_another_id(self):
        self.rejects(lambda r: r[2].update(text=r[0]['text']))

    def test_committed_weights_and_sources_are_identified(self):
        p = ROOT / 'neural-core/checkpoints/residual-adapter-v1/report.json'
        self.assertTrue(p.exists()); report = json.loads(p.read_text()); self.assertFalse(report['production_eligible'])
        self.assertEqual(report['fresh_supported_rows'], 0)
        for path, expected in report['artifact_sha256'].items():
            self.assertEqual(hashlib.sha256((ROOT/path).read_bytes()).hexdigest(), expected)


if __name__ == '__main__':
    unittest.main()
