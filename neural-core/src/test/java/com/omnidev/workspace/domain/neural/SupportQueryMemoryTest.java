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

  @Test
  public void compactPostingsMatchDenseCosineWithUnknownAndRepeatedTerms() {
    List<SupportQueryMemory.Exemplar> e = new ArrayList<>();
    String[] phrases = {
      "wake listener microphone",
      "تشغيل مستمع الصوت",
      "private owner bot pairing",
      "voice صوت wake",
      "تنبيه الصوت تشغيل",
      "bot bot private",
      "😀 voice 𐐀"
    };
    for (int i = 0; i < 48; i++)
      e.add(
          new SupportQueryMemory.Exemplar(
              "e" + i,
              "g" + (i / 3),
              i / 3 % 2 == 0 ? "voice" : "telegram",
              phrases[i % phrases.length] + " " + phrases[(i + 3) % phrases.length]));
    SupportQueryMemory m = make(e, null);
    String[] queries = {
      "wake wake microphone",
      "قُم بتشغيل الصَّوْت",
      "private unmatchedΩ bot",
      "😀 voice 𐐀",
      "voice صوت wake",
      "xyzzynotinvocabulary",
      "bot bot bot private owner",
      "!!!",
      ""
    };
    for (String q : queries)
      for (String excluded : new String[] {null, "g0", "g1", "g7", "absent"}) {
        Map<String, double[]> expected = denseFeatures(e, q, excluded);
        for (SupportQueryMemory.Row row : m.rows(q, excluded)) {
          double[] dense = expected.get(row.topic.id);
          float[] x = row.featuresCopy();
          assertEquals(Float.floatToIntBits((float) dense[0]), Float.floatToIntBits(x[1]));
          assertEquals(Float.floatToIntBits((float) dense[1]), Float.floatToIntBits(x[2]));
          // The original document field may raise coverage above the exemplar-only floor.
          assertTrue(row.coverage >= dense[2]);
        }
      }
  }

  @Test
  public void primitiveIndexCountsAreExactAndIndependentOfCallerMutations() {
    List<SupportQueryMemory.Exemplar> e = new ArrayList<>(examples());
    SupportQueryMemory m = make(e, null);
    SupportQueryMemory.IndexStats s = m.indexStats();
    Set<String> words = new HashSet<>(), chars = new HashSet<>();
    int similarity = 0, coverage = 0;
    for (SupportQueryMemory.Exemplar item : e) {
      Map<String, Integer> w = denseTerms(item.text, false), c = denseTerms(item.text, true);
      words.addAll(w.keySet());
      chars.addAll(c.keySet());
      similarity += w.size() + c.size();
      coverage += normalizedTokens(item.text).size();
    }
    assertEquals(words.size(), s.wordTerms);
    assertEquals(chars.size(), s.characterTerms);
    assertEquals(similarity, s.similarityPostings);
    assertEquals(coverage, s.coveragePostings);
    assertEquals(12L * similarity + 4L * e.size(), s.primitiveArrayPayloadBytes);
    e.clear();
    assertEquals(s.primitiveArrayPayloadBytes, m.indexStats().primitiveArrayPayloadBytes);
    assertEquals("voice", m.search("wake listener microphone setup", 1).matches.get(0).topic.id);
  }

  // Full scans are a small independent oracle for the sparse posting implementation.
  private static Map<String, double[]> denseFeatures(
      List<SupportQueryMemory.Exemplar> examples, String query, String excluded) {
    Map<String, double[]> out = new HashMap<>();
    Set<String> q = normalizedTokens(query);
    for (SupportQueryMemory.Exemplar e : examples) out.put(e.topic, new double[3]);
    for (boolean chars : new boolean[] {false, true}) {
      List<Map<String, Integer>> raw = new ArrayList<>();
      Map<String, Integer> df = new HashMap<>();
      for (SupportQueryMemory.Exemplar e : examples) {
        Map<String, Integer> terms = denseTerms(e.text, chars);
        raw.add(terms);
        for (String term : terms.keySet()) df.put(term, df.getOrDefault(term, 0) + 1);
      }
      Map<String, Double> v = denseVector(denseTerms(query, chars), df, examples.size());
      for (int i = 0; i < examples.size(); i++) {
        SupportQueryMemory.Exemplar e = examples.get(i);
        if (e.group.equals(excluded)) continue;
        Map<String, Double> doc = denseVector(raw.get(i), df, examples.size());
        double dot = 0;
        for (Map.Entry<String, Double> term : v.entrySet())
          dot += term.getValue() * doc.getOrDefault(term.getKey(), 0.0);
        double[] best = out.get(e.topic);
        int view = chars ? 1 : 0;
        best[view] = Math.max(best[view], Math.min(1, Math.max(0, dot)));
        Set<String> overlap = normalizedTokens(e.text);
        overlap.retainAll(q);
        if (!q.isEmpty()) best[2] = Math.max(best[2], overlap.size() / (double) q.size());
      }
    }
    return out;
  }

  private static Set<String> normalizedTokens(String text) {
    String normalized = HybridSupportRetriever.normalize(text, true);
    return normalized.isEmpty()
        ? new TreeSet<>()
        : new TreeSet<>(Arrays.asList(normalized.split(" ")));
  }

  private static Map<String, Integer> denseTerms(String text, boolean chars) {
    String normalized = HybridSupportRetriever.normalize(text, true);
    Map<String, Integer> terms = new TreeMap<>();
    if (normalized.isEmpty()) return terms;
    String[] words = normalized.split(" ");
    for (int i = 0; i < words.length; i++) {
      List<String> keys = new ArrayList<>();
      if (chars) {
        String bounded = "^" + words[i] + "$";
        for (int n = 3; n <= 4; n++)
          for (int j = 0; j + n <= bounded.length(); j++)
            keys.add(n + ":" + bounded.substring(j, j + n));
      } else {
        keys.add("w:" + words[i]);
        if (i > 0) keys.add("b:" + words[i - 1] + " " + words[i]);
      }
      for (String key : keys) terms.put(key, terms.getOrDefault(key, 0) + 1);
    }
    return terms;
  }

  private static Map<String, Double> denseVector(
      Map<String, Integer> counts, Map<String, Integer> df, int size) {
    Map<String, Double> out = new TreeMap<>();
    double squared = 0;
    for (Map.Entry<String, Integer> term : counts.entrySet()) {
      double value =
          (1 + Math.log(term.getValue()))
              * (1 + Math.log((size + 1.0) / (df.getOrDefault(term.getKey(), 0) + 1)));
      out.put(term.getKey(), value);
      squared += value * value;
    }
    if (squared > 0) {
      double norm = Math.sqrt(squared);
      for (Map.Entry<String, Double> term : out.entrySet()) term.setValue(term.getValue() / norm);
    }
    return out;
  }
}
