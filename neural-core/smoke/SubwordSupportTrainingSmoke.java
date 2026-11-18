package com.omnidev.workspace.domain.neural;

import static com.omnidev.workspace.domain.neural.HybridSupportExperiment.*;
import static com.omnidev.workspace.domain.neural.SupportReliabilityExperiment.*;

import java.nio.file.*;
import java.util.*;

public final class SubwordSupportTrainingSmoke {
  public static void main(String[] args) throws Exception {
    List<ProductSupportModel.Article> docs =
        ProductSupportExperiment.loadArticles(Paths.get("build/product-support/documents.tsv"));
    List<SupportQueryMemory.Exemplar> examples =
        QueryMemoryExperiment.examples(Paths.get("build/query-memory-v5/exemplars.tsv"));
    SupportQueryMemory memory = TrainedQueryMemory.createCandidate(docs, examples);
    TrainTokenCoverage tokens = new TrainTokenCoverage(examples);
    HybridSupportRetriever base = QueryMemoryExperiment.base(docs);
    List<Query> original = old("train", base);
    original.addAll(ExpandedSupportExperiment.load("train", base));
    List<Query> variants = new ArrayList<>();
    for (Query q : original)
      variants.add(
          new Query(
              q.id,
              "train",
              q.language,
              SubwordSupportExperiment.typo(q.id, q.text),
              q.primary,
              q.secondary,
              base));
    List<Datum> train = SubwordSupportExperiment.enriched(variants, memory, tokens, true),
        dev =
            SubwordSupportExperiment.enriched(
                ExpandedSupportExperiment.load("development", base), memory, tokens, false),
        cal =
            SubwordSupportExperiment.enriched(
                ExpandedSupportExperiment.load("calibration", base), memory, tokens, false);
    int coordinates = 0;
    for (int width : new int[] {0, 8}) {
      ProductSupportExperiment.Network n = new ProductSupportExperiment.Network(width, 97);
      int checked = 0;
      for (Datum d : train) {
        if (d.frame.hits.isEmpty()) continue;
        double[] g = new double[n.w.length];
        objective(d, n, g, .01);
        for (int i = 0; i < g.length; i++) {
          double old = n.w[i], h = 1e-5;
          n.w[i] = old + h;
          double plus = objective(d, n, new double[g.length], .01);
          n.w[i] = old - h;
          double minus = objective(d, n, new double[g.length], .01);
          n.w[i] = old;
          if (Math.abs(g[i] - (plus - minus) / (2 * h)) > 1e-7)
            throw new AssertionError("Actual subword-view gradient mismatch");
          coordinates++;
        }
        if (++checked == 2) break;
      }
    }
    if (coordinates != 252 || train.size() != 411)
      throw new AssertionError("Subword smoke incomplete");
    for (int i = 0; i < original.size(); i++) {
      Query q = original.get(i), v = variants.get(i);
      if (!q.id.equals(v.id)
          || !q.primary.equals(v.primary)
          || !v.text.equals(SubwordSupportExperiment.typo(q.id, q.text))
          || q.text.codePointCount(0, q.text.length()) - v.text.codePointCount(0, v.text.length())
              > 1) throw new AssertionError("Unstable or excessive typo augmentation");
    }
    SupportReliabilityTrainingSmoke.rejects(() -> fit(cal, dev, 0, 0, 42));
    SupportReliabilityTrainingSmoke.rejects(
        () ->
            SubwordSupportExperiment.calibrateSoft(
                calibrationPart(cal, true),
                new ProductSupportExperiment.Network(0, 42).runtime(),
                1));
    Fit one = fit(train, dev, 0, 0, 42), two = fit(train, dev, 0, 0, 42);
    if (!Arrays.equals(one.n.w, two.n.w) || one.epoch != two.epoch)
      throw new AssertionError("Nonreproducible subword fit");
    System.out.println(
        "Subword training: 252 actual augmented-view gradient coordinates, group inheritance,"
            + " deterministic fit and calibration-role rejection passed");
  }
}
