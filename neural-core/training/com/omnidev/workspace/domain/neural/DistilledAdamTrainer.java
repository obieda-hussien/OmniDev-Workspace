package com.omnidev.workspace.domain.neural;

import java.util.*;

/**
 * Desktop-only mini-batch AdamW with supervised soft targets, gradient clipping and utility-aligned
 * stopping.
 */
public final class DistilledAdamTrainer {
  static final class Example {
    final String group, language;
    final int label;
    final float[] x;
    final int[] nonzero;
    final double[] target;

    Example(String group, String language, int label, float[] x, double[] target) {
      if (group == null
          || group.isEmpty()
          || group.length() > 160
          || !Arrays.asList("ar", "en", "mixed").contains(language)
          || label < 0
          || label >= 6
          || x == null
          || x.length != 1024
          || target == null
          || target.length != 6) throw new IllegalArgumentException("Invalid distilled example");
      int count = 0;
      for (float v : x) {
        if (!Float.isFinite(v) || Math.abs(v) > 1)
          throw new IllegalArgumentException("Invalid distilled features");
        if (v != 0) count++;
      }
      double sum = 0;
      for (double v : target) {
        if (!Double.isFinite(v) || v < 0 || v > 1)
          throw new IllegalArgumentException("Invalid distilled target");
        sum += v;
      }
      if (Math.abs(sum - 1) > 1e-8)
        throw new IllegalArgumentException("Unnormalized distilled target");
      this.group = group;
      this.language = language;
      this.label = label;
      this.x = x.clone();
      this.target = target.clone();
      nonzero = new int[count];
      int index = 0;
      for (int i = 0; i < x.length; i++) if (x[i] != 0) nonzero[index++] = i;
    }
  }

  static final class Model {
    final int hidden;
    final float[] parameters;
    private final TinyNeuralRuntime runtime;

    Model(int hidden, float[] parameters) {
      if ((hidden != 0 && hidden != 16) || parameters == null || parameters.length != size(hidden))
        throw new IllegalArgumentException("Invalid distilled model");
      this.hidden = hidden;
      this.parameters = parameters.clone();
      int output = hidden == 0 ? 0 : 1024 * hidden + hidden, width = hidden == 0 ? 1024 : hidden;
      TinyNeuralRuntime.Dense last =
          new TinyNeuralRuntime.Dense(
              width,
              6,
              Arrays.copyOfRange(parameters, output, output + width * 6),
              Arrays.copyOfRange(parameters, output + width * 6, parameters.length),
              TinyNeuralRuntime.Activation.LINEAR);
      runtime =
          hidden == 0
              ? new TinyNeuralRuntime(last)
              : new TinyNeuralRuntime(
                  new TinyNeuralRuntime.Dense(
                      1024,
                      hidden,
                      Arrays.copyOfRange(parameters, 0, 1024 * hidden),
                      Arrays.copyOfRange(parameters, 1024 * hidden, output),
                      TinyNeuralRuntime.Activation.TANH),
                  last);
    }

    TinyNeuralRuntime runtime() {
      return runtime;
    }

    String javaSource(
        double temperature,
        double[] reject,
        double[] change,
        double[] support,
        String fingerprint) {
      StringBuilder s =
          new StringBuilder(
                  "import com.omnidev.workspace.domain.neural.*;\n"
                      + "public final class TrainedUtilityExpert {\n"
                      + "public static final double TEMPERATURE=")
              .append(temperature)
              .append(";\npublic static final String VOCABULARY_SHA256=\"")
              .append(fingerprint)
              .append("\";\npublic static double[] reject(){return new double[]{");
      doubles(s, reject);
      s.append("};}\npublic static double[] change(){return new double[]{");
      doubles(s, change);
      s.append("};}\npublic static double[] support(){return new double[]{");
      doubles(s, support);
      s.append("};}\npublic static TinyNeuralRuntime model(){return new TinyNeuralRuntime(");
      int output = hidden == 0 ? 0 : 1024 * hidden + hidden, width = hidden == 0 ? 1024 : hidden;
      if (hidden != 0)
        s.append(
            "new TinyNeuralRuntime.Dense(1024,16,first(),firstBias(),TinyNeuralRuntime.Activation.TANH),");
      s.append("new TinyNeuralRuntime.Dense(")
          .append(width)
          .append(",6,last(),lastBias(),TinyNeuralRuntime.Activation.LINEAR));}\n");
      if (hidden != 0) {
        OfflineDecisionTrainer.Model.appendArrayFactory(
            s, "first", Arrays.copyOfRange(parameters, 0, 1024 * hidden));
        OfflineDecisionTrainer.Model.appendArrayFactory(
            s, "firstBias", Arrays.copyOfRange(parameters, 1024 * hidden, output));
      }
      OfflineDecisionTrainer.Model.appendArrayFactory(
          s, "last", Arrays.copyOfRange(parameters, output, output + width * 6));
      OfflineDecisionTrainer.Model.appendArrayFactory(
          s, "lastBias", Arrays.copyOfRange(parameters, output + width * 6, parameters.length));
      return s.append("}\n").toString();
    }

