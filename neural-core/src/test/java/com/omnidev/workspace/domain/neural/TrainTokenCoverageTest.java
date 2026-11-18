package com.omnidev.workspace.domain.neural;

import static org.junit.Assert.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;

public final class TrainTokenCoverageTest {
  @Test
  public void vocabularyAndPostingBudgetsRejectOversizedCorpora() {
    List<SupportQueryMemory.Exemplar> manyWords = new ArrayList<>();
    for (int i = 0; i < 512; i++) {
      StringBuilder text = new StringBuilder();
      for (int j = 0; j < 17; j++) text.append("word").append(i).append('x').append(j).append(' ');
      manyWords.add(new SupportQueryMemory.Exemplar("e" + i, "g" + i, "voice", text.toString()));
    }
    assertThrows(IllegalArgumentException.class, () -> new TrainTokenCoverage(manyWords));
    Random random = new Random(97);
    List<SupportQueryMemory.Exemplar> manyPostings = new ArrayList<>();
    for (int i = 0; i < 512; i++) {
      StringBuilder text = new StringBuilder();
      for (int j = 0; j < 15; j++) {
        for (int k = 0; k < 30; k++) text.append((char) ('a' + random.nextInt(26)));
        text.append(' ');
      }
      manyPostings.add(
          new SupportQueryMemory.Exemplar("p" + i, "pg" + i, "voice", text.toString()));
    }
    assertThrows(IllegalArgumentException.class, () -> new TrainTokenCoverage(manyPostings));
  }

  @Test
  public void candidateCapNeverDropsAnExactWordOnAGramCountTie() {
    List<SupportQueryMemory.Exemplar> examples = new ArrayList<>();
    for (int i = 0; i < 130; i++)
      examples.add(
          new SupportQueryMemory.Exemplar("e" + i, "g" + i, "telegram", "aaaa" + i + "aaaa"));
    examples.add(new SupportQueryMemory.Exemplar("exact", "exact-group", "voice", "aaaaaaaaaaaa"));
    assertEquals(
        1, new TrainTokenCoverage(examples).coverage("aaaaaaaaaaaa", null).get("voice"), 0);
  }

  static List<SupportQueryMemory.Exemplar> examples() {
    return SupportQueryMemoryTest.examples();
  }

  @Test
  public void typoMatchingAndWholeGroupExclusionAreReal() {
    TrainTokenCoverage index = new TrainTokenCoverage(examples());
    assertTrue(index.coverage("microphne", null).get("voice") >= .6);
    assertEquals(0, index.coverage("microphne", "scenario-one").get("voice"), 0);
    assertEquals(0, index.coverage("تشغيل مستمع التنبيه بالصوت", "scenario-one").get("voice"), 0);
    assertEquals(0, index.coverage("qzxvfghjk", null).get("voice"), 0);
    assertEquals(1, index.coverage("microphone", null).get("voice"), 0);
  }

  @Test
  public void sparseCandidateIndexMatchesADenseVocabularyScan() {
    List<SupportQueryMemory.Exemplar> corpus = new ArrayList<>(examples());
    corpus.add(
        new SupportQueryMemory.Exemplar(
            "contrast", "scenario-three", "telegram", "microprocessor private ownership"));
    TrainTokenCoverage index = new TrainTokenCoverage(corpus);
    for (String q :
        new String[] {
          "microphne private",
          "تشغيله بالصوت",
          "microphone microphone",
          "unknownxyz bot",
          "",
          "owner مختلف صوت"
        })
      for (String excluded : new String[] {null, "scenario-one", "scenario-two", "missing"}) {
        Map<String, Double> expected = dense(corpus, q, excluded),
            actual = index.coverage(q, excluded);
        assertEquals(expected.keySet(), actual.keySet());
        for (String topic : expected.keySet())
          assertEquals(expected.get(topic), actual.get(topic), 1e-12);
      }
  }

