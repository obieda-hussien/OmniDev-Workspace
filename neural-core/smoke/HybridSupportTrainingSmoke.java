package com.omnidev.workspace.domain.neural;

import java.nio.file.*;
import java.util.*;

public final class HybridSupportTrainingSmoke {
  public static void main(String[] args) throws Exception {
    List<ProductSupportModel.Article> docs =
        ProductSupportExperiment.loadArticles(Paths.get("build/product-support/documents.tsv"));
    HybridSupportRetriever index =
        new HybridSupportRetriever(docs, HybridSupportRetriever.Mode.LEGACY, null, 0, 0, 0, null);
    List<HybridSupportExperiment.Query> train = HybridSupportExperiment.old("train", index),
        dev = HybridSupportExperiment.old("development", index);
    int checked = 0;
    for (HybridSupportExperiment.Query q : train) {
      if (q.primary.isEmpty() || q.rows.size() < 10) continue;
      boolean positive = false;
      for (HybridSupportRetriever.Row row : q.rows) positive |= q.primary.contains(row.topic.id);
      if (!positive) continue;
      double[] w = new double[8];
      for (int i = 0; i < 8; i++) w[i] = .125 + .01 * Math.sin(i + 1);
      double[] g = new double[8];
      HybridSupportExperiment.queryLoss(q, w, g);
      for (int i = 0; i < 8; i++) {
        double before = w[i], h = 1e-5;
        w[i] = before + h;
        double plus = HybridSupportExperiment.queryLoss(q, w, new double[8]);
        w[i] = before - h;
        double minus = HybridSupportExperiment.queryLoss(q, w, new double[8]);
        w[i] = before;
        if (Math.abs(g[i] - (plus - minus) / (2 * h)) > 1e-6)
          throw new AssertionError("Hybrid gradient mismatch " + q.id + " " + i);
        checked++;
      }
      if (checked >= 16) break;
    }
    if (checked != 16) throw new AssertionError("Insufficient actual gradient probes");
    double[] projected =
        HybridSupportExperiment.project(new double[] {-2, .3, 5, -1, .4, .6, .7, .2});
    double sum = 0;
    for (double v : projected) {
      if (v < 0 || !Double.isFinite(v)) throw new AssertionError("Invalid simplex");
      sum += v;
    }
    if (Math.abs(sum - 1) > 1e-12) throw new AssertionError("Unnormalized simplex");
    boolean rejected = false;
    try {
      HybridSupportExperiment.fit(Collections.singletonList(dev.get(0)), dev, .05, 42);
    } catch (IllegalArgumentException expected) {
      rejected = true;
    }
    if (!rejected) throw new AssertionError("Foreign fitting partition");
    System.out.println(
        "Hybrid training: 16 finite-difference coordinates, simplex and partition contracts"
            + " passed");
  }
}
