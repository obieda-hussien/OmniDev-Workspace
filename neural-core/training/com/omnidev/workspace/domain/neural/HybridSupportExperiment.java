package com.omnidev.workspace.domain.neural;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Four small convex fusion fits; zero-learned-parameter field controls compete on development. */
public final class HybridSupportExperiment {
  static final class Query {
    final String id, split, language, text;
    final Set<String> primary, secondary;
    final List<HybridSupportRetriever.Row> rows;
    final List<float[]> x = new ArrayList<>();

    Query(
        String id,
        String split,
        String language,
        String text,
        Set<String> primary,
        Set<String> secondary,
        HybridSupportRetriever index) {
      this.id = id;
      this.split = split;
      this.language = language;
      this.text = text;
      this.primary = primary;
      this.secondary = secondary;
      rows = index.rows(text);
      for (HybridSupportRetriever.Row r : rows) x.add(r.featuresCopy());
    }
  }

  static final class Config {
    final HybridSupportRetriever.Mode mode;
    final float[] w;
    final long seed;
    final double rate;
    final int epoch, ran;

    Config(
        HybridSupportRetriever.Mode mode, float[] w, long seed, double rate, int epoch, int ran) {
      this.mode = mode;
      this.w = w == null ? null : w.clone();
      this.seed = seed;
      this.rate = rate;
      this.epoch = epoch;
      this.ran = ran;
    }

    int parameters() {
      return mode == HybridSupportRetriever.Mode.LEARNED ? 9 : 0;
    }
  }

  static final class Policy {
    final double minimum, margin, coverage;

    Policy(double minimum, double margin, double coverage) {
      this.minimum = minimum;
      this.margin = margin;
      this.coverage = coverage;
    }
  }

  static final class Metrics {
    int known, unknown, top1, all3, correct, wrong, refused;
    double rr, ndcg;

    String json() {
      return "{\"supported\":"
          + known
          + ",\"unsupported\":"
          + unknown
          + ",\"top1_correct\":"
          + top1
          + ",\"mrr\":"
          + (known == 0 ? 0 : rr / known)
          + ",\"all_primary_at3\":"
          + all3
          + ",\"ndcg_at3\":"
          + (known == 0 ? 0 : ndcg / known)
          + ",\"accepted_correct\":"
          + correct
          + ",\"accepted_wrong\":"
          + wrong
          + ",\"unsupported_refused\":"
          + refused
          + "}";
    }
  }

  static Set<String> ids(String value) {
    return value.equals("-")
        ? Collections.emptySet()
        : new TreeSet<>(Arrays.asList(value.split(",")));
  }

  static List<Query> old(String split, HybridSupportRetriever index) throws Exception {
    List<Query> out = new ArrayList<>();
    List<String> lines =
        Files.readAllLines(
            Paths.get("neural-core/fixtures/product-support/queries.tsv"), StandardCharsets.UTF_8);
    for (String line : lines.subList(1, lines.size())) {
      String[] r = line.split("\t", -1);
      if (r.length != 6) throw new IllegalArgumentException("Original support schema");
      if (r[2].equals(split))
        out.add(new Query(r[0], split, r[3], r[5], ids(r[4]), Collections.emptySet(), index));
    }
    return out;
  }

  static List<Query> fresh(HybridSupportRetriever index) throws Exception {
    List<Query> out = new ArrayList<>();
    List<String> lines =
        Files.readAllLines(
            Paths.get("neural-core/fixtures/hybrid-support-v3/fresh.tsv"), StandardCharsets.UTF_8);
    for (String line : lines.subList(1, lines.size())) {
      String[] r = line.split("\t", -1);
      if (r.length != 6) throw new IllegalArgumentException("Compound support schema");
      out.add(new Query(r[0], "fresh", r[2], r[5], ids(r[3]), ids(r[4]), index));
    }
    return out;
  }

  static double dot(float[] x, float[] w) {
    double s = 0;
    for (int i = 0; i < 8; i++) s += (double) x[i] * w[i];
    return (float) s;
  }

  static double score(Query q, int i, Config c) {
    HybridSupportRetriever.Row row = q.rows.get(i);
    switch (c.mode) {
      case LEGACY:
        return row.legacy;
      case TITLE:
        return q.x.get(i)[3];
      case RRF:
        return row.rrf;
      case UNIFORM:
        double sum = 0;
        for (float f : q.x.get(i)) sum += f / 8.0;
        return sum;
      default:
        return Math.max(0, Math.min(1, dot(q.x.get(i), c.w)));
    }
  }

