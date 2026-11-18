import math
import unittest
from dataclasses import replace

from fit_topic_rerank import Hit, Row
from fit_refusal_rescue import calibrate, eligible, evaluate, fit


class RescueTest(unittest.TestCase):
    row = Row('q', 'development', 'ar', frozenset({'a'}), False, (Hit('a', .7, .5, .9),), False)

    def test_only_first_stage_refusals(self):
        self.assertTrue(eligible(self.row))
        for row in (replace(self.row, proposed=True), replace(self.row, accepted=True, proposed=True),
                    replace(self.row, hits=())):
            self.assertFalse(eligible(row))

    def test_wrong_tied_with_correct_disables_rescue(self):
        rows = [self.row]*134 + [replace(self.row, labels=frozenset())]
        self.assertEqual(calibrate(rows, 'retrieval', []), (0, math.inf))

    def test_inclusive_threshold_and_audit(self):
        wrong = replace(self.row, labels=frozenset())
        m = evaluate([self.row, wrong], 'retrieval', [], .7)
        self.assertEqual((m['gained'], m['added_wrong'], m['added_unsupported']), (1,1,1))

    def test_fit_rejects_calibration_and_single_class(self):
        with self.assertRaises(ValueError):
            fit([self.row]*507)
        with self.assertRaises(ValueError):
            fit([replace(self.row, split='train')]*507)
