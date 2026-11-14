"""The new challenge stays separate from observed questions and all fitting partitions."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import prepare_ranked_support as ranked

class RankedDataTest(unittest.TestCase):
    def test_real_challenge_has_no_observed_duplicate_and_grouped_languages(self):
        rows=ranked.validate();self.assertEqual(117,len(rows));self.assertTrue(all(r['split']=='test' for r in rows))
        self.assertEqual(87,sum(r['expected_topic']!='-' for r in rows))
        groups={}
        for row in rows:groups.setdefault(row['group'],set()).add(row['language'])
        self.assertTrue(all(v=={'ar','en','mixed'} for v in groups.values()))

    def mutate(self,edit,repin=False,change_protocol=None):
        with tempfile.TemporaryDirectory() as name:
            folder=Path(name)
            for path in ranked.FIXTURES.iterdir():(folder/path.name).write_bytes(path.read_bytes())
            path=folder/'fresh.tsv';path.write_text(edit(path.read_text()))
            p=folder/'protocol.json';r=json.loads(p.read_text())
            if repin:r['fresh_query_sha256']=hashlib.sha256(path.read_bytes()).hexdigest()
            if change_protocol:change_protocol(r)
            p.write_text(json.dumps(r))
            with patch.object(ranked,'FIXTURES',folder):
                with self.assertRaises(ValueError):ranked.validate()

    def test_unpinned_mutation_fails(self):self.mutate(lambda s:s.replace('automatically extracted','automatically selected',1))
    def test_fitting_split_in_new_challenge_fails_even_after_repin(self):self.mutate(lambda s:s.replace('\ttest\t','\ttrain\t',1),True)
    def test_duplicate_or_wrong_language_fails_even_after_repin(self):
        def duplicate(s):
            lines=s.splitlines();a=lines[1].split('\t');b=lines[2].split('\t');b[-1]=a[-1];lines[2]='\t'.join(b);return '\n'.join(lines)+'\n'
        self.mutate(duplicate,True)
        self.mutate(lambda s:s.replace('\ten\t','\tmixed\t',1),True)
    def test_observed_question_contamination_is_rejected_after_repin(self):
        import csv
        with (ranked.ROOT/'neural-core/fixtures/product-support/queries.tsv').open() as source:old=list(csv.DictReader(source,delimiter='\t'))[0]['query']
        def edit(s):
            lines=s.splitlines();row=lines[1].split('\t');row[-1]=old;lines[1]='\t'.join(row);return '\n'.join(lines)+'\n'
        self.mutate(edit,True)

    def test_false_independent_review_claim_fails(self):self.mutate(lambda s:s,change_protocol=lambda r:r.update(independent_human_labels=True))

if __name__=='__main__':unittest.main()