  static List<Integer> rank(Query q, Config c) {
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < q.rows.size(); i++)
      if (c.mode != HybridSupportRetriever.Mode.LEGACY || q.rows.get(i).legacy > 0) order.add(i);
    order.sort(
        Comparator.comparingDouble((Integer i) -> score(q, i, c))
            .reversed()
            .thenComparing(i -> q.rows.get(i).topic.id));
    return order;
  }

  static boolean accepted(Query q, List<Integer> order, Config c, Policy p) {
    return !order.isEmpty()
        && score(q, order.get(0), c) >= p.minimum
        && q.rows.get(order.get(0)).coverage >= p.coverage
        && (order.size() == 1 || score(q, order.get(0), c) - score(q, order.get(1), c) >= p.margin);
  }

  static void observe(Metrics m, Query q, List<String> rank, boolean accepted) {
    if (q.primary.isEmpty()) {
      m.unknown++;
      if (!accepted) m.refused++;
    } else {
      m.known++;
      int first = -1;
      Set<String> top = new HashSet<>();
      double dcg = 0;
      for (int i = 0; i < rank.size(); i++) {
        String id = rank.get(i);
        if (first < 0 && q.primary.contains(id)) first = i;
        if (i < 3) {
          top.add(id);
          int rel = q.primary.contains(id) ? 2 : q.secondary.contains(id) ? 1 : 0;
          dcg += (Math.pow(2, rel) - 1) / (Math.log(i + 2) / Math.log(2));
        }
      }
      if (first == 0) m.top1++;
      if (first >= 0) m.rr += 1.0 / (first + 1);
      if (top.containsAll(q.primary)) m.all3++;
      List<Integer> ideal = new ArrayList<>();
      for (String id : q.primary) ideal.add(3);
      for (String id : q.secondary) ideal.add(1);
      double best = 0;
      for (int i = 0; i < Math.min(3, ideal.size()); i++)
        best += ideal.get(i) / (Math.log(i + 2) / Math.log(2));
      m.ndcg += best == 0 ? 0 : dcg / best;
    }
    if (accepted) {
      if (!rank.isEmpty() && q.primary.contains(rank.get(0))) m.correct++;
      else m.wrong++;
    }
  }

  static Metrics metrics(List<Query> rows, Config c, Policy p) {
    Metrics m = new Metrics();
    for (Query q : rows) {
      List<Integer> order = rank(q, c);
      List<String> rank = new ArrayList<>();
      for (int i : order) rank.add(q.rows.get(i).topic.id);
      observe(m, q, rank, accepted(q, order, c, p));
    }
    return m;
  }

  static boolean floor(List<Query> dev, Config c) {
    Config original = new Config(HybridSupportRetriever.Mode.LEGACY, null, 0, 0, 0, 0);
    for (String lang : new String[] {"en", "ar", "mixed"}) {
      List<Query> subset = new ArrayList<>();
      for (Query q : dev) if (q.language.equals(lang)) subset.add(q);
      if (subset.isEmpty()) throw new IllegalArgumentException("Missing selection language");
      if (metrics(subset, c, new Policy(0, 0, 0)).top1
          < metrics(subset, original, new Policy(0, 0, 0)).top1) return false;
    }
    return true;
  }

  static boolean better(Config a, Config b, List<Query> dev) {
    if (b == null) return true;
    Metrics x = metrics(dev, a, new Policy(0, 0, 0)), y = metrics(dev, b, new Policy(0, 0, 0));
    return x.top1 > y.top1
        || x.top1 == y.top1
            && (x.rr > y.rr + 1e-12
                || Math.abs(x.rr - y.rr) < 1e-12 && a.parameters() < b.parameters());
  }

  static double sigmoid(double z) {
    return z >= 0 ? 1 / (1 + Math.exp(-z)) : Math.exp(z) / (1 + Math.exp(z));
  }

  static double queryLoss(Query q, double[] w, double[] gradient) {
    List<Integer> positive = new ArrayList<>(), negative = new ArrayList<>();
    for (int i = 0; i < q.rows.size(); i++) {
      if (q.primary.contains(q.rows.get(i).topic.id)) positive.add(i);
      else negative.add(i);
    }
    negative.sort(
        Comparator.comparingDouble((Integer i) -> rawDot(q.x.get(i), w))
            .reversed()
            .thenComparingInt(i -> i));
    if (negative.size() > 8) negative = negative.subList(0, 8);
    int pairs = positive.size() * negative.size();
    if (pairs == 0) return 0;
    double loss = 0;
    for (int p : positive)
      for (int n : negative) {
        double z = rawDot(q.x.get(n), w) - rawDot(q.x.get(p), w);
        loss += (Math.max(z, 0) + Math.log1p(Math.exp(-Math.abs(z)))) / pairs;
        double dz = sigmoid(z) / pairs;
        for (int j = 0; j < 8; j++) gradient[j] += dz * (q.x.get(n)[j] - q.x.get(p)[j]);
      }
    return loss;
  }

  static double rawDot(float[] x, double[] w) {
    double s = 0;
    for (int i = 0; i < 8; i++) s += x[i] * w[i];
    return s;
  }

  static double[] project(double[] v) {
    if (v == null || v.length != 8)
      throw new IllegalArgumentException("Invalid simplex dimensions");
    double[] u = v.clone();
    for (double d : u)
      if (!Double.isFinite(d)) throw new IllegalArgumentException("Non-finite simplex input");
    Arrays.sort(u);
    double sum = 0, theta = 0;
    for (int j = 0; j < u.length; j++) {
      double d = u[u.length - 1 - j];
      sum += d;
      double t = (sum - 1) / (j + 1);
      if (d > t) theta = t;
    }
    double[] out = new double[v.length];
    for (int i = 0; i < v.length; i++) out[i] = Math.max(0, v[i] - theta);
    return out;
  }

  static float[] weights(double[] w) {
    float[] out = new float[8];
    for (int i = 0; i < 8; i++) out[i] = (float) w[i];
    return out;
  }

  static Config fit(List<Query> train, List<Query> dev, double rate, long seed) {
    if (train.isEmpty() || dev.isEmpty()) throw new IllegalArgumentException("Empty fusion fit");
    for (Query q : train)
      if (!q.split.equals("train")) throw new IllegalArgumentException("Foreign fitting row");
    for (Query q : dev)
      if (!q.split.equals("development"))
        throw new IllegalArgumentException("Foreign selection row");
    double[] w = new double[8];
    Arrays.fill(w, .125);
    Config best = new Config(HybridSupportRetriever.Mode.LEARNED, weights(w), seed, rate, 0, 0);
    int epochBest = 0, ran = 0;
    Random random = new Random(seed);
    List<Query> order = new ArrayList<>(train);
    for (int epoch = 1; epoch <= 80; epoch++) {
      Collections.shuffle(order, random);
      for (int start = 0; start < order.size(); start += 16) {
        double[] g = new double[8];
        int count = Math.min(16, order.size() - start);
        for (int i = start; i < start + count; i++) queryLoss(order.get(i), w, g);
        double norm = 0;
        for (int i = 0; i < 8; i++) {
          g[i] = g[i] / count + .02 * (w[i] - .125);
          norm += g[i] * g[i];
        }
        double scale = Math.sqrt(norm) > 5 ? 5 / Math.sqrt(norm) : 1;
        double[] proposal = new double[8];
        for (int i = 0; i < 8; i++) proposal[i] = w[i] - rate * g[i] * scale;
        w = project(proposal);
      }
      Config c =
          new Config(HybridSupportRetriever.Mode.LEARNED, weights(w), seed, rate, epoch, epoch);
      ran = epoch;
      if (floor(dev, c) && better(c, best, dev)) {
        best = c;
        epochBest = epoch;
      }
      if (epoch - epochBest >= 10) break;
    }
    return new Config(best.mode, best.w, seed, rate, best.epoch, ran);
  }

  static Policy calibrate(List<Query> cal, Config c) {
    Policy best = new Policy(1, 1, 1);
    int utility = Integer.MIN_VALUE, wrong = Integer.MAX_VALUE;
    for (int t = 0; t <= 40; t++)
      for (double margin : new double[] {0, .025, .05, .1, .2})
        for (double coverage : new double[] {0, .25, .5, .75}) {
          Policy p = new Policy(t / 40.0, margin, coverage);
          Metrics m = metrics(cal, c, p);
          int u = m.correct - 3 * m.wrong;
          if (u > utility || u == utility && m.wrong < wrong) {
            best = p;
            utility = u;
            wrong = m.wrong;
          }
        }
    return best;
  }

  static HybridSupportRetriever model(
      List<ProductSupportModel.Article> docs, Config c, Policy p, String fp) {
    return new HybridSupportRetriever(docs, c.mode, c.w, p.minimum, p.margin, p.coverage, fp);
  }

  static String configJson(Config c, List<Query> dev) {
    return "{\"mode\":\""
        + c.mode
        + "\",\"seed\":"
        + c.seed
        + ",\"learning_rate\":"
        + c.rate
        + ",\"epoch\":"
        + c.epoch
        + ",\"epochs_run\":"
        + c.ran
        + ",\"parameters\":"
        + c.parameters()
        + ",\"weights\":"
        + (c.w == null ? "null" : Arrays.toString(c.w))
        + ",\"development\":"
        + metrics(dev, c, new Policy(0, 0, 0)).json()
        + "}";
  }

  static ProductSupportModel previous(
      List<ProductSupportModel.Article> corpus, String name, String method) {
    try {
      return (ProductSupportModel)
          Class.forName("com.omnidev.workspace.domain.neural." + name)
              .getMethod(method, List.class)
              .invoke(null, corpus);
    } catch (ReflectiveOperationException missing) {
      throw new IllegalStateException("Frozen checkpoint unavailable", missing);
    }
  }

  static Metrics previousMetrics(List<Query> rows, ProductSupportModel m) {
    Metrics stats = new Metrics();
    for (Query q : rows) {
      List<String> rank = new ArrayList<>();
      for (ProductSupportModel.Match hit : m.candidates(q.text)) rank.add(hit.article.topic);
      observe(stats, q, rank, m.search(q.text, 3).accepted);
    }
    return stats;
  }

  public static void main(String[] args) throws Exception {
    Path out = Paths.get(args[0]);
    Files.createDirectories(out);
    List<ProductSupportModel.Article> docs =
        ProductSupportExperiment.loadArticles(Paths.get("build/product-support/documents.tsv"));
    HybridSupportRetriever index =
        new HybridSupportRetriever(docs, HybridSupportRetriever.Mode.LEGACY, null, 0, 0, 0, null);
    List<Query> train = old("train", index),
        dev = old("development", index),
        cal = old("calibration", index);
    Config selected = null;
    List<Config> all = new ArrayList<>();
    for (HybridSupportRetriever.Mode mode :
        new HybridSupportRetriever.Mode[] {
          HybridSupportRetriever.Mode.LEGACY,
          HybridSupportRetriever.Mode.TITLE,
          HybridSupportRetriever.Mode.RRF,
          HybridSupportRetriever.Mode.UNIFORM
        }) all.add(new Config(mode, null, 0, 0, 0, 0));
    for (double rate : new double[] {.05, .15})
      for (long seed : new long[] {42, 97}) {
        Config c = fit(train, dev, rate, seed);
        all.add(c);
        System.err.println(configJson(c, dev));
      }
    StringBuilder candidates = new StringBuilder("[");
    for (Config c : all) {
      if (candidates.length() > 1) candidates.append(',');
      candidates.append(configJson(c, dev));
      if (floor(dev, c) && better(c, selected, dev)) selected = c;
    }
    candidates.append(']');
    if (selected == null) throw new IllegalStateException("No eligible fusion control");
    Policy policy = calibrate(cal, selected);
    String source = source(selected, policy, index.corpusFingerprint());
    Files.write(out.resolve("TrainedHybridSupport.java"), source.getBytes(StandardCharsets.UTF_8));
    String selection =
        "{\"selected\":"
            + configJson(selected, dev)
            + ",\"minimum\":"
            + policy.minimum
            + ",\"margin\":"
            + policy.margin
            + ",\"coverage\":"
            + policy.coverage
            + ",\"candidates\":"
            + candidates
            + "}";
    Files.write(out.resolve("selection.json"), selection.getBytes(StandardCharsets.UTF_8));
    List<Query> fresh = fresh(index), observed = old("test", index);
    List<String> v2 =
        Files.readAllLines(
            Paths.get("neural-core/fixtures/product-support-v2/fresh.tsv"), StandardCharsets.UTF_8);
    for (String line : v2.subList(1, v2.size())) {
      String[] r = line.split("\t", -1);
      observed.add(
          new Query(r[0], "observed", r[3], r[5], ids(r[4]), Collections.emptySet(), index));
    }
    HybridSupportRetriever candidate = model(docs, selected, policy, index.corpusFingerprint());
    ProductSupportModel lexical = previous(docs, "TrainedProductSupport", "baseline"),
        prior = previous(docs, "TrainedProductSupport", "create"),
        v2model = previous(docs, "TrainedRankedSupport", "createRaw");
    String report =
        "{\"selection\":"
            + selection
            + ",\"new_authored\":{\"lexical\":"
            + previousMetrics(fresh, lexical).json()
            + ",\"v1_guarded\":"
            + previousMetrics(fresh, prior).json()
            + ",\"v2_raw\":"
            + previousMetrics(fresh, v2model).json()
            + ",\"hybrid_candidate\":"
            + metrics(fresh, selected, policy).json()
            + "},\"new_languages\":"
            + languages(fresh, selected, policy, lexical)
            + ",\"observed_regression\":{\"lexical\":"
            + previousMetrics(observed, lexical).json()
            + ",\"retained_default\":"
            + previousMetrics(observed, prior).json()
            + ",\"hybrid_candidate\":"
            + metrics(observed, selected, policy).json()
            + "},\"production_eligible\":false,\"independent_human_labels\":false,\"semantic_ood\":false}";
    Files.write(out.resolve("report.json"), report.getBytes(StandardCharsets.UTF_8));
    List<Query> probes = new ArrayList<>();
    for (String split : new String[] {"train", "development", "calibration", "test"})
      probes.addAll(old(split, index));
    for (Query q : observed) if (q.split.equals("observed")) probes.add(q);
    probes.addAll(fresh);
    List<String> expected = new ArrayList<>();
    for (Query q : probes) expected.add(probe(q.id, candidate.search(q.text, 10)));
    Files.write(out.resolve("expected.tsv"), expected, StandardCharsets.UTF_8);
    System.out.println(report);
  }

  static String probe(String id, HybridSupportRetriever.Result r) {
    StringBuilder s = new StringBuilder(id + "\t" + r.accepted);
    for (HybridSupportRetriever.Hit hit : r.matches)
      s.append('\t')
          .append(hit.topic.id)
          .append(':')
          .append(String.format(Locale.ROOT, "%.9f", hit.score))
          .append(':')
          .append(String.format(Locale.ROOT, "%.9f", hit.coverage));
    return s.toString();
  }

  static String languages(List<Query> rows, Config c, Policy p, ProductSupportModel base) {
    StringBuilder s = new StringBuilder("{");
    for (String lang : new String[] {"en", "ar", "mixed"}) {
      List<Query> subset = new ArrayList<>();
      for (Query q : rows) if (q.language.equals(lang)) subset.add(q);
      if (s.length() > 1) s.append(',');
      s.append('"')
          .append(lang)
          .append("\":{\"lexical\":")
          .append(previousMetrics(subset, base).json())
          .append(",\"hybrid\":")
          .append(metrics(subset, c, p).json())
          .append('}');
    }
    return s.append('}').toString();
  }

  static String source(Config c, Policy p, String fp) {
    String weights = c.w == null ? "null" : ProductSupportExperiment.array(c.w);
    return "package com.omnidev.workspace.domain.neural;\n"
        + "import java.util.List;\n"
        + "public final class TrainedHybridSupport{\n"
        + "public static HybridSupportRetriever"
        + " createCandidate(List<ProductSupportModel.Article> a){return new"
        + " HybridSupportRetriever(a,HybridSupportRetriever.Mode."
        + c.mode
        + ","
        + weights
        + ","
        + p.minimum
        + ","
        + p.margin
        + ","
        + p.coverage
        + ",\""
        + fp
        + "\",\""
        + HybridSupportRetriever.VERSION
        + "\");}\n"
        + "public static ProductSupportModel"
        + " retainedDefault(List<ProductSupportModel.Article>a){return"
        + " TrainedProductSupport.create(a);}\n"
        + "private TrainedHybridSupport(){}\n"
        + "}\n";
  }
}
