package com.omnidev.workspace.domain.neural;

import static org.junit.Assert.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;

public final class SupportAnswerabilityModelTest {
  static SupportSubwordModel proposer() {
    return SupportSubwordModelTest.make(
        SupportQueryMemoryTest.make(SupportQueryMemoryTest.examples(), null),
        new TrainTokenCoverage(SupportQueryMemoryTest.examples()),
        true);
  }

  static TinyNeuralRuntime head(float bias) {
    return new TinyNeuralRuntime(
            new TinyNeuralRuntime.Dense(
                1024, 1, new float[1024], new float[] {bias}, TinyNeuralRuntime.Activation.LINEAR))
        .withSparseInput();
  }

  static SupportAnswerabilityModel model(
      SupportSubwordModel p, SupportPairEncoder e, float bias, double threshold) {
    return new SupportAnswerabilityModel(
        p,
        e,
        head(bias),
        1,
        threshold,
        SupportAnswerabilityModel.binding(p, e),
        SupportAnswerabilityModel.VERSION);
  }

  @Test
  public void queryBlocksStayFixedWhileReferenceInteractionBlocksChange() {
    SupportPairEncoder e = new SupportPairEncoder(HybridSupportRetrieverTest.corpus());
    float[] a = e.encode("private bot microphone", "voice"),
        b = e.encode("private bot microphone", "telegram");
    assertArrayEquals(Arrays.copyOf(a, 512), Arrays.copyOf(b, 512), 0);
    assertFalse(Arrays.equals(Arrays.copyOfRange(a, 512, 1024), Arrays.copyOfRange(b, 512, 1024)));
    double norm = 0;
    for (float value : a) {
      assertTrue(Float.isFinite(value) && Math.abs(value) <= 1);
      norm += (double) value * value;
    }
    assertTrue(norm <= 1.000001);
    List<ProductSupportModel.Article> reverse =
        new ArrayList<>(HybridSupportRetrieverTest.corpus());
    Collections.reverse(reverse);
    assertEquals(e.fingerprint(), new SupportPairEncoder(reverse).fingerprint());
    assertThrows(IllegalArgumentException.class, () -> e.encode("voice", "foreign"));
    assertThrows(
        IllegalArgumentException.class,
        () -> e.encode(String.join("", Collections.nCopies(513, "x")), "voice"));
  }

  @Test
  public void anyThresholdIsAVetoAndCannotRewriteProposedSources() {
    SupportSubwordModel p = proposer();
    SupportPairEncoder e = new SupportPairEncoder(HybridSupportRetrieverTest.corpus());
    for (double threshold : new double[] {0, .25, .5, .75, 1})
      for (float bias : new float[] {-20, 0, 20}) {
        SupportAnswerabilityModel m = model(p, e, bias, threshold);
        for (String q :
            new String[] {
              "microphne",
              "private owner bot pairing",
              "تشغيل مستمع التنبيه بالصوت",
              "xyzqwerty",
              ""
            }) {
          SupportSubwordModel.Result before = p.search(q, 10);
          SupportAnswerabilityModel.Result after = m.search(q, 10);
          assertEquals(before.accepted, after.proposalAccepted);
          assertFalse(after.accepted && !before.accepted);
          assertEquals(before.matches.size(), after.matches.size());
          for (int i = 0; i < before.matches.size(); i++) {
            assertSame(before.matches.get(i).topic, after.matches.get(i).topic);
            assertEquals(before.matches.get(i).score, after.matches.get(i).score, 0);
          }
          if (!before.accepted) {
            assertFalse(after.scored);
            assertEquals(0, after.answerability, 0);
          }
          if (threshold == 0) assertEquals(before.accepted, after.accepted);
        }
      }
  }

  @Test
  public void identityBindsProposalAndExactReferenceContent() {
    SupportSubwordModel p = proposer();
    SupportPairEncoder e = new SupportPairEncoder(HybridSupportRetrieverTest.corpus());
    List<ProductSupportModel.Article> changed =
        new ArrayList<>(HybridSupportRetrieverTest.corpus());
    ProductSupportModel.Article first = changed.get(0);
    changed.set(
        0,
        new ProductSupportModel.Article(
            first.id,
            first.topic,
            first.language,
            first.question,
            first.text + " changed",
            first.status,
            first.editions));
    SupportPairEncoder foreign = new SupportPairEncoder(changed);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SupportAnswerabilityModel(
                p,
                foreign,
                head(0),
                1,
                .5,
                SupportAnswerabilityModel.binding(p, e),
                SupportAnswerabilityModel.VERSION));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SupportAnswerabilityModel(
                p,
                e,
                head(0),
                Double.NaN,
                .5,
                SupportAnswerabilityModel.binding(p, e),
                SupportAnswerabilityModel.VERSION));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SupportAnswerabilityModel(
                p,
                e,
                SupportReliabilityModelTest.head(0),
                1,
                .5,
                SupportAnswerabilityModel.binding(p, e),
                SupportAnswerabilityModel.VERSION));
    assertNotEquals(model(p, e, 0, .25).fingerprint(), model(p, e, 0, .5).fingerprint());
    SupportAnswerabilityModel original = model(p, e, 0, .5);
    SupportAnswerabilityModel changedPolicy = original.withThreshold(.75);
    assertEquals(1025, original.parameterCount());
    assertNotEquals(original.fingerprint(), changedPolicy.fingerprint());
    assertEquals(original.score("bot", "telegram"), changedPolicy.score("bot", "telegram"), 0);
    assertEquals(model(p, e, 0, .5).fingerprint(), original.fingerprint());
    assertThrows(IllegalArgumentException.class, () -> original.withThreshold(Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> original.withThreshold(-.1));
  }

  @Test
  public void concurrentScoringAndReturnedReferencesAreImmutable() throws Exception {
    SupportSubwordModel p = proposer();
    SupportPairEncoder e = new SupportPairEncoder(HybridSupportRetrieverTest.corpus());
    SupportAnswerabilityModel m = model(p, e, 2, .5);
    double score = m.score("private bot", "telegram");
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<Double>> jobs = new ArrayList<>();
      for (int i = 0; i < 32; i++) jobs.add(pool.submit(() -> m.score("private bot", "telegram")));
      for (Future<Double> job : jobs) assertEquals(score, job.get(), 0);
    } finally {
      pool.shutdownNow();
    }
    assertThrows(
        UnsupportedOperationException.class,
        () -> m.search("private owner bot pairing", 3).matches.clear());
  }
}
