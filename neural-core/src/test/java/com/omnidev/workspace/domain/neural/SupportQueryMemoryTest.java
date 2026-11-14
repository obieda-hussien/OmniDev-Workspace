package com.omnidev.workspace.domain.neural;

import static org.junit.Assert.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;

public final class SupportQueryMemoryTest {
  static List<ProductSupportModel.Article> docs() {
    return HybridSupportRetrieverTest.corpus();
  }

  static List<SupportQueryMemory.Exemplar> examples() {
    return Arrays.asList(
        new SupportQueryMemory.Exemplar(
            "voice-en", "scenario-one", "voice", "wake listener microphone setup"),
        new SupportQueryMemory.Exemplar(
            "voice-ar", "scenario-one", "voice", "تشغيل مستمع التنبيه بالصوت"),
        new SupportQueryMemory.Exemplar(
            "telegram-en", "scenario-two", "telegram", "private owner bot pairing"));
  }

  static SupportQueryMemory make(List<SupportQueryMemory.Exemplar> e, String fingerprint) {
    List<ProductSupportModel.Article> d = docs();
    return new SupportQueryMemory(
        d,
        new HybridSupportRetriever(d, HybridSupportRetriever.Mode.UNIFORM, null, 0, 0, 0, null),
        e,
        new float[] {1f / 3, 1f / 3, 1f / 3},
        0,
        .01,
        .25,
        fingerprint,
        SupportQueryMemory.VERSION);
  }

  @Test
  public void exactTrainingMatchIsRemovedWithAllTranslationsOfItsGroup() {
    SupportQueryMemory m = make(examples(), null);
    for (String q : new String[] {"wake listener microphone setup", "تشغيل مستمع التنبيه بالصوت"}) {
      SupportQueryMemory.Row full =
          m.rows(q, null).stream().filter(r -> r.topic.id.equals("voice")).findFirst().get();
      assertEquals(1, full.featuresCopy()[1], 1e-6);
      SupportQueryMemory.Row excluded =
          m.rows(q, "scenario-one").stream()
              .filter(r -> r.topic.id.equals("voice"))
              .findFirst()
              .get();
      assertEquals(0, excluded.featuresCopy()[1], 0);
      assertEquals(0, excluded.featuresCopy()[2], 0);
    }
  }

  @Test
  public void referencesAreOriginalDocumentsAndNeverTrainingQueries() {
    SupportQueryMemory.Hit h =
        make(examples(), null).search("wake listener microphone setup", 2).matches.get(0);
    assertEquals("voice", h.topic.id);
    for (ProductSupportModel.Article a : h.topic.references)
      assertTrue(docs().stream().anyMatch(d -> d.id.equals(a.id) && d.text.equals(a.text)));
    assertThrows(UnsupportedOperationException.class, () -> h.topic.references.clear());
  }

  @Test
  public void orderIndependentFingerprintRejectsOneTrainingEdit() {
    SupportQueryMemory m = make(examples(), null);
    List<SupportQueryMemory.Exemplar> reverse = new ArrayList<>(examples());
    Collections.reverse(reverse);
    assertEquals(m.fingerprint(), make(reverse, m.fingerprint()).fingerprint());
    reverse.set(
        0,
        new SupportQueryMemory.Exemplar(
            "telegram-en", "scenario-two", "telegram", "changed wording"));
    assertThrows(IllegalArgumentException.class, () -> make(reverse, m.fingerprint()));
  }

  @Test
  public void foreignTopicsGroupsDuplicatesAndNullsAreRejected() {
    List<SupportQueryMemory.Exemplar> e = new ArrayList<>(examples());
    e.add(e.get(0));
    assertThrows(IllegalArgumentException.class, () -> make(e, null));
    e.set(
        3,
        new SupportQueryMemory.Exemplar("foreign", "scenario-other", "unknown", "unknown facts"));
    assertThrows(IllegalArgumentException.class, () -> make(e, null));
    e.set(
        3,
        new SupportQueryMemory.Exemplar(
            "conflict", "scenario-one", "telegram", "foreign group label"));
    assertThrows(IllegalArgumentException.class, () -> make(e, null));
    e.set(3, null);
    assertThrows(IllegalArgumentException.class, () -> make(e, null));
  }

  @Test
  public void boundsAbstentionAndFiniteFeaturesAreEnforced() {
    SupportQueryMemory m = make(examples(), null);
    assertFalse(m.search("", 1).accepted);
    assertFalse(m.search("how can I Omni", 1).accepted);
    assertThrows(IllegalArgumentException.class, () -> m.search("voice", 11));
    assertThrows(
        IllegalArgumentException.class,
        () -> m.search(String.join("", Collections.nCopies(513, "x")), 1));
    for (SupportQueryMemory.Row r : m.rows("voice owner مختلف", null)) {
      float[] x = r.featuresCopy();
      for (float f : x) assertTrue(Float.isFinite(f) && f >= 0 && f <= 1);
      Arrays.fill(x, Float.NaN);
      assertTrue(Float.isFinite(r.featuresCopy()[0]));
    }
    assertEquals(4, m.parameterCount());
  }

  @Test
  public void weightsVersionAndCorpusBindingsAreStrict() {
    List<ProductSupportModel.Article> d = docs();
    HybridSupportRetriever base =
        new HybridSupportRetriever(d, HybridSupportRetriever.Mode.UNIFORM, null, 0, 0, 0, null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SupportQueryMemory(
                d,
                base,
                examples(),
                new float[] {1, 1, 1},
                0,
                0,
                0,
                null,
                SupportQueryMemory.VERSION));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SupportQueryMemory(
                d, base, examples(), new float[] {1, 0, 0}, 0, 0, 0, null, "wrong-version"));
    List<ProductSupportModel.Article> foreign = new ArrayList<>(d);
    foreign.remove(0);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SupportQueryMemory(
                foreign,
                base,
                examples(),
                new float[] {1, 0, 0},
                0,
                0,
                0,
                null,
                SupportQueryMemory.VERSION));
  }

  @Test
  public void concurrentQueriesAreDeterministic() throws Exception {
    SupportQueryMemory m = make(examples(), null);
    String id = m.search("private owner bot pairing", 1).matches.get(0).topic.id;
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<String>> jobs = new ArrayList<>();
      for (int i = 0; i < 24; i++)
        jobs.add(
            pool.submit(() -> m.search("private owner bot pairing", 1).matches.get(0).topic.id));
      for (Future<String> f : jobs) assertEquals(id, f.get());
    } finally {
      pool.shutdownNow();
    }
  }
}
