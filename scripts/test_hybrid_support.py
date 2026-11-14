"""Frozen compound labels are not replacements for the observed single-topic benchmarks."""
import csv,hashlib,json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import prepare_hybrid_support as hybrid
class HybridDataTest(unittest.TestCase):
    def test_frozen_compound_translations_and_requirements(self):
        rows=hybrid.validate();self.assertEqual(108,len(rows));self.assertEqual(36,sum(',' in q['primary'] for q in rows));self.assertEqual(36,sum(q['primary']=='-' for q in rows))
        groups={}
        for q in rows:groups.setdefault(q['group'],set()).add(q['language'])
        self.assertTrue(all(x=={'ar','en','mixed'} for x in groups.values()))
    def mutate(self,edit,repin=False,change=None):
        with tempfile.TemporaryDirectory() as name:
            folder=Path(name)
            for p in hybrid.FIXTURES.iterdir():(folder/p.name).write_bytes(p.read_bytes())
            p=folder/'fresh.tsv';p.write_text(edit(p.read_text()));protocol=folder/'protocol.json';r=json.loads(protocol.read_text())
            if repin:r['fresh_sha256']=hashlib.sha256(p.read_bytes()).hexdigest()
            if change:change(r)
            protocol.write_text(json.dumps(r))
            with patch.object(hybrid,'FIXTURES',folder):
                with self.assertRaises(ValueError):hybrid.validate()
    def test_unpinned_wording_edit_fails(self):self.mutate(lambda s:s.replace('I linked GitHub Models','I configured GitHub Models',1))
    def test_translation_requirement_change_fails_after_repin(self):self.mutate(lambda s:s.replace('github-access,git-environment','github-access',1),True)
    def test_foreign_requirement_fails_after_repin(self):self.mutate(lambda s:s.replace('github-access,git-environment','nonexistent-fact',1),True)
    def test_observed_contamination_fails(self):
        with (hybrid.ROOT/'neural-core/fixtures/product-support/queries.tsv').open() as source:old=list(csv.DictReader(source,delimiter='\t'))[0]['query']
        def edit(s):
            lines=s.splitlines();q=lines[1].split('\t');q[-1]=old;lines[1]='\t'.join(q);return '\n'.join(lines)+'\n'
        self.mutate(edit,True)
    def test_false_independence_claim_fails(self):self.mutate(lambda s:s,change=lambda r:r.update(independent_human_labels=True))
if __name__=='__main__':unittest.main()