    private static void doubles(StringBuilder s, double[] a) {
      for (int i = 0; i < a.length; i++) {
        if (i > 0) s.append(',');
        s.append(a[i]);
      }
    }
  }

  static final class Result {
    final Model model;
    final int epoch, epochsRun;
    final double accuracy, loss;

    Result(Model model, int epoch, int epochsRun, double accuracy, double loss) {
      this.model = model;
      this.epoch = epoch;
      this.epochsRun = epochsRun;
      this.accuracy = accuracy;
      this.loss = loss;
    }
  }

  static Result fit(List<Example> training, List<Example> development, int hidden, long seed) {
    if (training == null
        || training.isEmpty()
        || training.size() > 100_000
        || development == null
        || development.isEmpty()
        || development.size() > 10_000
        || (hidden != 0 && hidden != 16))
      throw new IllegalArgumentException("Invalid bounded Adam training");
    Set<String> groups = new HashSet<>();
    boolean[] present = new boolean[6];
    for (Example e : training) {
      groups.add(e.group);
      present[e.label] = true;
    }
    for (boolean p : present) if (!p) throw new IllegalArgumentException("Missing distilled class");
    for (Example e : development)
      if (groups.contains(e.group)) throw new IllegalArgumentException("Distilled group leakage");
    Random random = new Random(seed);
    float[] p = new float[size(hidden)];
    int output = hidden == 0 ? 0 : 1024 * hidden + hidden, width = hidden == 0 ? 1024 : hidden;
    if (hidden != 0) initialize(p, 0, 1024 * hidden, Math.sqrt(6.0 / (1024 + hidden)), random);
    initialize(p, output, width * 6, Math.sqrt(6.0 / (width + 6)), random);
    boolean[] weights = new boolean[p.length];
    Arrays.fill(weights, output, output + width * 6, true);
    if (hidden != 0) Arrays.fill(weights, 0, 1024 * hidden, true);
    double[] first = new double[p.length],
        second = new double[p.length],
        gradient = new double[p.length];
    List<Example> order = new ArrayList<>(training);
    Result best = null;
    int step = 0, epochsRun = 0;
    for (int epoch = 1; epoch <= 100; epoch++) {
      Collections.shuffle(order, random);
      for (int start = 0; start < order.size(); start += 32) {
        Arrays.fill(gradient, 0);
        int end = Math.min(order.size(), start + 32);
        for (int j = start; j < end; j++) accumulate(p, hidden, order.get(j), gradient);
        for (int i = 0; i < gradient.length; i++) gradient[i] /= end - start;
        clip(gradient, 1);
        step++;
        update(p, gradient, first, second, weights, step);
      }
      epochsRun = epoch;
      if (epoch % 5 == 0) {
        Model snapshot = new Model(hidden, p);
        double[] score = evaluate(snapshot, development, 1);
        if (!Double.isFinite(score[0]) || !Double.isFinite(score[1]))
          throw new IllegalStateException("Nonfinite distilled training");
        if (best == null
            || score[0] > best.accuracy + 1e-12
            || Math.abs(score[0] - best.accuracy) <= 1e-12 && score[1] < best.loss)
          best = new Result(snapshot, epoch, epoch, score[0], score[1]);
        if (epoch - best.epoch >= 15) break;
      }
    }
    return new Result(best.model, best.epoch, epochsRun, best.accuracy, best.loss);
  }

  static int size(int hidden) {
    return hidden == 0 ? 6150 : 16502;
  }

  private static void initialize(float[] p, int offset, int count, double scale, Random random) {
    for (int i = 0; i < count; i++) p[offset + i] = (float) ((random.nextDouble() * 2 - 1) * scale);
  }

