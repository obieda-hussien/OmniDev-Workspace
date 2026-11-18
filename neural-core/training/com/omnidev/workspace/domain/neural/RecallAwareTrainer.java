package com.omnidev.workspace.domain.neural;

import static com.omnidev.workspace.domain.neural.AnswerabilityTrainer.*;

import java.util.*;

/** Reserved positive-weighted fitting; the shared DEV selection objective always uses cost4. */
final class RecallAwareTrainer {
  static double objective(
      Row row, double[] weights, double[] gradient, double l2, int positiveWeight) {
    double z = weights[1024];
    for (int i : row.nonzero) z += weights[i] * row.x[i];
    double importance = row.label == 1 ? positiveWeight : 1;
    double delta = importance * (ProductSupportModel.sigmoid(z) - row.label);
    for (int i : row.nonzero) gradient[i] += delta * row.x[i];
    gradient[1024] += delta;
    double loss =
        importance * (Math.max(z, 0) - z * row.label + Math.log1p(Math.exp(-Math.abs(z))));
    for (int i = 0; i < weights.length; i++) {
      loss += l2 * weights[i] * weights[i];
      gradient[i] += 2 * l2 * weights[i];
    }
    return loss;
  }

  static double nll(List<Row> rows, Model model, double temperature) {
    double sum = 0;
    for (String language : new String[] {"en", "ar", "mixed"}) {
      double loss = 0;
      int count = 0;
      for (Row row : rows)
        if (row.language.equals(language)) {
          int importance = row.label == 1 ? 4 : 1;
          loss += importance * bce(model.probability(row, temperature), row.label);
          count += importance;
        }
      if (count == 0) throw new IllegalArgumentException("Missing pair language");
      sum += loss / count;
    }
    return sum / 3;
  }

  static void require(List<Row> rows, String split) {
    if (rows.isEmpty() || rows.size() > 10000)
      throw new IllegalArgumentException("Answerability split bound");
    for (Row row : rows)
      if (!row.split.equals(split))
        throw new IllegalArgumentException("Foreign answerability " + split + " row");
  }

  static Fit fit(
      List<Row> training, List<Row> development, long seed, double l2, int positiveWeight) {
    require(training, "train");
    require(development, "development");
    if ((positiveWeight != 1 && positiveWeight != 4) || (seed != 42 && seed != 97))
      throw new IllegalArgumentException("Unreserved recall fit");
    if (l2 != .0001 && l2 != .001)
      throw new IllegalArgumentException("Unreserved answerability regularization");
    Set<String> groups = new HashSet<>();
    boolean[] labels = new boolean[2];
    for (Row row : training) {
      groups.add(row.group);
      labels[row.label] = true;
    }
    if (!labels[0] || !labels[1])
      throw new IllegalArgumentException("Missing answerability target");
    for (Row row : development)
      if (groups.contains(row.group))
        throw new IllegalArgumentException("Answerability group leakage");
    double[] w = new double[1025], m = new double[1025], v = new double[1025];
    Random random = new Random(seed);
    for (int i = 0; i < 1024; i++) w[i] = random.nextGaussian() * .01;
    Model best = new Model(w);
    double bestNll = nll(development, best, 1);
    int epochBest = 0, ran = 0, step = 0;
    List<Row> order = new ArrayList<>(training);
    for (int epoch = 1; epoch <= 100; epoch++) {
      Collections.shuffle(order, random);
      for (int start = 0; start < order.size(); start += 32) {
        int count = Math.min(32, order.size() - start);
        double[] gradient = new double[1025];
        for (int i = start; i < start + count; i++)
          objective(order.get(i), w, gradient, l2, positiveWeight);
        double norm = 0;
        for (int i = 0; i < gradient.length; i++) {
          gradient[i] /= count;
          norm += gradient[i] * gradient[i];
        }
        double scale = norm > 25 ? 5 / Math.sqrt(norm) : 1;
        step++;
        for (int i = 0; i < w.length; i++) {
          double g = gradient[i] * scale;
          m[i] = .9 * m[i] + .1 * g;
          v[i] = .999 * v[i] + .001 * g * g;
          w[i] -=
              .01
                  * ((m[i] / (1 - Math.pow(.9, step)))
                          / (Math.sqrt(v[i] / (1 - Math.pow(.999, step))) + 1e-8)
                      + 1e-4 * w[i]);
        }
      }
      Model model = new Model(w);
      double loss = nll(development, model, 1);
      ran = epoch;
      if (loss < bestNll - 1e-8) {
        best = model;
        bestNll = loss;
        epochBest = epoch;
      }
      if (epoch - epochBest >= 12) break;
    }
    return new Fit(best, epochBest, ran, seed, l2, bestNll);
  }
}
