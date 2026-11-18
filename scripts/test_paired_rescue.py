import unittest
from dataclasses import replace
from fit_topic_rerank import Hit, Row
from fit_paired_rescue import accepts, paired_outcomes, partition, train_policy


class PairedRescueTest(unittest.TestCase):
    row = Row('paired13-a-1-ar','paired13-test','ar',frozenset({'a'}),False,
              (Hit('a', .7, .5, .6),),False)
    policy = dict(kind='retrieval', weights=[], threshold=.7)

    def test_veto_and_acceptance_precedence(self):
        self.assertFalse(accepts(replace(self.row, proposed=True), self.policy))
        self.assertTrue(accepts(replace(self.row, proposed=True, accepted=True), self.policy))
        self.assertTrue(accepts(self.row, self.policy))
        self.assertFalse(accepts(replace(self.row, hits=()), self.policy))

    def test_pair_requires_both_decisions(self):
        negative = replace(self.row,id='paired13-a-0-ar',labels=frozenset())
        self.assertEqual(paired_outcomes([self.row,negative],self.policy)['complete'],0)
        negative = replace(negative, proposed=True)
        self.assertEqual(paired_outcomes([self.row,negative],self.policy)['complete'],1)
        incorrect = replace(self.row,hits=(Hit('other', .9, .5, .6),))
        self.assertEqual(paired_outcomes([incorrect,negative],self.policy)['complete'],0)

    def test_topic_overlap_and_incomplete_language_fail(self):
        rows = []
        for split,count in [('train',8),('dev',4),('test',6)]:
            for i in range(count):
                topic = split+str(i)
                for label in ('0','1'):
                    for lang in ('ar','en','mixed'):
                        rows.append(replace(self.row,id=f'paired13-{topic}-{label}-{lang}',
                                            language=lang,split='paired13-'+split,
                                            labels={topic} if label=='1' else set()))
        partition(rows)
        with self.assertRaises(ValueError):
            partition(rows[:-1])
        with self.assertRaises(ValueError):
            partition([replace(r,id=r.id.replace('test0','train0'),
                               labels={'train0'} if r.labels else set()) if 'test0-' in r.id else r for r in rows])

    def test_evaluation_rows_cannot_fit(self):
        with self.assertRaises(ValueError):
            train_policy([self.row],[self.row],'all_hits')
