import csv,hashlib,json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import prepare_query_memory as data
class QueryMemoryDataTest(unittest.TestCase):
 def test_reserved_challenge_and_train_only_memory(self):
  rows,ex=data.validate();self.assertEqual(138,len(rows));self.assertEqual(351,len(ex));self.assertTrue(all(x['group'].startswith(('old:','new:')) for x in ex));self.assertFalse({q['id'] for q in rows}&{q['id'] for q in ex})
 def corrupt(self,edit=lambda r:None,pedit=lambda p:None,repin=True):
  with tempfile.TemporaryDirectory() as temp:
   folder=Path(temp)
   for p in data.FIXTURES.iterdir():(folder/p.name).write_bytes(p.read_bytes())
   p=folder/'fresh.tsv'
   with p.open() as f:rows=list(csv.DictReader(f,delimiter='\t'))
   edit(rows)
   with p.open('w') as f:w=csv.DictWriter(f,fieldnames=['id','group','language','primary','secondary','query'],delimiter='\t',lineterminator='\n');w.writeheader();w.writerows(rows)
   protocol=json.loads((folder/'protocol.json').read_text())
   if repin:protocol['fresh_sha256']=hashlib.sha256(p.read_bytes()).hexdigest()
   pedit(protocol);(folder/'protocol.json').write_text(json.dumps(protocol))
   with patch.object(data,'FIXTURES',folder):
    with self.assertRaises(ValueError):data.validate()
 def test_unpinned_wording(self):self.corrupt(lambda r:r[0].update(query='different question about naming'),repin=False)
 def test_foreign_label(self):self.corrupt(lambda r:r[0].update(primary='invented-fact'))
 def test_translation_label_mismatch(self):self.corrupt(lambda r:r[0].update(primary='history-topics'))
 def test_missing_translation(self):self.corrupt(lambda r:r[0].update(group='detached-translation'))
 def test_observed_contamination(self):
  with (data.ROOT/'neural-core/fixtures/support-data-v4/queries.tsv').open() as f:q=next(csv.DictReader(f,delimiter='\t'))['query']
  self.corrupt(lambda r:r[0].update(query=q))
 def test_false_readiness(self):self.corrupt(pedit=lambda p:p.update(production_eligible=True))
 def test_false_human_review(self):self.corrupt(pedit=lambda p:p.update(independent_human_labels=True))
if __name__=='__main__':unittest.main()
