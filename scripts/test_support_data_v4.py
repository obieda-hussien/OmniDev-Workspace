"""Exercise real frozen scenario validation, including repinned structural corruption."""
import csv,hashlib,json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import prepare_support_data_v4 as data

class ExpandedDataTest(unittest.TestCase):
    def test_frozen_scenarios_cover_all_topics_and_languages(self):
        rows=data.validate();self.assertEqual(642,len(rows));self.assertEqual(297,sum(q['split']=='train' for q in rows));self.assertEqual(147,sum(q['split']=='fresh' for q in rows))
        self.assertEqual(36,sum(',' in q['primary'] for q in rows))
    def mutate(self,edit=lambda rows:None,repin=True,protocol_edit=lambda p:None):
        with tempfile.TemporaryDirectory() as temp:
            folder=Path(temp)
            for path in data.FIXTURES.iterdir():(folder/path.name).write_bytes(path.read_bytes())
            path=folder/'queries.tsv'
            with path.open() as f:rows=list(csv.DictReader(f,delimiter='\t'))
            edit(rows)
            with path.open('w') as f:
                w=csv.DictWriter(f,fieldnames=['id','group','split','language','primary','secondary','query'],delimiter='\t',lineterminator='\n');w.writeheader();w.writerows(rows)
            p=json.loads((folder/'protocol.json').read_text())
            if repin:p['query_sha256']=hashlib.sha256(path.read_bytes()).hexdigest()
            protocol_edit(p);(folder/'protocol.json').write_text(json.dumps(p))
            with patch.object(data,'FIXTURES',folder):
                with self.assertRaises(ValueError):data.validate()
    def test_unpinned_wording_is_rejected(self):self.mutate(lambda r:r[0].update(query='Entirely different greeting question?'),False)
    def test_foreign_requirement_is_rejected(self):self.mutate(lambda r:r[0].update(primary='invented-product-fact'))
    def test_incomplete_translation_is_rejected(self):self.mutate(lambda r:r[0].update(group='detached-translation'))
    def test_reassigned_translation_cannot_cross_partitions(self):
        def edit(r):r[0]['split'],r[9]['split']=r[9]['split'],r[0]['split']
        self.mutate(edit)
    def test_previous_test_contamination_is_rejected(self):
        with (data.ROOT/'neural-core/fixtures/hybrid-support-v3/fresh.tsv').open() as f:q=next(csv.DictReader(f,delimiter='\t'))['query']
        self.mutate(lambda r:r[0].update(query=q))
    def test_duplicate_identity_is_rejected(self):self.mutate(lambda r:r[0].update(id=r[1]['id']))
    def test_duplicate_wording_is_rejected(self):self.mutate(lambda r:r[0].update(query=r[3]['query']))
    def test_false_review_claim_is_rejected(self):self.mutate(protocol_edit=lambda p:p.update(independent_human_labels=True))
    def test_foreign_document_origin_is_rejected(self):self.mutate(protocol_edit=lambda p:p.update(source='private-chat.jsonl'))
    def test_unknown_labels_cannot_have_secondary_requirements(self):self.mutate(lambda r:r[-1].update(primary='-',secondary='history-titles'))

if __name__=='__main__':unittest.main()
