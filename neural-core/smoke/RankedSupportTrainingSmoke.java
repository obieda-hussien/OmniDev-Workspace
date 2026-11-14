package com.omnidev.workspace.domain.neural;

import com.omnidev.workspace.domain.neural.ProductSupportExperiment.Frame;
import java.nio.file.*;
import java.util.*;

/**
 * Independent finite-difference validation of the actual query objective, plus split/boundary
 * checks.
 */
public final class RankedSupportTrainingSmoke {
  public static void main(String[] args) throws Exception {
    List<ProductSupportModel.Article> articles =
        ProductSupportExperiment.loadArticles(Paths.get("build/product-support/documents.tsv"));
    ProductSupportModel base = new ProductSupportModel(articles, null, 0, 0, null);
    Path old = Paths.get("neural-core/fixtures/product-support/queries.tsv");
    List<Frame> train = ProductSupportExperiment.frames(old, "train", base),
        dev = ProductSupportExperiment.frames(old, "development", base);
    Frame known = null, unknown = null;
    for (Frame f : train) {
      if (f.topic.equals("-")) unknown = f;
      else
        for (ProductSupportModel.Match m : f.pool) if (m.article.topic.equals(f.topic)) known = f;
    }
    if (known == null || unknown == null)
      throw new AssertionError("Missing supervised gradient rows");
    int checked = 0;
    for (Frame f : Arrays.asList(known, unknown)) {
      double[] w = RankedSupportTrainer.anchored().w;
      for (int i = 0; i < w.length; i++) w[i] += .03 * Math.sin(i + 1);
      double[] g = new double[13];
      double loss = RankedSupportTrainer.queryLoss(f, w, g);
      if (!Double.isFinite(loss)) throw new AssertionError("Invalid objective");
      for (int i = 0; i < 13; i++) {
        double value = w[i], step = 1e-5;
        w[i] = value + step;
        double plus = RankedSupportTrainer.queryLoss(f, w, new double[13]);
        w[i] = value - step;
        double minus = RankedSupportTrainer.queryLoss(f, w, new double[13]);
        w[i] = value;
        double numeric = (plus - minus) / (2 * step);
        if (Math.abs(g[i] - numeric) > 1e-6)
          throw new AssertionError(
              "Gradient mismatch " + i + " actual=" + g[i] + " expected=" + numeric);
        checked++;
      }
    }
    boolean rejected = false;
    try {
      RankedSupportTrainer.fit(Collections.singletonList(dev.get(0)), dev, .02, 42);
    } catch (IllegalArgumentException expected) {
      rejected = true;
    }
    if (!rejected) throw new AssertionError("Development query entered fitting");
    rejected = false;
    try {
      RankedSupportTrainer.fit(train, Collections.singletonList(train.get(0)), .02, 42);
    } catch (IllegalArgumentException expected) {
      rejected = true;
    }
    if (!rejected) throw new AssertionError("Training query entered selection");
    if (RankedSupportTrainer.anchored().runtime().parameterCount() != 13)
      throw new AssertionError("Unbounded ranking head");
    System.out.println(
        "Ranked support objective verified: "
            + checked
            + " finite-difference coordinates; split rejection and 13-parameter bound");
  }
}
