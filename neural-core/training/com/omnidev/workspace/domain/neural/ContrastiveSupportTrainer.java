package com.omnidev.workspace.domain.neural;

import static com.omnidev.workspace.domain.neural.AnswerabilityTrainer.*;

import java.util.*;

/** Unweighted BCE plus optional TRAIN-only same-scenario pair ordering; bounded sparse AdamW. */
final class ContrastiveSupportTrainer {
  static final class Pair {
    final Row positive, negative;

    Pair(Row positive, Row negative) {
      if (!positive.split.equals("train")
          || !negative.split.equals("train")
          || positive.label != 1
          || negative.label != 0
          || !positive.group.equals(negative.group)
          || !positive.language.equals(negative.language))
        throw new IllegalArgumentException("Contrastive pair leakage/label");
      this.positive = positive;
      this.negative = negative;
    }
  }

  static final class Model {
    final float[] weights;
    final int hidden;
    final TinyNeuralRuntime runtime;

    Model(double[] values, int hidden) {
      weights = new float[values.length];
      for (int i = 0; i < values.length; i++) weights[i] = (float) values[i];
      this.hidden = hidden;
      runtime = SupportContrastiveModel.runtime(weights, hidden);
    }

    double probability(Row row, double temperature) {
      return ProductSupportModel.sigmoid(runtime.infer(row.x)[0] / temperature);
    }
  }

  static final class Fit {
    final Model model;
    final int epoch, ran;
    final long seed;
    final double ranking, nll;

    Fit(Model model, int epoch, int ran, long seed, double ranking, double nll) {
      this.model = model;
      this.epoch = epoch;
      this.ran = ran;
      this.seed = seed;
      this.ranking = ranking;
      this.nll = nll;
    }

    String json() {
      return "{\"hidden\":"
          + model.hidden
          + ",\"ranking_weight\":"
          + ranking
          + ",\"seed\":"
          + seed
          + ",\"epoch\":"
          + epoch
          + ",\"epochs_run\":"
          + ran
          + ",\"parameters\":"
          + model.weights.length
          + ",\"development_nll\":"
          + nll
          + "}";
    }
  }

  static double forward(Row row, double[] w, int hidden, double[] activation) {
    if (hidden == 0) {
      double z = w[1024];
      for (int i : row.nonzero) z += w[i] * row.x[i];
      return z;
    }
    double z = w[8208];
    for (int h = 0; h < 8; h++) {
      double a = w[8192 + h];
      for (int i : row.nonzero) a += w[h * 1024 + i] * row.x[i];
      activation[h] = Math.tanh(a);
      z += w[8200 + h] * activation[h];
    }
    return z;
  }

  static void backward(
      Row row, double[] w, int hidden, double[] activation, double delta, double[] g) {
    if (hidden == 0) {
      for (int i : row.nonzero) g[i] += delta * row.x[i];
      g[1024] += delta;
      return;
    }
    g[8208] += delta;
    for (int h = 0; h < 8; h++) {
      g[8200 + h] += delta * activation[h];
      double d = delta * w[8200 + h] * (1 - activation[h] * activation[h]);
      g[8192 + h] += d;
      for (int i : row.nonzero) g[h * 1024 + i] += d * row.x[i];
    }
  }

  static double objective(Row row, double[] w, int hidden, double[] g) {
    double[] a = new double[8];
    double z = forward(row, w, hidden, a);
    backward(row, w, hidden, a, ProductSupportModel.sigmoid(z) - row.label, g);
    return Math.max(z, 0) - z * row.label + Math.log1p(Math.exp(-Math.abs(z)));
  }

  static double pairObjective(Pair pair, double[] w, int hidden, double[] g) {
    double[] a = new double[8], b = new double[8];
    double gap = forward(pair.positive, w, hidden, a) - forward(pair.negative, w, hidden, b);
    double delta = -ProductSupportModel.sigmoid(-gap);
    backward(pair.positive, w, hidden, a, delta, g);
    backward(pair.negative, w, hidden, b, -delta, g);
    return Math.max(-gap, 0) + Math.log1p(Math.exp(-Math.abs(gap)));
  }

