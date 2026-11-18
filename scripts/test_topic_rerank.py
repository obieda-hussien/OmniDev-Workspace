import math
import unittest
from dataclasses import replace

from fit_topic_rerank import Hit, Row, choose, evaluate, fit


class TopicPolicyTest(unittest.TestCase):
    def row(self, accepted=True, probability=.9, labels=('second',)):
        return Row('a', 'test', 'ar', frozenset(labels), accepted,
                   (Hit('first', .5, .5, .2), Hit('second', .4, 1, probability)))

    def test_refusal_stays_refused_even_with_known_answer(self):
        row = self.row(False)
        self.assertIsNone(choose(row, 1, 2, 0))
        stats, _ = evaluate([row], (1, 2, 0))
        self.assertEqual((stats['refused_supported'], stats['refused_correct_top3']), (1, 1))

    def test_challenger_must_clear_veto(self):
        self.assertEqual(choose(self.row(probability=.0999), 1, 0, 0), 'first')
        self.assertEqual(choose(self.row(probability=.1), 1, 0, 0), 'second')

    def test_strict_margin_and_disabled_policy(self):
        tied = Row('tie', 'test', 'en', frozenset(), True,
                   (Hit('a', .5, 1, 1), Hit('b', .75, 1, 1)))
        self.assertEqual(choose(tied, 0, 0, .25), 'a')
        self.assertEqual(choose(tied, 0, 0, .24), 'b')
        self.assertEqual(choose(tied, 0, 0, math.inf), 'a')

    def test_switch_accounting_includes_alternative_correct_labels(self):
        stats, changes = evaluate([self.row(), self.row(labels=('first',)),
                                   self.row(labels=('first', 'second'))], (1, 0, 0))
        self.assertEqual((stats['gained'], stats['lost'], len(changes)), (1, 1, 3))
        self.assertEqual(stats['oracle_correct'], 3)

    def test_fit_learns_to_switch_and_dev_can_disable_it(self):
        source = self.row()
        train = [replace(source, split='train', id=str(i)) for i in range(507)]
        dev = [replace(source, split='development', id='d'+str(i)) for i in range(135)]
        policy, grid = fit(train, dev)
        self.assertEqual(len(grid), 20)
        self.assertEqual(choose(source, *policy), 'second')
        policy, _ = fit(train, [replace(r, labels=frozenset({'first'})) for r in dev])
        self.assertEqual(choose(source, *policy), 'first')

    def test_reject_mislabeled_fit_roles(self):
        with self.assertRaises(ValueError):
            fit([self.row()] * 507, [self.row()] * 135)


if __name__ == '__main__':
    unittest.main()
