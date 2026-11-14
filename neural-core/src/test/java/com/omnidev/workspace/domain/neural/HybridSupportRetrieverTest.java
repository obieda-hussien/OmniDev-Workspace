package com.omnidev.workspace.domain.neural;

import static org.junit.Assert.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;

public final class HybridSupportRetrieverTest {
  static List<ProductSupportModel.Article> corpus() {
    return Arrays.asList(
        new ProductSupportModel.Article(
            "voice:en",
            "voice",
            "en",
            "Voice wake setup",
            "Voice wake setup\nMicrophone permission is required for voice wake.",
            "implemented",
            "all"),
        new ProductSupportModel.Article(
            "voice:ar",
            "voice",
            "ar",
            "تشغيل كلمة التنبيه الصوتية",
            "تشغيل كلمة التنبيه الصوتية\nيحتاج الصوت إلى تصريح الميكروفون.",
            "implemented",
            "all"),
        new ProductSupportModel.Article(
            "telegram:en",
            "telegram",
            "en",
            "Pair Telegram owner",
            "Pair Telegram owner\nThe owner's private bot chat pairs the account.",
            "implemented",
            "advanced"),
        new ProductSupportModel.Article(
            "telegram:ar",
            "telegram",
            "ar",
            "ربط مالك تيليجرام",
            "ربط مالك تيليجرام\nالمحادثة الخاصة بالمالك مطلوبة.",
            "implemented",
            "advanced"));
  }

  static HybridSupportRetriever model() {
    return new HybridSupportRetriever(
        corpus(), HybridSupportRetriever.Mode.UNIFORM, null, 0, 0, 0, null);
  }

  @Test
  public void lightArabicNormalizationIsSymmetricAndRetainsProperServiceNames() {
    assertEquals(
        HybridSupportRetriever.normalize("بالمحادثة", true),
        HybridSupportRetriever.normalize("محادثه", true));
    assertEquals("واتساب", HybridSupportRetriever.normalize("واتساب", true));
    assertEquals("لا مش", HybridSupportRetriever.normalize("لا مش", true));
  }

  @Test
  public void mixedQuerySharesBilingualTopicAndKeepsSeparateOriginalReferences() {
    HybridSupportRetriever m = model();
    HybridSupportRetriever.Hit hit = m.search("Voice تصريح الميكروفون", 3).matches.get(0);
    assertEquals("voice", hit.topic.id);
    assertEquals(2, hit.topic.references.size());
    for (ProductSupportModel.Article a : hit.topic.references)
      assertTrue(
          corpus().stream()
              .anyMatch(original -> original.id.equals(a.id) && original.text.equals(a.text)));
    assertThrows(UnsupportedOperationException.class, () -> hit.topic.references.clear());
  }

  @Test
  public void confidenceIsBoundedAndMeaninglessQueriesAbstain() {
    HybridSupportRetriever m = model();
    assertFalse(m.search("", 2).accepted);
    assertFalse(m.search("how can i Omni", 2).accepted);
    for (HybridSupportRetriever.Hit hit : m.search("voice Telegram", 10).matches)
      assertTrue(
          Double.isFinite(hit.score)
              && hit.score >= 0
              && hit.score <= 1
              && hit.coverage >= 0
              && hit.coverage <= 1);
    assertThrows(IllegalArgumentException.class, () -> m.search("voice", 11));
  }

  @Test
  public void learnedWeightsAreConvexDefensivelyCopiedAndNineParameters() {
    float[] w = new float[8];
    Arrays.fill(w, .125f);
    HybridSupportRetriever m =
        new HybridSupportRetriever(corpus(), HybridSupportRetriever.Mode.LEARNED, w, 0, 0, 0, null);
    double before = m.search("voice permission", 1).matches.get(0).score;
    Arrays.fill(w, Float.NaN);
    assertEquals(before, m.search("voice permission", 1).matches.get(0).score, 0);
    assertEquals(9, m.parameterCount());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HybridSupportRetriever(
                corpus(), HybridSupportRetriever.Mode.LEARNED, w, 0, 0, 0, null));
  }

  @Test
  public void foreignCorpusFeatureVersionAndConflictingTranslationsAreRejected() {
    String fp = model().corpusFingerprint();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HybridSupportRetriever(
                corpus(), HybridSupportRetriever.Mode.RRF, null, 0, 0, 0, "wrong"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HybridSupportRetriever(
                corpus(), HybridSupportRetriever.Mode.RRF, null, 0, 0, 0, fp, "wrong-features"));
    List<ProductSupportModel.Article> bad = new ArrayList<>(corpus());
    bad.set(
        1,
        new ProductSupportModel.Article("voice:ar", "voice", "ar", "صوت", "صوت", "planned", "all"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HybridSupportRetriever(
                bad, HybridSupportRetriever.Mode.UNIFORM, null, 0, 0, 0, null));
  }

  @Test
  public void parallelQueriesAreDeterministicAndRowsCannotBeMutated() throws Exception {
    HybridSupportRetriever m = model();
    String expected = m.search("Telegram الخاصة", 1).matches.get(0).topic.id;
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<String>> futures = new ArrayList<>();
      for (int i = 0; i < 32; i++)
        futures.add(pool.submit(() -> m.search("Telegram الخاصة", 1).matches.get(0).topic.id));
      for (Future<String> f : futures) assertEquals(expected, f.get());
      HybridSupportRetriever.Row row = m.rows("voice permission").get(0);
      float[] features = row.featuresCopy();
      Arrays.fill(features, Float.NaN);
      for (float value : row.featuresCopy())
        assertTrue(Float.isFinite(value) && value >= 0 && value <= 1);
    } finally {
      pool.shutdownNow();
    }
  }
}
