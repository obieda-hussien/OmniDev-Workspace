"""Documentation-only support export with translation grouping and no implicit code/data ingestion."""
import tempfile
import unittest
from pathlib import Path
import build_product_knowledge as knowledge


class ProductKnowledgeTest(unittest.TestCase):
    def test_checked_in_export_matches_reviewed_documentation(self):
        knowledge.export(verify=True)
        records = knowledge.records()
        self.assertEqual(len(records), 58)
        self.assertTrue(all(r['runtime_execution_evidence'] is False for r in records))
        groups = {}
        for row in records:
            groups.setdefault(row['group'], set()).add(row['language'])
        self.assertTrue(all(languages == {'ar', 'en'} for languages in groups.values()))

    def test_code_chat_and_unlisted_paths_are_rejected(self):
        for path in ['README.md', 'app/src/main/java/Private.kt', 'chat.db', '../messages.md', '/tmp/private.md']:
            with self.assertRaises(ValueError):
                knowledge.records(source=Path(path))

    def test_code_fences_or_missing_translation_cannot_be_exported(self):
        original = (knowledge.ROOT / knowledge.SOURCE).read_text()
        for text in [original + '\n```kotlin\nprivate class Secret\n```', original.replace('Question-ar:', 'Question-missing:', 1), original.replace('Question-en: How', 'Question-en: عربي How', 1)]:
            with tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                path = root / knowledge.SOURCE
                path.parent.mkdir(parents=True)
                path.write_text(text)
                with self.assertRaises(ValueError):
                    knowledge.records(root=root)


if __name__ == '__main__':
    unittest.main()
