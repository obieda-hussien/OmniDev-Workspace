package com.omnidev.workspace.domain.neural;

import com.omnidev.workspace.domain.neural.ProductSupportExperiment.Frame;
import com.omnidev.workspace.domain.neural.ProductSupportExperiment.Network;
import com.omnidev.workspace.domain.neural.ProductSupportExperiment.Policy;
import com.omnidev.workspace.domain.neural.ProductSupportExperiment.Stats;
import java.util.*;

/** Query-balanced pairwise ranking plus relevance loss, regularized around a lexical anchor. */
final class RankedSupportTrainer {
  static final class Fit {
    final Network network;
    final int epoch, ran;
    final double anchor, loss;
    final long seed;
    final Stats development;

    Fit(
        Network network,
        int epoch,
        int ran,
        double anchor,
        long seed,
        double loss,
        Stats development) {
      this.network = network;
      this.epoch = epoch;
      this.ran = ran;
      this.anchor = anchor;
      this.seed = seed;
      this.loss = loss;
      this.development = development;
    }
  }

  static Network anchored() {
    Network n = new Network(0, 0);
    Arrays.fill(n.w, 0);
    n.w[0] = 5;
    return n;
  }

  static double logit(double[] w, float[] x) {
    double z = w[12];
    for (int i = 0; i < 12; i++) z += w[i] * x[i];
    return z;
  }

  static double softplus(double z) {
    return Math.max(z, 0) + Math.log1p(Math.exp(-Math.abs(z)));
  }

  static void add(double[] g, float[] x, double dz) {
    for (int i = 0; i < 12; i++) g[i] += dz * x[i];
    g[12] += dz;
  }

  /** Also used by the finite-difference smoke test; labels only supervise TRAIN frames. */
  static double queryLoss(Frame f, double[] w, double[] g) {
    List<Integer> positive = new ArrayList<>(), negative = new ArrayList<>();
    for (int i = 0; i < f.pool.size(); i++) {
      if (f.pool.get(i).article.topic.equals(f.topic)) positive.add(i);
      else if (negative.size() < 8) negative.add(i);
    }
    double loss = 0;
    int pairs = positive.size() * negative.size();
    for (int p : positive)
      for (int n : negative) {
        double difference = logit(w, f.x.get(n)) - logit(w, f.x.get(p));
        double scale = .75 / Math.max(1, pairs);
        loss += scale * softplus(difference);
        double dz = scale * ProductSupportModel.sigmoid(difference);
        add(g, f.x.get(n), dz);
        add(g, f.x.get(p), -dz);
      }
    double balance =
        positive.isEmpty() ? 1 : Math.min(8, negative.size() / (double) positive.size());
    double denominator = balance * positive.size() + negative.size();
    for (int i : positive) {
      double z = logit(w, f.x.get(i)), scale = .25 * balance / Math.max(1, denominator);
      loss += scale * softplus(-z);
      add(g, f.x.get(i), scale * (ProductSupportModel.sigmoid(z) - 1));
    }
    for (int i : negative) {
      double z = logit(w, f.x.get(i)), scale = .25 / Math.max(1, denominator);
      loss += scale * softplus(z);
      add(g, f.x.get(i), scale * ProductSupportModel.sigmoid(z));
    }
    return loss;
  }

  static boolean languageFloor(List<Frame> dev, TinyNeuralRuntime runtime) {
    for (String language : new String[] {"en", "ar", "mixed"}) {
      List<Frame> rows = new ArrayList<>();
      for (Frame f : dev) if (f.language.equals(language)) rows.add(f);
      if (rows.isEmpty()) throw new IllegalArgumentException("Missing development language");
      if (ProductSupportExperiment.stats(rows, runtime, new Policy(0, 0)).top1
          < ProductSupportExperiment.stats(rows, null, new Policy(0, 0)).top1) return false;
    }
    return true;
  }

  static boolean better(Stats a, double loss, Stats b, double previousLoss) {
    return a.top1 > b.top1
        || a.top1 == b.top1
            && (a.reciprocal > b.reciprocal + 1e-12
                || Math.abs(a.reciprocal - b.reciprocal) < 1e-12 && loss < previousLoss - 1e-8);
  }

  static Fit fit(List<Frame> train, List<Frame> dev, double anchor, long seed) {
    if (train.isEmpty() || dev.isEmpty() || !Double.isFinite(anchor) || anchor <= 0)
      throw new IllegalArgumentException("Invalid ranking fit");
    for (Frame f : train)
      if (!f.split.equals("train"))
        throw new IllegalArgumentException("Non-training query entered fitting");
    for (Frame f : dev)
      if (!f.split.equals("development"))
        throw new IllegalArgumentException("Non-development selection query");
    Network n = anchored(), best = anchored();
    Stats bestStats = ProductSupportExperiment.stats(dev, n.runtime(), new Policy(0, 0));
    double bestLoss = ProductSupportExperiment.loss(dev, n.runtime());
    int bestEpoch = 0, ran = 0, step = 0;
    double[] m = new double[13], v = new double[13];
    Random random = new Random(seed);
    List<Frame> order = new ArrayList<>(train);
    for (int epoch = 1; epoch <= 80; epoch++) {
      Collections.shuffle(order, random);
      for (int start = 0; start < order.size(); start += 16) {
        double[] g = new double[13];
        int count = Math.min(16, order.size() - start);
        for (int i = start; i < start + count; i++) queryLoss(order.get(i), n.w, g);
        double norm = 0;
        for (int i = 0; i < 13; i++) {
          double delta = n.w[i] - (i == 0 ? 5 : 0);
          g[i] = g[i] / count + 2 * anchor * delta;
          norm += g[i] * g[i];
        }
        double scale = Math.sqrt(norm) > 5 ? 5 / Math.sqrt(norm) : 1;
        step++;
        for (int i = 0; i < 13; i++) {
          double d = g[i] * scale;
          m[i] = .9 * m[i] + .1 * d;
          v[i] = .999 * v[i] + .001 * d * d;
          double delta = n.w[i] - (i == 0 ? 5 : 0);
          n.w[i] -=
              .01
                  * ((m[i] / (1 - Math.pow(.9, step)))
                          / (Math.sqrt(v[i] / (1 - Math.pow(.999, step))) + 1e-8)
                      + 1e-4 * delta);
        }
      }
      TinyNeuralRuntime runtime = n.runtime();
      Stats stats = ProductSupportExperiment.stats(dev, runtime, new Policy(0, 0));
      double loss = ProductSupportExperiment.loss(dev, runtime);
      ran = epoch;
      if (languageFloor(dev, runtime) && better(stats, loss, bestStats, bestLoss)) {
        best = new Network(0, n.w);
        bestStats = stats;
        bestLoss = loss;
        bestEpoch = epoch;
      }
      if (epoch - bestEpoch >= 12) break;
    }
    return new Fit(best, bestEpoch, ran, anchor, seed, bestLoss, bestStats);
  }
}