  static void accumulate(float[] p, int hidden, Example e, double[] gradient) {
    int width = hidden == 0 ? 1024 : hidden, output = hidden == 0 ? 0 : 1024 * hidden + hidden;
    float[] h = hidden == 0 ? e.x : new float[hidden];
    if (hidden != 0)
      for (int c = 0; c < hidden; c++) {
        double sum = p[1024 * hidden + c];
        for (int i : e.nonzero) sum += (double) p[c * 1024 + i] * e.x[i];
        h[c] = (float) Math.tanh(sum);
      }
    double[] errors = new double[6];
    double max = -Double.MAX_VALUE, sum = 0;
    for (int c = 0; c < 6; c++) {
      double value = p[output + width * 6 + c];
      if (hidden == 0) {
        for (int i : e.nonzero) value += (double) p[output + c * width + i] * h[i];
      } else for (int i = 0; i < width; i++) value += (double) p[output + c * width + i] * h[i];
      errors[c] = (float) value;
      max = Math.max(max, errors[c]);
    }
    for (int c = 0; c < 6; c++) {
      errors[c] = Math.exp(errors[c] - max);
      sum += errors[c];
    }
    for (int c = 0; c < 6; c++) errors[c] = errors[c] / sum - e.target[c];
    for (int c = 0; c < 6; c++) {
      gradient[output + width * 6 + c] += errors[c];
      if (hidden == 0) {
        for (int i : e.nonzero) gradient[output + c * width + i] += errors[c] * h[i];
      } else for (int i = 0; i < width; i++) gradient[output + c * width + i] += errors[c] * h[i];
    }
    if (hidden != 0)
      for (int c = 0; c < hidden; c++) {
        double value = 0;
        for (int j = 0; j < 6; j++) value += errors[j] * p[output + j * width + c];
        value *= 1 - (double) h[c] * h[c];
        gradient[1024 * hidden + c] += value;
        for (int i : e.nonzero) gradient[c * 1024 + i] += value * e.x[i];
      }
  }

  static void clip(double[] gradient, double maximum) {
    if (gradient == null || !Double.isFinite(maximum) || maximum <= 0)
      throw new IllegalArgumentException("Invalid clipping norm");
    double norm = 0;
    for (double g : gradient) {
      if (!Double.isFinite(g)) throw new IllegalArgumentException("Nonfinite Adam gradient");
      norm += g * g;
    }
    norm = Math.sqrt(norm);
    if (!Double.isFinite(norm)) throw new IllegalArgumentException("Adam gradient norm overflow");
    if (norm > maximum) for (int i = 0; i < gradient.length; i++) gradient[i] *= maximum / norm;
  }

  static void update(float[] p, double[] g, double[] m, double[] v, boolean[] weights, int step) {
    double a = 1 - Math.pow(.9, step), b = 1 - Math.pow(.999, step);
    for (int i = 0; i < p.length; i++) {
      m[i] = .9 * m[i] + .1 * g[i];
      v[i] = .999 * v[i] + .001 * g[i] * g[i];
      double decay = weights[i] ? .0005 * p[i] : 0;
      p[i] -= (float) (.003 * (m[i] / a / (Math.sqrt(v[i] / b) + 1e-8) + decay));
      if (!Float.isFinite(p[i])) throw new IllegalStateException("Nonfinite Adam weight");
    }
  }

  static double[] evaluate(Model model, List<Example> rows, double temperature) {
    TinyDecisionModel decision =
        new TinyDecisionModel(
            model.runtime().withSparseInput(), RichTextDecisionModel.labels(), temperature);
    Map<String, double[]> strata = new TreeMap<>();
    for (Example e : rows) {
      double[] p = decision.decide(e.x, 0, 0).probabilitiesCopy();
      int best = 0;
      for (int i = 1; i < 6; i++) if (p[i] > p[best]) best = i;
      double[] n = strata.computeIfAbsent(e.language + ":" + e.label, k -> new double[3]);
      n[0] += best == e.label ? 1 : 0;
      n[1] -= Math.log(Math.max(1e-30, p[e.label]));
      n[2]++;
    }
    double accuracy = 0, loss = 0;
    for (double[] n : strata.values()) {
      accuracy += n[0] / n[2];
      loss += n[1] / n[2];
    }
    return new double[] {accuracy / strata.size(), loss / strata.size()};
  }

  private DistilledAdamTrainer() {}
}
