"""Authored/source ownership, grouped splits, contamination and truthful checkpoint evidence."""
import copy
import hashlib
import json
import pathlib
import tempfile
import unittest
from unittest import mock
import prepare_contract_intents as corpus


class ContractIntentDataTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows = corpus.read(corpus.DEST / 'corpus.tsv')

    def reject(self, change):
        rows = copy.deepcopy(self.rows)
        change(rows)
        with self.assertRaises(ValueError):
            corpus.validate(rows)

    def bad_manifest(self, change):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            data = json.loads((corpus.DEST / 'provenance.json').read_text())
            change(data)
            (root / 'provenance.json').write_text(json.dumps(data))
            (root / 'corpus.tsv').write_bytes((corpus.DEST / 'corpus.tsv').read_bytes())
            with mock.patch.object(corpus, 'DEST', root), self.assertRaises(ValueError):
                corpus.verify()

    def test_fixed_groups_and_all_class_language_partitions(self):
        corpus.verify()
        self.assertEqual(len(self.rows), 216)
        self.assertEqual(len({r['group'] for r in self.rows}), 72)
        self.assertEqual(sum(r['split'] == 'train' for r in self.rows), 108)
        self.assertEqual(sum(r['split'] == 'test' and r['expected_domain'] != '-' for r in self.rows), 30)

    def test_old_text_cannot_become_new_authored_data(self):
        self.reject(lambda rows: rows[0].update(text=next(iter(corpus.old_texts()))))

    def test_changed_identity_cannot_hide_a_duplicate(self):
        self.reject(lambda rows: rows[3].update(text=rows[0]['text']))

    def test_language_variants_cannot_cross_partitions(self):
        self.reject(lambda rows: rows[0].update(split='test'))

    def test_wrong_domain_source_anchor_is_rejected(self):
        self.reject(lambda rows: rows[0].update(anchor='settings'))

    def test_missing_variant_or_invalid_language_is_rejected(self):
        self.reject(lambda rows: rows.pop())
        self.reject(lambda rows: rows[0].update(language='unknown'))

    def test_definition_anchor_cannot_claim_execution_or_human_annotation(self):
        self.bad_manifest(lambda p: p.update(runtime_execution_verified=True))
        self.bad_manifest(lambda p: p.update(independent_human_annotations=True))

    def test_changed_source_line_or_hash_is_rejected(self):
        self.bad_manifest(lambda p: p['anchors']['runtime'].update(line=1))
        self.bad_manifest(lambda p: p['files'].update({'docs/TOOL_EXECUTION_CONTRACT.md': '0' * 64}))

    def test_manifest_cannot_omit_a_source_pin_or_add_unrelated_files(self):
        self.bad_manifest(lambda p: p['files'].pop('docs/TOOL_EXECUTION_CONTRACT.md'))
        self.bad_manifest(lambda p: p['files'].update({'unrelated-private-file': '0' * 64}))

    def test_checkpoint_manifest_and_failed_retention_flags(self):
        report = json.loads((corpus.ROOT / 'neural-core/checkpoints/contract-intents-v1/report.json').read_text())
        self.assertFalse(report['production_eligible'])
        self.assertFalse(report['old_observed_retention_passed'])
        self.assertFalse(report['new_authored_retention_passed'])
        self.assertFalse(report['all_known_retention_passed'])
        for path, expected in report['artifact_sha256'].items():
            self.assertEqual(hashlib.sha256((corpus.ROOT / path).read_bytes()).hexdigest(), expected)

    def test_diagnostic_upgrades_are_not_confused_with_protected_behavior(self):
        report = json.loads((corpus.ROOT / 'neural-core/checkpoints/contract-intents-v1/report.json').read_text())
        old = report['evaluations']['old_observed']
        self.assertEqual(old['protected']['teacher_abstentions_upgraded'], 0)
        self.assertGreater(old['raw_diagnostic']['teacher_abstentions_upgraded'], 0)
        self.assertEqual(old['protected']['previous_correct_lost'], 3)
        self.assertEqual(len(report['candidates']), 5)
        self.assertTrue(any(r['source'] == 'retuned_frozen_utility_v2' for r in report['candidates']))


if __name__ == '__main__':
    unittest.main()
