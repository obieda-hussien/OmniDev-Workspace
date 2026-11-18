"""Regression checks for the operation-scoped reference cards."""
import unittest

from catalog_advisor import CatalogAdvisor, load_cards
from model import HERE, Model


class DefensiveCatalogTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cards = load_cards()
        cls.advisor = CatalogAdvisor(Model(HERE / "weights.npz"), cls.cards,
                                     HERE / "catalog-advisor.npz")

    def test_projections_match_exact_operations(self):
        cases = [
            ("android_security_research", "list_apps", "Inventory installed user apps for a defensive audit.", []),
            ("security_analyzer", "quick_scan", "Check the overall permission risk score of one Android app.",
             ["package_name"]),
            ("install_tool", "status", "Show the status of the installer and its locally cached packages.", []),
        ]
        available = {f"{name}/{action}" for name, action, _, _ in cases}
        for name, action, query, parameters in cases:
            with self.subTest(tool=name):
                result = self.advisor.reference_cards(query, available, limit=1)
                self.assertEqual(result[0]["tool"], name)
                self.assertEqual(result[0]["fixed_arguments"], {"action": action})
                self.assertEqual([p["name"] for p in result[0]["parameters"]], parameters)
                self.assertEqual(self.advisor.reference_cards(query, {name, f"{name}/unknown"}), [])

    def test_manifest_inventory_remains_available(self):
        result = self.advisor.reference_cards("Show Android manifest metadata for my app.",
                                              {"enhanced_manifest_analyzer"})
        self.assertEqual(result[0]["tool"], "enhanced_manifest_analyzer")
        self.assertNotIn("enhanced_attack_surface", self.cards)


if __name__ == "__main__":
    unittest.main()