  static List<Pair> pairs(List<Row> training) {
    Map<String, Row[]> groups = new TreeMap<>();
    Set<String> ids = new HashSet<>();
    for (Row row : training) {
      if (!row.split.equals("train") || !ids.add(row.id))
        throw new IllegalArgumentException("Contrastive TRAIN role/identity");
      if (!row.id.startsWith("answer8-contrast-")) continue;
      Row[] pair = groups.computeIfAbsent(row.group + "|" + row.language, k -> new Row[2]);
      if (pair[row.label] != null)
        throw new IllegalArgumentException("Duplicate contrast polarity");
      pair[row.label] = row;
    }
    List<Pair> out = new ArrayList<>();
    for (Row[] rows : groups.values()) {
      if (rows[0] == null || rows[1] == null)
        throw new IllegalArgumentException("Missing contrast polarity");
      out.add(new Pair(rows[1], rows[0]));
    }
    if (out.size() != 48) throw new IllegalArgumentException("Expected48 TRAIN contrasts");
    return out;
  }

  static double nll(List<Row> rows, Model model, double temperature) {
    double sum = 0;
    for (String language : new String[] {"en", "ar", "mixed"}) {
      double loss = 0;
      int count = 0;
      for (Row row : rows)
        if (row.language.equals(language)) {
          loss += bce(model.probability(row, temperature), row.label);
          count++;
        }
      if (count == 0) throw new IllegalArgumentException("Missing contrastive language");
      sum += loss / count;
    }
    return sum / 3;
  }

  static Fit fit(List<Row> training, List<Row> development, int hidden, double ranking, long seed) {
    AnswerabilityTrainer.require(training, "train");
    AnswerabilityTrainer.require(development, "development");
    if ((hidden != 0 && hidden != 8)
        || (ranking != 0 && ranking != .25)
        || (seed != 42 && seed != 97))
      throw new IllegalArgumentException("Unreserved contrastive fit");
    Set<String> groups = new HashSet<>();
    for (Row row : training) groups.add(row.group);
    for (Row row : development)
      if (groups.contains(row.group))
        throw new IllegalArgumentException("Contrastive group leakage");
    List<Pair> pairOrder = pairs(training);
    List<Row> order = new ArrayList<>(training);
    double[] w = new double[hidden == 0 ? 1025 : 8209],
        m = new double[w.length],
        v = new double[w.length];
    Random rng = new Random(seed);
    if (hidden == 0) {
      for (int i = 0; i < 1024; i++) w[i] = rng.nextGaussian() * .01;
    } else {
      for (int i = 0; i < 8192; i++) w[i] = rng.nextGaussian() * Math.sqrt(2.0 / 1032);
      for (int i = 8200; i < 8208; i++) w[i] = rng.nextGaussian() / Math.sqrt(8);
    }
    Model best = new Model(w, hidden);
    double bestNll = nll(development, best, 1);
    int chosen = 0, ran = 0, step = 0;
    for (int epoch = 1; epoch <= 100; epoch++) {
      Collections.shuffle(order, rng);
      Collections.shuffle(pairOrder, rng);
      int batch = 0;
      for (int start = 0; start < order.size(); start += 32, batch++) {
        int count = Math.min(32, order.size() - start);
        double[] g = new double[w.length];
        for (int i = start; i < start + count; i++) objective(order.get(i), w, hidden, g);
        for (int i = 0; i < g.length; i++) g[i] /= count;
        if (ranking > 0) {
          double[] pg = new double[w.length];
          for (int i = 0; i < 16; i++)
            pairObjective(pairOrder.get((batch * 16 + i) % pairOrder.size()), w, hidden, pg);
          for (int i = 0; i < g.length; i++) g[i] += ranking * pg[i] / 16;
        }
        double norm = 0;
        for (int i = 0; i < g.length; i++) {
          g[i] += .002 * w[i];
          norm += g[i] * g[i];
        }
        double scale = norm > 25 ? 5 / Math.sqrt(norm) : 1;
        step++;
        double bc1 = 1 - Math.pow(.9, step), bc2 = 1 - Math.pow(.999, step);
        for (int i = 0; i < w.length; i++) {
          double d = g[i] * scale;
          m[i] = .9 * m[i] + .1 * d;
          v[i] = .999 * v[i] + .001 * d * d;
          w[i] -= .003 * ((m[i] / bc1) / (Math.sqrt(v[i] / bc2) + 1e-8) + 1e-4 * w[i]);
        }
      }
      Model model = new Model(w, hidden);
      double loss = nll(development, model, 1);
      ran = epoch;
      if (loss < bestNll - 1e-8) {
        best = model;
        bestNll = loss;
        chosen = epoch;
      }
      if (epoch - chosen >= 12) break;
    }
    return new Fit(best, chosen, ran, seed, ranking, bestNll);
  }
}
