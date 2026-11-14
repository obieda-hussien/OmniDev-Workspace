package com.omnidev.workspace.domain.neural;

import static org.junit.Assert.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;

public final class ProductSupportModelTest {
  private static List<ProductSupportModel.Article> articles() {
    return Arrays.asList(
        new ProductSupportModel.Article(
            "telegram:en",
            "telegram",
            "en",
            "How can I link Telegram?",
            "Telegram owner pairing requires a private chat and linked account.",
            "implemented",
            "advanced"),
        new ProductSupportModel.Article(
            "voice:en",
            "voice",
            "en",
            "How can I use voice?",
            "Voice wake needs microphone permission and a supported backend.",
            "implemented",
            "all"));
  }

  private static TinyNeuralRuntime constant(float bias) {
    return new TinyNeuralRuntime(
        new TinyNeuralRuntime.Dense(
            12, 1, new float[12], new float[] {bias}, TinyNeuralRuntime.Activation.LINEAR));
  }

  @Test
  public void quotesAndMetadataRemainAttributable() {
    ProductSupportModel m = new ProductSupportModel(articles(), null, 0, 0, null);
    ProductSupportModel.Result r = m.search("Telegram owner pairing", 3);
    assertTrue(r.accepted);
    ProductSupportModel.Match first = r.matches.get(0);
    assertEquals("telegram", first.article.topic);
    assertEquals("advanced", first.article.editions);
    assertEquals(
        first.evidence.quote,
        first.article.text.substring(first.evidence.startOffset, first.evidence.endOffset));
    assertTrue(first.evidence.score >= 0);
    assertThrows(UnsupportedOperationException.class, () -> r.matches.clear());
  }

  @Test
  public void learnedScoreCannotUpgradeGuardedBaselineRefusal() {
    ProductSupportModel raw = new ProductSupportModel(articles(), constant(20), 0, 0, null);
    assertTrue(raw.search("Telegram owner pairing", 3).accepted);
    ProductSupportModel guarded =
        new ProductSupportModel(
            articles(), constant(20), 0, 0, null, new ProductSupportModel.Guard(1, 0));
    assertFalse(guarded.search("Telegram owner pairing", 3).accepted);
  }

  @Test
  public void agreementGuardNeverAcceptsDifferentBaselineTopic() {
    ProductSupportModel base = new ProductSupportModel(articles(), null, 0, 0, null),
        g =
            new ProductSupportModel(
                articles(), constant(10), 0, 0, null, new ProductSupportModel.Guard(0, 0));
    for (String q :
        Arrays.asList(
            "voice microphone permission", "Telegram owner pairing", "supported backend wake")) {
      ProductSupportModel.Result a = g.search(q, 3), b = base.search(q, 3);
      if (a.accepted) {
        assertTrue(b.accepted);
        assertEquals(b.matches.get(0).article.topic, a.matches.get(0).article.topic);
      }
    }
  }

  @Test
  public void emptyQueryAbstainsEvenWithConfidentModel() {
    assertFalse(
        new ProductSupportModel(articles(), constant(20), 0, 0, null).search("", 1).accepted);
  }

  @Test
  public void corpusBindingRejectsStaleOrForeignDocuments() {
    ProductSupportModel m = new ProductSupportModel(articles(), null, 0, 0, null);
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProductSupportModel(articles(), null, 0, 0, "wrong"));
    List<ProductSupportModel.Article> changed = new ArrayList<>(articles());
    changed.remove(0);
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProductSupportModel(changed, null, 0, 0, m.fingerprint()));
  }

  @Test
  public void malformedNetworksBoundsAndDuplicatesFailClosed() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProductSupportModel(articles(), null, Double.NaN, 0, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProductSupportModel(
                articles(),
                new TinyNeuralRuntime(
                    new TinyNeuralRuntime.Dense(
                        1,
                        1,
                        new float[] {1},
                        new float[] {0},
                        TinyNeuralRuntime.Activation.LINEAR)),
                0,
                0,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProductSupportModel(
                Arrays.asList(articles().get(0), articles().get(0)), null, 0, 0, null));
    ProductSupportModel m = new ProductSupportModel(articles(), null, 0, 0, null);
    assertThrows(
        IllegalArgumentException.class,
        () -> m.search(String.join("", Collections.nCopies(513, "x")), 1));
    assertThrows(IllegalArgumentException.class, () -> m.search("Telegram", 11));
    assertThrows(
        IllegalArgumentException.class, () -> new ProductSupportModel.Guard(0, Double.NaN));
  }

  @Test
  public void foreignEvidenceIsRejected() {
    ProductSupportModel m = new ProductSupportModel(articles(), null, 0, 0, null);
    OmniRamLite index =
        new OmniRamLite(Arrays.asList(new OmniRamLite.Document("foreign:en", "Telegram owner")));
    OmniRamLite.Hit hit = index.search("Telegram", 1).get(0);
    assertThrows(IllegalArgumentException.class, () -> m.features("Telegram", hit));
  }

  @Test
  public void cacheIsBoundedAndConcurrentInferenceIsDeterministic() throws Exception {
    ProductSupportModel m = new ProductSupportModel(articles(), constant(0), 0, 0, null);
    List<String> expected = new ArrayList<>();
    for (ProductSupportModel.Match hit : m.search("Telegram voice permission", 3).matches)
      expected.add(hit.article.id);
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<List<String>>> futures = new ArrayList<>();
      for (int i = 0; i < 40; i++)
        futures.add(
            pool.submit(
                () -> {
                  List<String> ids = new ArrayList<>();
                  for (ProductSupportModel.Match hit :
                      m.search("Telegram voice permission", 3).matches) ids.add(hit.article.id);
                  return ids;
                }));
      for (Future<List<String>> f : futures) assertEquals(expected, f.get());
      ProductSupportModel.Article article = articles().get(0);
      for (int end = 12; end < 40; end++) {
        OmniRamLite probe =
            new OmniRamLite(
                Arrays.asList(
                    new OmniRamLite.Document(article.id, article.text.substring(0, end))));
        m.features("Telegram", probe.search("Telegram", 1).get(0));
      }
      assertTrue(m.cachedPassages() <= articles().size() * 3);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  public void candidateFeaturesHaveFixedFiniteContract() {
    ProductSupportModel m = new ProductSupportModel(articles(), null, 0, 0, null);
    for (ProductSupportModel.Match hit : m.evidencePool("Telegram private pairing")) {
      float[] x = m.features("Telegram private pairing", hit.evidence);
      assertEquals(12, x.length);
      for (float value : x) assertTrue(Float.isFinite(value) && value >= 0 && value <= 1);
    }
  }
}
