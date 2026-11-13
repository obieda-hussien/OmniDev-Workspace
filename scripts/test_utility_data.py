"""Verify actual held-out-source provenance, positive partition disclosure and committed initial/follow-up artifacts."""
import copy
import hashlib
import json
import unittest
from reserve_utility_eval import ROOT, DEST, blocked, read, validate, verify


class UtilityDataTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows=read(DEST/'fresh.tsv')

    def reject(self,change):
        rows=copy.deepcopy(self.rows);change(rows)
        with self.assertRaises(ValueError):validate(rows)

    def test_reservation_and_positive_partition_disclosure(self):
        verify();self.assertEqual(len(self.rows),136)
        positives=[r for r in self.rows if r['expected_domain']!='-'];self.assertEqual(len(positives),56)
        self.assertTrue(all(r['source_partition']=='train' and r['expected_domain']=='device' for r in positives))

    def test_old_source_id_is_rejected(self):
        self.reject(lambda r:r[0].update(source_id=next(iter(blocked()[1]))))

    def test_old_text_is_rejected(self):
        self.reject(lambda r:r[0].update(text=next(iter(blocked()[0]))))

    def test_official_test_claim_cannot_impersonate_reserved_train_positive(self):
        self.reject(lambda r:r[0].update(source_partition='test'))

    def test_pair_cannot_be_removed(self):
        self.reject(lambda r:r.pop(0))

    def test_label_cannot_be_changed(self):
        self.reject(lambda r:r[0].update(expected_domain='chat'))

    def test_new_identity_cannot_hide_normalized_duplicate(self):
        self.reject(lambda r:r[2].update(text=r[0]['text']))

    def test_initial_and_followup_artifacts_and_failure_flags(self):
        for version in ['utility-expert-v1','utility-expert-v2']:
            r=json.loads((ROOT/'neural-core/checkpoints'/version/'report.json').read_text());self.assertFalse(r['production_eligible'])
            for path,expected in r['artifact_sha256'].items():self.assertEqual(hashlib.sha256((ROOT/path).read_bytes()).hexdigest(),expected)


if __name__=='__main__':unittest.main()
