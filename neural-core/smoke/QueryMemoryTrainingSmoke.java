package com.omnidev.workspace.domain.neural;

import static com.omnidev.workspace.domain.neural.HybridSupportExperiment.*;

import java.nio.file.*;
import java.util.*;

public final class QueryMemoryTrainingSmoke {
  public static void main(String[] args) throws Exception {
    List<ProductSupportModel.Article> docs =
        ProductSupportExperiment.loadArticles(Paths.get("build/product-support/documents.tsv"));
    HybridSupportRetriever base = QueryMemoryExperiment.base(docs);
    List<SupportQueryMemory.Exemplar> ex =
        QueryMemoryExperiment.examples(Paths.get("build/query-memory-v5/exemplars.tsv"));
    SupportQueryMemory m =
        new SupportQueryMemory(
            docs, base, ex, new float[] {1, 0, 0}, 0, 0, 0, null, SupportQueryMemory.VERSION);
    List<Query> labels = old("train", base);
    labels.addAll(ExpandedSupportExperiment.load("train", base));
    List<QueryMemoryExperiment.Q> train =
        QueryMemoryExperiment.rows(labels, m, QueryMemoryExperiment.trainingGroups(), true);
    int n = 0;
    for (QueryMemoryExperiment.Q q : train) {
      double[] w = {.3, .4, .3}, g = new double[3];
      if (QueryMemoryExperiment.loss(q, w, g) == 0) continue;
      for (int i = 0; i < 3; i++) {
        double old = w[i], h = 1e-5;
        w[i] = old + h;
        double plus = QueryMemoryExperiment.loss(q, w, new double[3]);
        w[i] = old - h;
        double minus = QueryMemoryExperiment.loss(q, w, new double[3]);
        w[i] = old;
        if (Math.abs(g[i] - (plus - minus) / (2 * h)) > 1e-6)
          throw new AssertionError("Memory gradient mismatch");
        n++;
      }
      if (n >= 12) break;
    }
    if (n != 12 || train.size() != 411 || ex.size() != 351)
      throw new AssertionError("Incomplete memory training probes");
    Map<String, String> invalid = new HashMap<>(QueryMemoryExperiment.trainingGroups());
    invalid.remove(labels.get(0).id);
    boolean rejected = false;
    try {
      QueryMemoryExperiment.rows(labels, m, invalid, true);
    } catch (IllegalArgumentException expected) {
      rejected = true;
    }
    if (!rejected) throw new AssertionError("Unattributed training row");
    List<QueryMemoryExperiment.Q> dev =
        QueryMemoryExperiment.rows(
            ExpandedSupportExperiment.load("development", base), m, Collections.emptyMap(), false);
    rejected = false;
    try {
      QueryMemoryExperiment.calibrate(
          dev, new QueryMemoryExperiment.C("base", new float[] {1, 0, 0}, 0, 0, 0, 0));
    } catch (IllegalArgumentException expected) {
      rejected = true;
    }
    if (!rejected) throw new AssertionError("Foreign memory calibration");
    System.out.println(
        "Query memory: 12 actual gradient coordinates; 411 attributed TRAIN rows and calibration"
            + " barriers passed");
  }
}
