package com.omnidev.workspace.domain.neural;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Four bounded offline AdamW fits; topic-held-out queries never enter fitting or model selection.
 */
public final class ProductSupportExperiment {
  static final class Frame {
    final String id, split, language, topic, query;
    final List<ProductSupportModel.Match> pool;
    final List<float[]> x = new ArrayList<>();

    Frame(String[] row, ProductSupportModel baseline) {
      id = row[0];
      split = row[2];
      language = row[3];
      topic = row[4];
      query = row[5];
      pool = baseline.evidencePool(query);
      for (ProductSupportModel.Match m : pool) x.add(baseline.features(query, m.evidence));
    }
  }

  static final class Pair {
    final float[] x;
    final double y;

    Pair(float[] x, double y) {
      this.x = x;
      this.y = y;
    }
  }

  static final class Network {
    final int width;
    final double[] w;

    Network(int width, long seed) {
      this.width = width;
      w = new double[width == 0 ? 13 : width * 12 + width + width + 1];
      Random r = new Random(seed);
      for (int i = 0; i < w.length; i++) w[i] = r.nextGaussian() * (width == 0 ? .02 : .2);
    }

    Network(int width, double[] w) {
      this.width = width;
      this.w = w.clone();
    }

    double[] forward(float[] x) {
      double[] h = new double[width + 1];
      double z = w[w.length - 1];
      if (width == 0) {
        for (int j = 0; j < 12; j++) z += w[j] * x[j];
      } else {
        for (int i = 0; i < width; i++) {
          double a = w[width * 12 + i];
          for (int j = 0; j < 12; j++) a += w[i * 12 + j] * x[j];
          h[i] = Math.tanh(a);
          z += w[width * 13 + i] * h[i];
        }
      }
      h[width] = z;
      return h;
    }

    void gradient(Pair p, double weight, double[] g) {
      double[] h = forward(p.x);
      double dz = (ProductSupportModel.sigmoid(h[width]) - p.y) * weight;
      g[w.length - 1] += dz;
      if (width == 0) {
        for (int j = 0; j < 12; j++) g[j] += dz * p.x[j];
      } else
        for (int i = 0; i < width; i++) {
          g[width * 13 + i] += dz * h[i];
          double dh = dz * w[width * 13 + i] * (1 - h[i] * h[i]);
          g[width * 12 + i] += dh;
          for (int j = 0; j < 12; j++) g[i * 12 + j] += dh * p.x[j];
        }
    }

    TinyNeuralRuntime runtime() {
      if (width == 0)
        return new TinyNeuralRuntime(
            new TinyNeuralRuntime.Dense(
                12, 1, floats(w, 0, 12), floats(w, 12, 1), TinyNeuralRuntime.Activation.LINEAR));
      return new TinyNeuralRuntime(
          new TinyNeuralRuntime.Dense(
              12,
              width,
              floats(w, 0, width * 12),
              floats(w, width * 12, width),
              TinyNeuralRuntime.Activation.TANH),
          new TinyNeuralRuntime.Dense(
              width,
              1,
              floats(w, width * 13, width),
              floats(w, w.length - 1, 1),
              TinyNeuralRuntime.Activation.LINEAR));
    }
  }

  static final class Fit {
    final Network n;
    final int epoch, ran;
    final double loss, mrr;

    Fit(Network n, int epoch, int ran, double loss, double mrr) {
      this.n = n;
      this.epoch = epoch;
      this.ran = ran;
      this.loss = loss;
      this.mrr = mrr;
    }
  }

  static final class Policy {
    final double minimum, margin;

    Policy(double minimum, double margin) {
      this.minimum = minimum;
      this.margin = margin;
    }
  }

  static final class Stats {
    int supported, ood, top1, top3, found, acceptedCorrect, acceptedWrong, oodRefused;
    double reciprocal;

