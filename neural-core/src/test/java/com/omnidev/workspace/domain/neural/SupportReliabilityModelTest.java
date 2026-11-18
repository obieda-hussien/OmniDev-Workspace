package com.omnidev.workspace.domain.neural;

import static org.junit.Assert.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;

public final class SupportReliabilityModelTest {
  static SupportQueryMemory memory() {
    return SupportQueryMemoryTest.make(SupportQueryMemoryTest.examples(), null);
  }

  static TinyNeuralRuntime head(float bias) {
    return new TinyNeuralRuntime(
        new TinyNeuralRuntime.Dense(
            12, 1, new float[12], new float[] {bias}, TinyNeuralRuntime.Activation.LINEAR));
  }

  static SupportReliabilityModel make(
      SupportQueryMemory m, boolean active, double threshold, double margin) {
    return new SupportReliabilityModel(
        m,
        head(2),
        1,
        threshold,
        0,
        margin,
        active,
        m.contractFingerprint(),
        SupportReliabilityModel.VERSION);
  }

  @Test
  public void emptyInputsNeverBecomeEvidenceEvenWithAHighHeadScore() {
    SupportReliabilityModel m = make(memory(), true, 0, 0);
    for (String q : new String[] {"", "!!!", "how can I Omni"}) {
      SupportReliabilityModel.Result r = m.search(q, 1);
      assertFalse(r.accepted);
      assertTrue(r.matches.isEmpty());
      assertEquals(0, r.reliability, 0);
    }
    assertThrows(IllegalArgumentException.class, () -> m.search(null, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> m.search(String.join("", Collections.nCopies(513, "x")), 1));
    assertThrows(IllegalArgumentException.class, () -> m.search("voice", 11));
  }

  @Test
  public void headCannotRewriteRankOrOriginalSources() {
    SupportQueryMemory memory = memory();
    SupportReliabilityModel m = make(memory, true, 0, 0);
    for (String q :
        new String[] {
          "wake listener microphone setup",
          "private owner bot pairing",
          "تشغيل مستمع التنبيه بالصوت",
          "voice owner مختلف"
        }) {
      SupportQueryMemory.Result old = memory.search(q, 10);
      SupportReliabilityModel.Result next = m.search(q, 10);
      assertEquals(old.matches.size(), next.matches.size());
      for (int i = 0; i < old.matches.size(); i++) {
        assertSame(old.matches.get(i).topic, next.matches.get(i).topic);
        assertEquals(old.matches.get(i).score, next.matches.get(i).score, 0);
        assertEquals(old.matches.get(i).coverage, next.matches.get(i).coverage, 0);
      }
      assertEquals(old.accepted, next.baselineAccepted);
      assertThrows(UnsupportedOperationException.class, () -> next.matches.clear());
    }
  }

  @Test
  public void inactivePolicyPreservesBaselineAndLimitCannotHideRunnerUp() {
    SupportQueryMemory memory = memory();
    SupportReliabilityModel inactive = make(memory, false, 1, 1),
        active = make(memory, true, 0, .5);
    for (String q :
        new String[] {"wake listener", "private bot", "voice owner", "صوت private", ""}) {
      assertEquals(memory.search(q, 3).accepted, inactive.search(q, 3).accepted);
      assertEquals(active.search(q, 10).accepted, active.search(q, 1).accepted);
      assertEquals(inactive.search(q, 10).accepted, inactive.search(q, 1).accepted);
    }
  }

  @Test
  public void checkpointBindsRankingWeightsBaseModeAndAcceptancePolicy() {
    SupportQueryMemory first = memory();
    List<ProductSupportModel.Article> docs = SupportQueryMemoryTest.docs();
    HybridSupportRetriever base =
        new HybridSupportRetriever(docs, HybridSupportRetriever.Mode.UNIFORM, null, 0, 0, 0, null);
    SupportQueryMemory alteredWeights =
        new SupportQueryMemory(
            docs,
            base,
            SupportQueryMemoryTest.examples(),
            new float[] {1, 0, 0},
            0,
            .01,
            .25,
            null,
            SupportQueryMemory.VERSION);
    SupportQueryMemory alteredPolicy =
        new SupportQueryMemory(
            docs,
            base,
            SupportQueryMemoryTest.examples(),
            new float[] {1f / 3, 1f / 3, 1f / 3},
            0,
            .2,
            .25,
            null,
            SupportQueryMemory.VERSION);
    SupportQueryMemory alteredBase =
        new SupportQueryMemory(
            docs,
            new HybridSupportRetriever(
                docs, HybridSupportRetriever.Mode.TITLE, null, 0, 0, 0, null),
            SupportQueryMemoryTest.examples(),
            new float[] {1f / 3, 1f / 3, 1f / 3},
            0,
            .01,
            .25,
            null,
            SupportQueryMemory.VERSION);
    for (SupportQueryMemory other : Arrays.asList(alteredWeights, alteredPolicy, alteredBase)) {
      assertEquals(first.fingerprint(), other.fingerprint());
      assertNotEquals(first.contractFingerprint(), other.contractFingerprint());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new SupportReliabilityModel(
                  other,
                  head(2),
                  1,
                  .5,
                  0,
                  0,
                  true,
                  first.contractFingerprint(),
                  SupportReliabilityModel.VERSION));
    }
  }

  @Test
  public void malformedShapeCalibrationVersionAndBindingAreRejected() {
    SupportQueryMemory m = memory();
    String contract = m.contractFingerprint();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SupportReliabilityModel(
                m, head(2), Double.NaN, .5, 0, 0, true, contract, SupportReliabilityModel.VERSION));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SupportReliabilityModel(
                m, head(2), 0, .5, 0, 0, true, contract, SupportReliabilityModel.VERSION));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SupportReliabilityModel(m, head(2), 1, .5, 0, 0, true, contract, "foreign"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SupportReliabilityModel(
                m, head(2), 1, 1.01, 0, 0, true, contract, SupportReliabilityModel.VERSION));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SupportReliabilityModel(
                m, head(2), 1, .5, 0, 0, true, null, SupportReliabilityModel.VERSION));
    TinyNeuralRuntime wrong =
        new TinyNeuralRuntime(
            new TinyNeuralRuntime.Dense(
                3, 1, new float[3], new float[1], TinyNeuralRuntime.Activation.LINEAR));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SupportReliabilityModel(
                m, wrong, 1, .5, 0, 0, true, contract, SupportReliabilityModel.VERSION));
  }

  @Test
  public void outputsAreDeterministicAndFingerprintsIncludeHeadAndPolicy() throws Exception {
    SupportQueryMemory memory = memory();
    SupportReliabilityModel m = make(memory, true, .5, 0);
    assertNotEquals(m.fingerprint(), make(memory, false, .5, 0).fingerprint());
    assertNotEquals(m.fingerprint(), make(memory, true, .75, 0).fingerprint());
    assertEquals(13, m.parameterCount());
    double expected = m.search("private owner bot pairing", 1).reliability;
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<Double>> tasks = new ArrayList<>();
      for (int i = 0; i < 32; i++)
        tasks.add(pool.submit(() -> m.search("private owner bot pairing", 1).reliability));
      for (Future<Double> task : tasks) assertEquals(expected, task.get(), 0);
    } finally {
      pool.shutdownNow();
    }
  }
}
