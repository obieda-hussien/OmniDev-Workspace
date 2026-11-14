package com.omnidev.workspace.domain.neural;

import static com.omnidev.workspace.domain.neural.HybridSupportExperiment.*;

import java.nio.file.*;
import java.util.*;

/** Numerical and split contracts against the actual expanded training queries. */
public final class ExpandedSupportTrainingSmoke {
  public static void main(String[] args) throws Exception {
    HybridSupportRetriever index =
        new HybridSupportRetriever(
            ProductSupportExperiment.loadArticles(Paths.get("build/product-support/documents.tsv")),
            HybridSupportRetriever.Mode.LEGACY,
            null,
            0,
            0,
            0,
            null);
    List<Query> train = ExpandedSupportExperiment.load("train", index),
        dev = ExpandedSupportExperiment.load("development", index),
        cal = ExpandedSupportExperiment.load("calibration", index);
    List<Query> all = ExpandedSupportExperiment.observed(index);
    Set<String> ids = new HashSet<>();
    for (Query q : all) if (!ids.add(q.id)) throw new AssertionError("Duplicate old query");
    for (String split : new String[] {"train", "development", "calibration", "fresh"})
      for (Query q : ExpandedSupportExperiment.load(split, index))
        if (!ids.add(q.id)) throw new AssertionError("Duplicate new query");
    if (ids.size() != 1101 || train.size() != 297 || dev.size() != 99 || cal.size() != 99)
      throw new AssertionError("Expanded corpus incomplete");
    int n = 0;
    for (Query q : train) {
      double[] w = {.13, .11, .14, .12, .13, .11, .14, .12}, g = new double[8];
      if (queryLoss(q, w, g) == 0) continue;
      for (int i = 0; i < 8; i++) {
        double before = w[i], h = 1e-5;
        w[i] = before + h;
        double plus = queryLoss(q, w, new double[8]);
        w[i] = before - h;
        double minus = queryLoss(q, w, new double[8]);
        w[i] = before;
        if (Math.abs(g[i] - (plus - minus) / (2 * h)) > 1e-6)
          throw new AssertionError("Expanded gradient drift");
        n++;
      }
      if (n >= 24) break;
    }
    if (n != 24) throw new AssertionError("Insufficient gradient checks");
    boolean rejected = false;
    try {
      ExpandedSupportExperiment.select(train, dev, Collections.singletonList(dev.get(0)));
    } catch (IllegalArgumentException expected) {
      rejected = true;
    }
    if (!rejected) throw new AssertionError("Foreign calibration accepted");
    rejected = false;
    try {
      ExpandedSupportExperiment.select(Collections.singletonList(cal.get(0)), dev, cal);
    } catch (IllegalArgumentException expected) {
      rejected = true;
    }
    if (!rejected) throw new AssertionError("Foreign fitting query accepted");
    // Scoring the immutable checkpoint through rows must agree with trainer features.
    Query q = train.get(0);
    Config c = new Config(HybridSupportRetriever.Mode.UNIFORM, null, 0, 0, 0, 0);
    Policy p = new Policy(0, 0, 0);
    HybridSupportRetriever model =
        HybridSupportExperiment.model(
            ProductSupportExperiment.loadArticles(Paths.get("build/product-support/documents.tsv")),
            c,
            p,
            index.corpusFingerprint());
    if (!metrics(Collections.singletonList(q), c, p)
        .json()
        .equals(ExpandedSupportExperiment.measured(Collections.singletonList(q), model).json()))
      throw new AssertionError("Metric ranking paths disagree");
    System.out.println(
        "Expanded training: 24 actual gradient coordinates, 1101 unique queries, metric parity and"
            + " split barriers passed");
  }
}