    String json() {
      return "{\"supported\":"
          + supported
          + ",\"ood\":"
          + ood
          + ",\"recall_at_1\":"
          + rate(top1, supported)
          + ",\"recall_at_3\":"
          + rate(top3, supported)
          + ",\"mrr\":"
          + rate(reciprocal, supported)
          + ",\"candidate_recall\":"
          + rate(found, supported)
          + ",\"accepted_correct\":"
          + acceptedCorrect
          + ",\"accepted_wrong\":"
          + acceptedWrong
          + ",\"ood_refused\":"
          + oodRefused
          + "}";
    }
  }

  public static List<ProductSupportModel.Article> loadArticles(Path path) throws Exception {
    List<ProductSupportModel.Article> out = new ArrayList<>();
    for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
      String[] r = line.split("\t", -1);
      if (r.length != 7) throw new IllegalArgumentException("Article schema");
      out.add(
          new ProductSupportModel.Article(
              r[0], r[1], r[2], r[3], r[4].replace("\\n", "\n"), r[5], r[6]));
    }
    return out;
  }

  static List<Frame> frames(Path path, String split, ProductSupportModel baseline)
      throws Exception {
    List<Frame> out = new ArrayList<>();
    List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
    for (String line : lines.subList(1, lines.size())) {
      String[] r = line.split("\t", -1);
      if (r.length != 6) throw new IllegalArgumentException("Query schema");
      if (r[2].equals(split)) out.add(new Frame(r, baseline));
    }
    return out;
  }

  static Fit fit(List<Frame> train, List<Frame> dev, int width, long seed) {
    List<Pair> data = new ArrayList<>();
    int positives = 0;
    for (Frame f : train)
      for (int i = 0; i < f.pool.size(); i++) {
        double y = f.pool.get(i).article.topic.equals(f.topic) ? 1 : 0;
        data.add(new Pair(f.x.get(i), y));
        if (y == 1) positives++;
      }
    if (positives == 0) throw new IllegalArgumentException("No training positives");
    double posWeight = Math.min(8, (data.size() - positives) / (double) positives);
    Network n = new Network(width, seed), best = null;
    double[] m = new double[n.w.length], v = new double[n.w.length];
    int step = 0, bestEpoch = 0, ran = 0;
    double bestMrr = -1, bestLoss = Double.POSITIVE_INFINITY;
    Random random = new Random(seed);
    for (int epoch = 1; epoch <= 100; epoch++) {
      Collections.shuffle(data, random);
      for (int start = 0; start < data.size(); start += 64) {
        double[] g = new double[n.w.length];
        int count = Math.min(64, data.size() - start);
        for (int i = start; i < start + count; i++) {
          Pair p = data.get(i);
          n.gradient(p, p.y == 1 ? posWeight : 1, g);
        }
        double norm = 0;
        for (int i = 0; i < g.length; i++) {
          g[i] /= count;
          norm += g[i] * g[i];
        }
        double scale = Math.sqrt(norm) > 5 ? 5 / Math.sqrt(norm) : 1;
        step++;
        for (int i = 0; i < g.length; i++) {
          double d = g[i] * scale;
          m[i] = .9 * m[i] + .1 * d;
          v[i] = .999 * v[i] + .001 * d * d;
          n.w[i] -=
              .01
                  * ((m[i] / (1 - Math.pow(.9, step)))
                          / (Math.sqrt(v[i] / (1 - Math.pow(.999, step))) + 1e-8)
                      + 1e-4 * n.w[i]);
        }
      }
      TinyNeuralRuntime runtime = n.runtime();
      Stats s = stats(dev, runtime, new Policy(0, 0));
      double mrr = rate(s.reciprocal, s.supported), loss = loss(dev, runtime);
      ran = epoch;
      if (mrr > bestMrr + 1e-12 || Math.abs(mrr - bestMrr) < 1e-12 && loss < bestLoss - 1e-8) {
        best = new Network(width, n.w);
        bestMrr = mrr;
        bestLoss = loss;
        bestEpoch = epoch;
      }
      if (epoch - bestEpoch >= 15) break;
    }
    return new Fit(best, bestEpoch, ran, bestLoss, bestMrr);
  }

  static double loss(List<Frame> rows, TinyNeuralRuntime network) {
    double total = 0;
    int count = 0;
    for (Frame f : rows)
      for (int i = 0; i < f.pool.size(); i++) {
        double z = network.infer(f.x.get(i))[0],
            y = f.pool.get(i).article.topic.equals(f.topic) ? 1 : 0;
        total += Math.max(z, 0) - z * y + Math.log1p(Math.exp(-Math.abs(z)));
        count++;
      }
    return total / Math.max(1, count);
  }

  static List<ProductSupportModel.Match> rank(Frame f, TinyNeuralRuntime network) {
    Map<String, ProductSupportModel.Match> topics = new HashMap<>();
    for (int i = 0; i < f.pool.size(); i++) {
      ProductSupportModel.Match base = f.pool.get(i);
      double score =
          network == null ? base.score : ProductSupportModel.sigmoid(network.infer(f.x.get(i))[0]);
      ProductSupportModel.Match
          m = new ProductSupportModel.Match(base.article, base.evidence, score),
          old = topics.get(base.article.topic);
      if (old == null || compare(m, old) < 0) topics.put(base.article.topic, m);
    }
    List<ProductSupportModel.Match> result = new ArrayList<>(topics.values());
    result.sort(ProductSupportExperiment::compare);
    return result;
  }

  static int compare(ProductSupportModel.Match a, ProductSupportModel.Match b) {
    int c = Double.compare(b.score, a.score);
    if (c == 0) c = a.evidence.documentId.compareTo(b.evidence.documentId);
    return c != 0 ? c : Integer.compare(a.evidence.startOffset, b.evidence.startOffset);
  }

  static boolean accepted(List<ProductSupportModel.Match> r, Policy p) {
    return !r.isEmpty()
        && r.get(0).score >= p.minimum
        && (r.size() == 1 || r.get(0).score - r.get(1).score >= p.margin);
  }

  static Stats stats(List<Frame> frames, TinyNeuralRuntime network, Policy policy) {
    Stats s = new Stats();
    for (Frame f : frames) {
      List<ProductSupportModel.Match> r = rank(f, network);
      boolean a = accepted(r, policy), known = !f.topic.equals("-");
      if (known) {
        s.supported++;
        int pos = -1;
        for (int i = 0; i < r.size(); i++)
          if (r.get(i).article.topic.equals(f.topic)) {
            pos = i;
            break;
          }
        if (pos >= 0) {
          s.found++;
          s.reciprocal += 1.0 / (pos + 1);
          if (pos == 0) s.top1++;
          if (pos < 3) s.top3++;
        }
      } else {
        s.ood++;
        if (!a) s.oodRefused++;
      }
      if (a) {
        if (known && !r.isEmpty() && r.get(0).article.topic.equals(f.topic)) s.acceptedCorrect++;
        else s.acceptedWrong++;
      }
    }
    return s;
  }

  static Policy calibrate(List<Frame> cal, TinyNeuralRuntime n) {
    Policy best = new Policy(1, 1);
    int utility = Integer.MIN_VALUE, wrong = Integer.MAX_VALUE;
    for (int t = 0; t <= 40; t++)
      for (double gap : new double[] {0, .025, .05, .1, .2}) {
        Policy p = new Policy(t / 40.0, gap);
        Stats s = stats(cal, n, p);
        int u = s.acceptedCorrect - 3 * s.acceptedWrong;
        if (u > utility || u == utility && s.acceptedWrong < wrong) {
          best = p;
          utility = u;
          wrong = s.acceptedWrong;
        }
      }
    return best;
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Expected support output directory");
    Path out = Paths.get(args[0]);
    Files.createDirectories(out);
    List<ProductSupportModel.Article> articles = loadArticles(out.resolve("documents.tsv"));
    ProductSupportModel base = new ProductSupportModel(articles, null, 0, 0, null);
    Path queries = Paths.get("neural-core/fixtures/product-support/queries.tsv");
    List<Frame> train = frames(queries, "train", base),
        dev = frames(queries, "development", base),
        cal = frames(queries, "calibration", base);
    Fit selected = null;
    long selectedSeed = 0;
    StringBuilder candidates = new StringBuilder("[");
    for (int width : new int[] {0, 8})
      for (long seed : new long[] {42, 97}) {
        Fit f = fit(train, dev, width, seed);
        if (candidates.length() > 1) candidates.append(',');
        candidates
            .append("{\"width\":")
            .append(width)
            .append(",\"seed\":")
            .append(seed)
            .append(",\"selected_epoch\":")
            .append(f.epoch)
            .append(",\"epochs_run\":")
            .append(f.ran)
            .append(",\"development_mrr\":")
            .append(f.mrr)
            .append(",\"development_nll\":")
            .append(f.loss)
            .append('}');
        if (selected == null
            || f.mrr > selected.mrr + 1e-12
            || Math.abs(f.mrr - selected.mrr) < 1e-12 && f.loss < selected.loss) {
          selected = f;
          selectedSeed = seed;
        }
        System.err.println("Support width=" + width + " seed=" + seed + " devMRR=" + f.mrr);
      }
    candidates.append(']');
    TinyNeuralRuntime network = selected.n.runtime();
    Policy policy = calibrate(cal, network), baseline = calibrate(cal, null);
    // Freeze portable weights and policy BEFORE opening held-out query rows for scoring.
    String source = javaSource(selected.n, policy, baseline, base.fingerprint());
    Files.write(out.resolve("TrainedProductSupport.java"), source.getBytes(StandardCharsets.UTF_8));
    List<Frame> test = frames(queries, "test", base);
    Stats before = stats(test, null, baseline), after = stats(test, network, policy);
    StringBuilder languages = new StringBuilder("{");
    for (String lang : new String[] {"en", "ar", "mixed"}) {
      List<Frame> subset = new ArrayList<>();
      for (Frame f : test) if (f.language.equals(lang)) subset.add(f);
      if (languages.length() > 1) languages.append(',');
      languages
          .append('"')
          .append(lang)
          .append("\":{\"baseline\":")
          .append(stats(subset, null, baseline).json())
          .append(",\"learned\":")
          .append(stats(subset, network, policy).json())
          .append('}');
    }
    languages.append('}');
    String report =
        "{\"feature_version\":\""
            + ProductSupportModel.FEATURES
            + "\",\"corpus_fingerprint\":\""
            + base.fingerprint()
            + "\",\"parameters\":"
            + network.parameterCount()
            + ",\"fp32_weight_bytes\":"
            + (network.parameterCount() * 4)
            + ",\"selected_seed\":"
            + selectedSeed
            + ",\"selected_width\":"
            + selected.n.width
            + ",\"minimum\":"
            + policy.minimum
            + ",\"margin\":"
            + policy.margin
            + ",\"baseline_minimum\":"
            + baseline.minimum
            + ",\"baseline_margin\":"
            + baseline.margin
            + ",\"candidates\":"
            + candidates
            + ",\"held_out_baseline\":"
            + before.json()
            + ",\"held_out_learned\":"
            + after.json()
            + ",\"held_out_languages\":"
            + languages
            + ",\"production_eligible\":false,\"physical_device_tested\":false,\"query_authoring_independently_reviewed\":false}";
    Files.write(out.resolve("report.json"), report.getBytes(StandardCharsets.UTF_8));
    List<String> expected = new ArrayList<>();
    for (String split : new String[] {"train", "development", "calibration", "test"})
      for (Frame f : frames(queries, split, base)) {
        List<ProductSupportModel.Match> r = rank(f, network);
        StringBuilder line = new StringBuilder(f.id + "\t" + accepted(r, policy));
        for (int i = 0; i < Math.min(3, r.size()); i++) {
          ProductSupportModel.Match m = r.get(i);
          line.append('\t')
              .append(m.article.id)
              .append(':')
              .append(m.evidence.startOffset)
              .append(':')
              .append(m.evidence.endOffset)
              .append(':')
              .append(m.score);
        }
        expected.add(line.toString());
      }
    Files.write(out.resolve("expected.tsv"), expected, StandardCharsets.UTF_8);
    System.out.println(report);
  }

  static String javaSource(Network n, Policy p, Policy baseline, String fingerprint) {
    String raw =
        "new ProductSupportModel(corpus,network(),"
            + p.minimum
            + ","
            + p.margin
            + ",\""
            + fingerprint
            + "\"";
    StringBuilder s =
        new StringBuilder(
            "package com.omnidev.workspace.domain.neural;\n"
                + "import java.util.List;\n"
                + "public final class TrainedProductSupport {\n");
    s.append(
            " public static ProductSupportModel createRaw(List<ProductSupportModel.Article>"
                + " corpus){return ")
        .append(raw)
        .append(");}\n");
    s.append(
            " public static ProductSupportModel create(List<ProductSupportModel.Article>"
                + " corpus){return ")
        .append(raw)
        .append(",new ProductSupportModel.Guard(")
        .append(baseline.minimum)
        .append(',')
        .append(baseline.margin)
        .append("));}\n");
    s.append(
            " public static ProductSupportModel baseline(List<ProductSupportModel.Article>"
                + " corpus){return new ProductSupportModel(corpus,null,")
        .append(baseline.minimum)
        .append(',')
        .append(baseline.margin)
        .append(",\"")
        .append(fingerprint)
        .append("\");}\n");
    s.append(" public static TinyNeuralRuntime network(){return new TinyNeuralRuntime(");
    List<TinyNeuralRuntime.Dense> layers = new ArrayList<>();
    if (n.width == 0)
      layers.add(
          new TinyNeuralRuntime.Dense(
              12, 1, floats(n.w, 0, 12), floats(n.w, 12, 1), TinyNeuralRuntime.Activation.LINEAR));
    else {
      layers.add(
          new TinyNeuralRuntime.Dense(
              12,
              n.width,
              floats(n.w, 0, n.width * 12),
              floats(n.w, n.width * 12, n.width),
              TinyNeuralRuntime.Activation.TANH));
      layers.add(
          new TinyNeuralRuntime.Dense(
              n.width,
              1,
              floats(n.w, n.width * 13, n.width),
              floats(n.w, n.w.length - 1, 1),
              TinyNeuralRuntime.Activation.LINEAR));
    }
    for (int i = 0; i < layers.size(); i++) {
      TinyNeuralRuntime.Dense l = layers.get(i);
      if (i > 0) s.append(',');
      s.append("new TinyNeuralRuntime.Dense(")
          .append(l.inputSize())
          .append(',')
          .append(l.outputSize())
          .append(',')
          .append(array(l.weightsCopy()))
          .append(',')
          .append(array(l.biasCopy()))
          .append(",TinyNeuralRuntime.Activation.")
          .append(n.width > 0 && i == 0 ? "TANH" : "LINEAR")
          .append(')');
    }
    return s.append(");}\n private TrainedProductSupport(){}\n}\n").toString();
  }

  static String array(float[] v) {
    StringBuilder s = new StringBuilder("new float[]{");
    for (int i = 0; i < v.length; i++) {
      if (i > 0) s.append(',');
      s.append(Float.toString(v[i])).append('f');
    }
    return s.append('}').toString();
  }

  static float[] floats(double[] v, int offset, int count) {
    float[] r = new float[count];
    for (int i = 0; i < count; i++) {
      r[i] = (float) v[offset + i];
      if (!Float.isFinite(r[i])) throw new IllegalArgumentException("Non-finite fit");
    }
    return r;
  }

  static double rate(double value, int count) {
    return count == 0 ? 0 : value / count;
  }
}