  @Test
  public void identityCopiesBoundsAndRangesAreEnforced() {
    List<SupportQueryMemory.Exemplar> source = new ArrayList<>(examples());
    TrainTokenCoverage index = new TrainTokenCoverage(source);
    Collections.reverse(source);
    assertEquals(index.fingerprint(), new TrainTokenCoverage(source).fingerprint());
    source.clear();
    assertEquals(1, index.coverage("microphone", null).get("voice"), 0);
    assertThrows(UnsupportedOperationException.class, () -> index.coverage("voice", null).clear());
    assertThrows(IllegalArgumentException.class, () -> index.coverage(null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> index.coverage(String.join("", Collections.nCopies(513, "x")), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TrainTokenCoverage(Arrays.asList(examples().get(0), examples().get(0))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TrainTokenCoverage(
                Collections.singletonList(
                    new SupportQueryMemory.Exemplar(
                        "long", "group", "voice", String.join("", Collections.nCopies(65, "x"))))));
    for (double v : index.coverage("voice private صوت", null).values())
      assertTrue(Double.isFinite(v) && v >= 0 && v <= 1);
    assertTrue(
        index.wordCount() > 0
            && index.gramCount() > 0
            && index.postingCount() >= index.gramCount());
  }

  @Test
  public void concurrentCoverageQueriesKeepNoSharedQueryState() throws Exception {
    TrainTokenCoverage index = new TrainTokenCoverage(examples());
    Map<String, Double> expected = index.coverage("microphne private", null);
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<Map<String, Double>>> tasks = new ArrayList<>();
      for (int i = 0; i < 32; i++)
        tasks.add(pool.submit(() -> index.coverage("microphne private", null)));
      for (Future<Map<String, Double>> task : tasks) assertEquals(expected, task.get());
    } finally {
      pool.shutdownNow();
    }
  }

  static Map<String, Double> dense(
      List<SupportQueryMemory.Exemplar> examples, String query, String excluded) {
    Map<String, Map<String, Set<String>>> owners = new TreeMap<>();
    Map<String, Integer> df = new HashMap<>();
    Set<String> topics = new TreeSet<>();
    for (SupportQueryMemory.Exemplar e : examples) {
      topics.add(e.topic);
      for (String word : TrainTokenCoverage.tokens(e.text)) {
        df.put(word, df.getOrDefault(word, 0) + 1);
        owners
            .computeIfAbsent(word, k -> new TreeMap<>())
            .computeIfAbsent(e.topic, k -> new HashSet<>())
            .add(e.group);
      }
    }
    Map<String, Double> out = new TreeMap<>();
    for (String topic : topics) out.put(topic, 0.0);
    double total = 0;
    for (String word : TrainTokenCoverage.tokens(query)) {
      double weight = 1 + Math.log((examples.size() + 1.0) / (df.getOrDefault(word, 0) + 1));
      total += weight;
      Set<String> q = TrainTokenCoverage.grams(word);
      Map<String, Double> best = new TreeMap<>();
      for (Map.Entry<String, Map<String, Set<String>>> entry : owners.entrySet()) {
        Set<String> grams = TrainTokenCoverage.grams(entry.getKey()),
            intersection = new HashSet<>(q);
        intersection.retainAll(grams);
        double dice = 2.0 * intersection.size() / (q.size() + grams.size());
        if (dice < .6) continue;
        for (Map.Entry<String, Set<String>> owner : entry.getValue().entrySet())
          if (owner.getValue().stream().anyMatch(g -> !g.equals(excluded)))
            best.put(owner.getKey(), Math.max(best.getOrDefault(owner.getKey(), 0.0), dice));
      }
      for (Map.Entry<String, Double> b : best.entrySet())
        out.put(b.getKey(), out.get(b.getKey()) + weight * b.getValue());
    }
    for (Map.Entry<String, Double> e : out.entrySet())
      e.setValue(total == 0 ? 0 : e.getValue() / total);
    return out;
  }
}
