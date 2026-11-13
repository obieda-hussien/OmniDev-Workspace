"""Exercise parallel-group, split, remapping and contamination guards on the real public subset."""
import copy
import hashlib
import json
import unittest
from prepare_massive_refusal import DEST, read, old_texts, validate_rows, verify


class MassiveRefusalTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows = read(DEST / 'corpus.tsv'); cls.blocked = old_texts()

    def reject(self, change):
        rows = copy.deepcopy(self.rows); change(rows)
        with self.assertRaises(ValueError):
            validate_rows(rows, self.blocked)

    def test_source_provenance_and_untouched_subset(self):
        verify(); self.assertEqual(len(self.rows), 2240)

    def test_translations_cannot_cross_fit_and_test(self):
        self.reject(lambda r: r[0].update(split='test'))

    def test_upstream_test_cannot_become_training(self):
        def change(rows):
            for r in rows:
                if r['source_partition'] == 'test':
                    r['split'] = 'train'
        self.reject(change)

    def test_semantic_group_cannot_be_renamed(self):
        self.reject(lambda r: r[0].update(group='massive-foreign'))

    def test_parallel_labels_must_match(self):
        self.reject(lambda r: r[0].update(intent='general_greet', expected_domain='chat'))

    def test_mapping_cannot_be_changed(self):
        self.reject(lambda r: r[0].update(expected_domain='knowledge'))

    def test_test_only_topics_cannot_fit(self):
        self.reject(lambda r: r[0].update(intent='weather_query', expected_domain='-'))

    def test_duplicate_source_identity(self):
        self.reject(lambda r: r.append(dict(r[0])))

    def test_missing_translation_is_not_kept(self):
        self.reject(lambda r: r.pop(0))

    def test_normalized_duplicate_is_rejected(self):
        self.reject(lambda r: r[2].update(text=r[0]['text']))

    def test_old_dataset_cannot_impersonate_public_fresh_test(self):
        self.reject(lambda r: r[0].update(text=next(iter(self.blocked))))

    def test_versioned_artifact_hashes(self):
        from prepare_massive_refusal import ROOT
        for directory in ('public-refusal-v1', 'public-decision-v1'):
            p = ROOT / 'neural-core/checkpoints' / directory / 'report.json'
            self.assertTrue(p.exists())
            r = json.loads(p.read_text()); self.assertFalse(r['production_eligible'])
            for path, expected in r['artifact_sha256'].items():
                self.assertEqual(hashlib.sha256((ROOT / path).read_bytes()).hexdigest(), expected)


if __name__ == '__main__':
    unittest.main()
