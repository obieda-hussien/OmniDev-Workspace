package com.omnidev.workspace.domain.neural;

import static org.junit.Assert.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;

public final class SupportContrastiveModelTest {
  static SupportContrastiveModel model(
      SupportSubwordModel p, SupportPairEncoder e, float[] w, int hidden, double threshold) {
    return new SupportContrastiveModel(
        p,
        e,
        w,
        hidden,
        1,
        threshold,
        SupportContrastiveModel.binding(p, e),
        SupportContrastiveModel.VERSION);
  }

  @Test
  public void bothTopologiesOnlyVetoAndPreserveOriginalSources() {
    SupportSubwordModel p = SupportAnswerabilityModelTest.proposer();
    SupportPairEncoder e = new SupportPairEncoder(HybridSupportRetrieverTest.corpus());
    for (int h : new int[] {0, 8})
      for (float bias : new float[] {-20, 0, 20})
        for (double cutoff : new double[] {0, .25, .5, .75, 1}) {
          float[] w = new float[h == 0 ? 1025 : 8209];
          w[w.length - 1] = bias;
          SupportContrastiveModel m = model(p, e, w, h, cutoff);
          for (String q :
              new String[] {
                "microphne",
                "private owner bot pairing",
                "تشغيل مستمع التنبيه بالصوت",
                "xyzqwerty",
                ""
              }) {
            SupportSubwordModel.Result before = p.search(q, 10);
            SupportContrastiveModel.Result after = m.search(q, 10);
            assertFalse(after.accepted && !before.accepted);
            assertEquals(before.accepted, after.proposalAccepted);
            assertEquals(before.matches.size(), after.matches.size());
            for (int i = 0; i < before.matches.size(); i++) {
              assertSame(before.matches.get(i).topic, after.matches.get(i).topic);
              assertEquals(before.matches.get(i).score, after.matches.get(i).score, 0);
            }
            if (cutoff == 0) assertEquals(before.accepted, after.accepted);
            if (!before.accepted) {
              assertFalse(after.scored);
              assertEquals(0, after.answerability, 0);
            }
          }
        }
  }

  @Test
  public void clonedParametersTopologyAndCalibrationAreBound() {
    SupportSubwordModel p = SupportAnswerabilityModelTest.proposer();
    SupportPairEncoder e = new SupportPairEncoder(HybridSupportRetrieverTest.corpus());
    float[] w = new float[8209];
    w[8208] = 2;
    SupportContrastiveModel m = model(p, e, w, 8, .5);
    String identity = m.fingerprint();
    double score = m.score("bot", "telegram");
    Arrays.fill(w, 100);
    assertEquals(identity, m.fingerprint());
    assertEquals(score, m.score("bot", "telegram"), 0);
    assertEquals(8209, m.parameterCount());
    SupportContrastiveModel changed = m.withThreshold(.75);
    assertNotEquals(identity, changed.fingerprint());
    assertEquals(score, changed.score("bot", "telegram"), 0);
    assertEquals(identity, m.withThreshold(.5).fingerprint());
    assertThrows(IllegalArgumentException.class, () -> m.withThreshold(Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> model(p, e, new float[8209], 0, .5));
    assertThrows(IllegalArgumentException.class, () -> model(p, e, new float[1025], 8, .5));
    float[] invalid = new float[8209];
    invalid[34] = Float.NaN;
    assertThrows(IllegalArgumentException.class, () -> model(p, e, invalid, 8, .5));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SupportContrastiveModel(
                p, e, new float[8209], 8, 1, .5, "foreign", SupportContrastiveModel.VERSION));
  }

  @Test
  public void concurrentNonlinearScoringAndResultsStayImmutable() throws Exception {
    SupportSubwordModel p = SupportAnswerabilityModelTest.proposer();
    SupportPairEncoder e = new SupportPairEncoder(HybridSupportRetrieverTest.corpus());
    float[] w = new float[8209];
    Random rng = new Random(42);
    for (int i = 0; i < w.length; i++) w[i] = (float) (rng.nextGaussian() * .05);
    SupportContrastiveModel m = model(p, e, w, 8, .5);
    double expected = m.score("private bot", "telegram");
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<Double>> jobs = new ArrayList<>();
      for (int i = 0; i < 32; i++) jobs.add(pool.submit(() -> m.score("private bot", "telegram")));
      for (Future<Double> job : jobs) assertEquals(expected, job.get(), 0);
    } finally {
      pool.shutdownNow();
    }
    assertThrows(
        UnsupportedOperationException.class,
        () -> m.search("private owner bot pairing", 3).matches.clear());
  }
}
