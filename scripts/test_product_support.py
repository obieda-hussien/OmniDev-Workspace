"""Frozen topic/translation split, data-origin and contamination guards."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import prepare_product_support as support

class SupportDataTest(unittest.TestCase):
    def test_real_corpus_and_disjoint_groups(self):
        docs, queries = support.validate()
        self.assertEqual(58, len(docs)); self.assertEqual(234, len(queries))
        self.assertTrue(all(d['source_doc'] == 'docs/OMNI_PRODUCT_KNOWLEDGE.md' for d in docs))
        self.assertTrue(all(d['runtime_execution_evidence'] is False for d in docs))
        groups = {}
        for row in queries:
            groups.setdefault(row['group'], set()).add(row['split'])
        self.assertTrue(all(len(x) == 1 for x in groups.values()))

    def mutated(self, change, repin=False):
        with tempfile.TemporaryDirectory() as name:
            folder=Path(name)
            for path in support.FIXTURES.iterdir():
                (folder/path.name).write_bytes(path.read_bytes())
            path=folder/'queries.tsv'; original=path.read_text();path.write_text(change(original))
            if repin:
                p=folder/'protocol.json';r=json.loads(p.read_text());r['query_sha256']=hashlib.sha256(path.read_bytes()).hexdigest();p.write_text(json.dumps(r))
            with patch.object(support, 'FIXTURES', folder):
                with self.assertRaises(ValueError):support.validate()

    def test_unpinned_data_edit_is_rejected(self):
        self.mutated(lambda t:t.replace('How does Omni name', 'How would Omni name', 1))

    def test_cross_split_topic_translation_is_rejected_after_repin(self):
        def edit(text):
            lines=text.splitlines();r=lines[1].split('\t');r[2]='test' if r[2]!='test' else 'train';lines[1]='\t'.join(r);return '\n'.join(lines)+'\n'
        self.mutated(edit, True)

    def test_duplicate_normalized_query_is_rejected_after_repin(self):
        def edit(text):
            lines=text.splitlines();a=lines[1].split('\t');b=lines[2].split('\t');b[-1]=a[-1];lines[2]='\t'.join(b);return '\n'.join(lines)+'\n'
        self.mutated(edit, True)

    def test_missing_or_wrong_topic_label_is_rejected_after_repin(self):
        self.mutated(lambda t:t.replace('history-titles\tHow does', 'secret-code\tHow does',1),True)

if __name__=='__main__':unittest.main()
