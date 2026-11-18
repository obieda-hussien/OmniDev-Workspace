"""Checks the informational interface independently of operation ranking."""
import json
import unittest

from catalog_advisor import CatalogAdvisor, load_cards
from explain_tool import CARDS, ToolExplainer
from model import HERE, Model


class ToolExplainerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.encoder = Model(HERE / "weights.npz")
        cls.cards = json.loads(CARDS.read_text(encoding="utf-8"))
        cls.explainer = ToolExplainer(cls.cards, cls.encoder)

    def test_all_names_return_bilingual_metadata(self):
        for name in set(self.cards) - {"UNKNOWN"}:
            with self.subTest(tool=name):
                result, = self.explainer.explain(f"يعني إيه {name}؟")
                self.assertEqual(result["tool"], name)
                self.assertEqual(set(result), {"tool", "summary_ar", "summary_en"})

    def test_paraphrases_and_unrelated_questions(self):
        cases = [
            ("Which tool shows externally visible app components?", "enhanced_attack_surface"),
            ("إيه اللي بيراجع أذونات التطبيق ومخاطر الشبكة؟", "security_analyzer"),
            ("What tool combines several security assessment stages for an Android app?",
             "android_security_research"),
            ("Which catalog entry manages helper-tool installs and the local cache?", "install_tool"),
        ]
        for query, expected in cases:
            with self.subTest(query=query):
                self.assertEqual([r["tool"] for r in self.explainer.explain(query)], [expected])
        for query in ("", "يعني إيه تلخيص المحادثات؟", "Tell me the battery percentage of my phone."):
            self.assertEqual(self.explainer.explain(query), [])

    def test_explanation_does_not_add_execution_candidate(self):
        advisor = CatalogAdvisor(self.encoder, load_cards(), HERE / "catalog-advisor.npz")
        name = "enhanced_attack_surface"
        self.assertTrue(self.explainer.explain(name))
        self.assertEqual(advisor.reference_cards(name, {name}), [])


if __name__ == "__main__":
    unittest.main()
