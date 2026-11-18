import math
import unittest
from dataclasses import replace
from fit_topic_rerank import Hit, Row
from fit_lexical_rescue import bucket, metrics, tokens, train, vector


class LexicalRescueTest(unittest.TestCase):
    row = Row('a','train','ar',frozenset({'doc'}),False,(Hit('doc', .5,.5,.6),),False)

    def test_arabic_normalization_and_code_switch(self):
        self.assertEqual(tokens('إذْن الـ PORT؟'), ['اذن','الـ','port'])

    def test_bounded_separate_lexical_block(self):
        present = dict(vector(self.row,'port',{'port'},True))
        absent = dict(vector(self.row,'port',set(),True))
        self.assertNotEqual(present,absent)
        self.assertEqual(dict(vector(self.row,'port',set(),False)),{i:present[i] for i in range(6)})
        self.assertAlmostEqual(sum(v*v for k,v in present.items() if k>=6),1)
        self.assertTrue(all(0<=k<2054 for k in absent))
        self.assertEqual(bucket('wport'),(236,1))

    def test_control_only_candidates_and_inclusive_cutoff(self):
        rows = {'a':self.row,'b':replace(self.row,id='b',labels=set()),'c':replace(self.row,id='c')}
        m = metrics(rows,rows,{'a':.5,'b':.49},.5)
        self.assertEqual((m['gained'],m['wrong']),(1,0))

    def test_training_separates_opposite_observations(self):
        rows = {'a':self.row,'b':replace(self.row,id='b',labels=set())}
        with self.assertRaises(ValueError):
            train(['a'],{'a':[(0,1)]},rows,2)
        w = train(['a','b'],{'a':[(0,1),(1,1)],'b':[(0,1),(1,-1)]},rows,2)
        self.assertTrue(math.isfinite(w[0]) and w[1]>0)
