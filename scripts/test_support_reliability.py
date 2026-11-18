import copy
import json
import unittest
import prepare_support_reliability as data


class ReservationTests(unittest.TestCase):
    def setUp(self):
        self.rows = data.read(data.FRESH)
        self.protocol = json.loads(data.PROTOCOL.read_text())

    def rejected(self):
        with self.assertRaises(ValueError):
            data.validate(self.rows, self.protocol)

    def test_valid_frozen_data_and_disjoint_calibration(self):
        rows, temp, decision = data.validate()
        self.assertEqual(129, len(rows))
        self.assertFalse(set(temp) & set(decision))

    def test_missing_row(self):
        self.rows.pop(); self.rejected()

    def test_duplicate_identity(self):
        self.rows[1]['id'] = self.rows[0]['id']; self.rejected()

    def test_unknown_primary(self):
        self.rows[0]['primary'] = 'invented'; self.rejected()

    def test_translation_conflict(self):
        self.rows[0]['primary'] = 'voice-wake'; self.rejected()

    def test_observed_query(self):
        self.rows[0]['query'] = data.read(data.FIXTURES / 'product-support/queries.tsv')[0]['query']
        self.rejected()

    def test_wrong_source_hash(self):
        self.protocol['source_sha256'] = '0' * 64; self.rejected()

    def test_promotion_claim(self):
        self.protocol['production_eligible'] = True; self.rejected()

    def test_wrong_language(self):
        self.rows[0]['language'] = 'ar'; self.rejected()


if __name__ == '__main__':
    unittest.main()
