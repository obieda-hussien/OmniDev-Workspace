package com.omnidev.workspace.domain.neural;

import static com.omnidev.workspace.domain.neural.HybridSupportExperiment.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Query-memory fitting excludes the complete TRAIN scenario from nearest-exemplar matching. */
public final class QueryMemoryExperiment {
  static final class Q {
    final Query label;
    final List<SupportQueryMemory.Row> rows;
    final List<float[]> x = new ArrayList<>();

    Q(Query q, SupportQueryMemory m, String excluded) {
      label = q;
      rows = m.rows(q.text, excluded);
      for (SupportQueryMemory.Row r : rows) x.add(r.featuresCopy());
    }
  }

  static final class C {
    final float[] w;
    final String name;
    final long seed;
    final double rate;
    final int epoch, ran;

    C(String n, float[] w, long s, double r, int e, int ran) {
      name = n;
      this.w = w.clone();
      seed = s;
      rate = r;
      epoch = e;
      this.ran = ran;
    }
  }

  static List<SupportQueryMemory.Exemplar> examples(Path path) throws Exception {
    List<SupportQueryMemory.Exemplar> out = new ArrayList<>();
    List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
    if (!lines.get(0).equals("id\tgroup\ttopic\ttext"))
      throw new IllegalArgumentException("Exemplar schema");
    for (String line : lines.subList(1, lines.size())) {
      String[] r = line.split("\t", -1);
      if (r.length != 4) throw new IllegalArgumentException("Exemplar columns");
      out.add(new SupportQueryMemory.Exemplar(r[0], r[1], r[2], r[3]));
    }
    if (out.size() != 351) throw new IllegalArgumentException("Wrong exemplar count");
    return out;
  }

  static Map<String, String> trainingGroups() throws Exception {
    Map<String, String> groups = new HashMap<>();
    for (String name :
        new String[] {"product-support/queries.tsv", "support-data-v4/queries.tsv"}) {
      List<String> lines =
          Files.readAllLines(Paths.get("neural-core/fixtures", name), StandardCharsets.UTF_8);
      for (String line : lines.subList(1, lines.size())) {
        String[] r = line.split("\t", -1);
        if (r[2].equals("train"))
          groups.put(r[0], (name.startsWith("product-support") ? "old:" : "new:") + r[1]);
      }
    }
    return groups;
  }

  static List<Q> rows(
      List<Query> labels, SupportQueryMemory m, Map<String, String> groups, boolean train) {
    List<Q> out = new ArrayList<>();
    for (Query q : labels) {
      String group = train ? groups.get(q.id) : null;
      if (train && (!q.split.equals("train") || group == null))
        throw new IllegalArgumentException("Foreign training group");
      out.add(new Q(q, m, group));
    }
    return out;
  }

  static double score(Q q, int i, C c) {
    double value = 0;
    for (int j = 0; j < 3; j++) value += (double) q.x.get(i)[j] * c.w[j];
    return Math.max(0, Math.min(1, (float) value));
  }

  static List<Integer> order(Q q, C c) {
    List<Integer> out = new ArrayList<>();
    for (int i = 0; i < q.rows.size(); i++) if (score(q, i, c) > 0) out.add(i);
    out.sort(
        Comparator.comparingDouble((Integer i) -> score(q, i, c))
            .reversed()
            .thenComparing(i -> q.rows.get(i).topic.id));
    return out;
  }

  static Metrics metrics(List<Q> rows, C c, Policy p) {
    Metrics stats = new Metrics();
    for (Q q : rows) {
      List<Integer> order = order(q, c);
      List<String> rank = new ArrayList<>();
      for (int i : order) rank.add(q.rows.get(i).topic.id);
      boolean accepted =
          !order.isEmpty()
              && score(q, order.get(0), c) >= p.minimum
              && q.rows.get(order.get(0)).coverage >= p.coverage
              && (order.size() == 1
                  || score(q, order.get(0), c) - score(q, order.get(1), c) >= p.margin);
      observe(stats, q.label, rank, accepted);
    }
    return stats;
  }

  static double loss(Q q, double[] w, double[] g) {
    List<Integer> pos = new ArrayList<>(), neg = new ArrayList<>();
    for (int i = 0; i < q.rows.size(); i++) {
      if (q.label.primary.contains(q.rows.get(i).topic.id)) pos.add(i);
      else neg.add(i);
    }
    neg.sort(
        Comparator.comparingDouble((Integer i) -> dot(q.x.get(i), w))
            .reversed()
            .thenComparingInt(i -> i));
    if (neg.size() > 8) neg = neg.subList(0, 8);
    int pairs = pos.size() * neg.size();
    if (pairs == 0) return 0;
    double out = 0;
    for (int p : pos)
      for (int n : neg) {
        double z = dot(q.x.get(n), w) - dot(q.x.get(p), w);
        out += (Math.max(0, z) + Math.log1p(Math.exp(-Math.abs(z)))) / pairs;
        double d = sigmoid(z) / pairs;
        for (int j = 0; j < 3; j++) g[j] += d * (q.x.get(n)[j] - q.x.get(p)[j]);
      }
    return out;
  }

  static double dot(float[] x, double[] w) {
    double v = 0;
    for (int i = 0; i < 3; i++) v += x[i] * w[i];
    return v;
  }

  static double[] project(double[] w) {
    double[] u = w.clone();
    Arrays.sort(u);
    double sum = 0, theta = 0;
    for (int i = 0; i < u.length; i++) {
      double v = u[u.length - 1 - i];
      sum += v;
      double t = (sum - 1) / (i + 1);
      if (v > t) theta = t;
    }
    double[] out = new double[w.length];
    for (int i = 0; i < w.length; i++) out[i] = Math.max(0, w[i] - theta);
    return out;
  }

  static float[] floats(double[] w) {
    return new float[] {(float) w[0], (float) w[1], (float) w[2]};
  }

  static boolean better(C a, C b, List<Q> dev) {
    if (b == null) return true;
    Metrics x = metrics(dev, a, new Policy(0, 0, 0)), y = metrics(dev, b, new Policy(0, 0, 0));
    return x.top1 > y.top1
        || x.top1 == y.top1
            && (x.rr > y.rr + 1e-12
                || Math.abs(x.rr - y.rr) < 1e-12
                    && !a.name.equals("learned")
                    && b.name.equals("learned"));
  }

  static boolean floor(List<Q> dev, C c, HybridSupportRetriever base) {
    for (String lang : new String[] {"en", "ar", "mixed"}) {
      List<Q> part = new ArrayList<>();
      List<Query> labels = new ArrayList<>();
      for (Q q : dev)
        if (q.label.language.equals(lang)) {
          part.add(q);
          labels.add(q.label);
        }
      if (part.isEmpty()) throw new IllegalArgumentException("Missing selection language");
      if (metrics(part, c, new Policy(0, 0, 0)).top1
          < ExpandedSupportExperiment.measured(labels, base).top1) return false;
    }
    return true;
  }

  static C fit(List<Q> train, List<Q> dev, double rate, long seed, HybridSupportRetriever base) {
    if (train.isEmpty() || dev.isEmpty()) throw new IllegalArgumentException("Empty memory fit");
    for (Q q : train)
      if (!q.label.split.equals("train"))
        throw new IllegalArgumentException("Foreign memory fitting row");
    for (Q q : dev)
      if (!q.label.split.equals("development"))
        throw new IllegalArgumentException("Foreign memory selection row");
    double[] w = {1.0 / 3, 1.0 / 3, 1.0 / 3};
    C best = new C("learned", floats(w), seed, rate, 0, 0);
    int last = 0, ran = 0;
    Random rng = new Random(seed);
    List<Q> shuffled = new ArrayList<>(train);
    for (int epoch = 1; epoch <= 80; epoch++) {
      Collections.shuffle(shuffled, rng);
      for (int start = 0; start < shuffled.size(); start += 16) {
        double[] g = new double[3];
        int count = Math.min(16, shuffled.size() - start);
        for (int i = start; i < start + count; i++) loss(shuffled.get(i), w, g);
        double norm = 0;
        for (int j = 0; j < 3; j++) {
          g[j] = g[j] / count + .02 * (w[j] - 1.0 / 3);
          norm += g[j] * g[j];
        }
        double scale = Math.sqrt(norm) > 5 ? 5 / Math.sqrt(norm) : 1;
        for (int j = 0; j < 3; j++) w[j] -= rate * g[j] * scale;
        w = project(w);
      }
      C c = new C("learned", floats(w), seed, rate, epoch, epoch);
      ran = epoch;
      if (floor(dev, c, base) && better(c, best, dev)) {
        best = c;
        last = epoch;
      }
      if (epoch - last >= 10) break;
    }
    return new C(best.name, best.w, seed, rate, best.epoch, ran);
  }

  static Policy calibrate(List<Q> cal, C c) {
    for (Q q : cal)
      if (!q.label.split.equals("calibration"))
        throw new IllegalArgumentException("Foreign memory calibration row");
    Policy best = new Policy(1, 1, 1);
    int utility = Integer.MIN_VALUE, wrong = Integer.MAX_VALUE;
    for (int t = 0; t <= 40; t++)
      for (double m : new double[] {0, .025, .05, .1, .2})
        for (double coverage : new double[] {0, .25, .5, .75}) {
          Policy p = new Policy(t / 40.0, m, coverage);
          Metrics s = metrics(cal, c, p);
          int u = s.correct - 3 * s.wrong;
          if (u > utility || u == utility && s.wrong < wrong) {
            utility = u;
            wrong = s.wrong;
            best = p;
          }
        }
    return best;
  }

  static String config(C c, List<Q> dev) {
    return "{\"name\":\""
        + c.name
        + "\",\"weights\":"
        + Arrays.toString(c.w)
        + ",\"seed\":"
        + c.seed
        + ",\"rate\":"
        + c.rate
        + ",\"epoch\":"
        + c.epoch
        + ",\"epochs_run\":"
        + c.ran
        + ",\"development\":"
        + metrics(dev, c, new Policy(0, 0, 0)).json()
        + "}";
  }

  static List<Query> fresh(HybridSupportRetriever base) throws Exception {
    List<Query> out = new ArrayList<>();
    List<String> lines =
        Files.readAllLines(
            Paths.get("neural-core/fixtures/query-memory-v5/fresh.tsv"), StandardCharsets.UTF_8);
    for (String line : lines.subList(1, lines.size())) {
      String[] r = line.split("\t", -1);
      out.add(new Query(r[0], "fresh", r[2], r[5], ids(r[3]), ids(r[4]), base));
    }
    if (out.size() != 138) throw new IllegalArgumentException("Missing memory test");
    return out;
  }

  static HybridSupportRetriever base(List<ProductSupportModel.Article> docs) {
    try {
      return (HybridSupportRetriever)
          Class.forName("com.omnidev.workspace.domain.neural.TrainedExpandedSupport")
              .getMethod("createCandidate", List.class)
              .invoke(null, docs);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  static String compare(
      List<Q> rows,
      C c,
      Policy p,
      HybridSupportRetriever base,
      ProductSupportModel lexical,
      ProductSupportModel retained) {
    List<Query> labels = new ArrayList<>();
    for (Q q : rows) labels.add(q.label);
    return "{\"lexical\":"
        + previousMetrics(labels, lexical).json()
        + ",\"retained_default\":"
        + previousMetrics(labels, retained).json()
        + ",\"frozen_v4\":"
        + ExpandedSupportExperiment.measured(labels, base).json()
        + ",\"query_memory\":"
        + metrics(rows, c, p).json()
        + "}";
  }

  static String probe(String id, SupportQueryMemory.Result r) {
    StringBuilder out = new StringBuilder(id + "\t" + r.accepted);
    for (SupportQueryMemory.Hit h : r.matches)
      out.append('\t')
          .append(h.topic.id)
          .append(':')
          .append(String.format(Locale.ROOT, "%.9f", h.score))
          .append(':')
          .append(String.format(Locale.ROOT, "%.9f", h.coverage));
    return out.toString();
  }

  public static void main(String[] args) throws Exception {
    Path out = Paths.get(args[0]);
    Files.createDirectories(out);
    List<ProductSupportModel.Article> docs =
        ProductSupportExperiment.loadArticles(Paths.get("build/product-support/documents.tsv"));
    HybridSupportRetriever base = base(docs);
    List<SupportQueryMemory.Exemplar> examples = examples(out.resolve("exemplars.tsv"));
    SupportQueryMemory memory =
        new SupportQueryMemory(
            docs, base, examples, new float[] {1, 0, 0}, 0, 0, 0, null, SupportQueryMemory.VERSION);
    Map<String, String> groups = trainingGroups();
    List<Query> trainLabels = old("train", base);
    trainLabels.addAll(ExpandedSupportExperiment.load("train", base));
    List<Q> train = rows(trainLabels, memory, groups, true),
        dev = rows(ExpandedSupportExperiment.load("development", base), memory, groups, false),
        cal = rows(ExpandedSupportExperiment.load("calibration", base), memory, groups, false);
    List<C> candidates = new ArrayList<>();
    candidates.add(new C("base", new float[] {1, 0, 0}, 0, 0, 0, 0));
    candidates.add(new C("word", new float[] {0, 1, 0}, 0, 0, 0, 0));
    candidates.add(new C("character", new float[] {0, 0, 1}, 0, 0, 0, 0));
    candidates.add(new C("uniform", new float[] {1f / 3, 1f / 3, 1f / 3}, 0, 0, 0, 0));
    for (double rate : new double[] {.05, .15})
      for (long seed : new long[] {42, 97}) candidates.add(fit(train, dev, rate, seed, base));
    C selected = null;
    StringBuilder configs = new StringBuilder("[");
    for (C c : candidates) {
      if (configs.length() > 1) configs.append(',');
      configs.append(config(c, dev));
      if (floor(dev, c, base) && better(c, selected, dev)) selected = c;
    }
    if (selected == null) throw new IllegalStateException("No eligible query-memory control");
    Policy p = calibrate(cal, selected);
    String selection =
        "{\"selected\":"
            + config(selected, dev)
            + ",\"minimum\":"
            + p.minimum
            + ",\"margin\":"
            + p.margin
            + ",\"coverage\":"
            + p.coverage
            + ",\"candidates\":"
            + configs.append(']')
            + "}";
    Files.write(out.resolve("selection.json"), selection.getBytes(StandardCharsets.UTF_8));
    String source =
        "package com.omnidev.workspace.domain.neural;\n"
            + "import java.util.List;\n"
            + "public final class TrainedQueryMemory{\n"
            + "public static SupportQueryMemory"
            + " createCandidate(List<ProductSupportModel.Article>d,List<SupportQueryMemory.Exemplar>e){return"
            + " new SupportQueryMemory(d,TrainedExpandedSupport.createCandidate(d),e,"
            + ProductSupportExperiment.array(selected.w)
            + ","
            + p.minimum
            + ","
            + p.margin
            + ","
            + p.coverage
            + ",\""
            + memory.fingerprint()
            + "\",\""
            + SupportQueryMemory.VERSION
            + "\");}\n"
            + "public static ProductSupportModel"
            + " retainedDefault(List<ProductSupportModel.Article>d){return"
            + " TrainedProductSupport.create(d);}\n"
            + "private TrainedQueryMemory(){}\n"
            + "}\n";
    Files.write(out.resolve("TrainedQueryMemory.java"), source.getBytes(StandardCharsets.UTF_8));
    List<Q> fresh = rows(fresh(base), memory, groups, false);
    List<Query> old = ExpandedSupportExperiment.observed(base);
    for (String split : new String[] {"train", "development", "calibration", "fresh"})
      old.addAll(ExpandedSupportExperiment.load(split, base));
    List<Q> observed = rows(old, memory, groups, false);
    ProductSupportModel lexical = previous(docs, "TrainedProductSupport", "baseline"),
        retained = previous(docs, "TrainedProductSupport", "create");
    StringBuilder languages = new StringBuilder("{");
    for (String lang : new String[] {"en", "ar", "mixed"}) {
      List<Q> subset = new ArrayList<>();
      for (Q q : fresh) if (q.label.language.equals(lang)) subset.add(q);
      if (languages.length() > 1) languages.append(',');
      languages
          .append('"')
          .append(lang)
          .append("\":")
          .append(compare(subset, selected, p, base, lexical, retained));
    }
    String report =
        "{\"selection\":"
            + selection
            + ",\"fresh_authored\":"
            + compare(fresh, selected, p, base, lexical, retained)
            + ",\"languages\":"
            + languages.append('}')
            + ",\"observed1101\":"
            + compare(observed, selected, p, base, lexical, retained)
            + ",\"production_eligible\":false,\"independent_human_labels\":false}";
    Files.write(out.resolve("report.json"), report.getBytes(StandardCharsets.UTF_8));
    SupportQueryMemory candidate =
        new SupportQueryMemory(
            docs,
            base,
            examples,
            selected.w,
            p.minimum,
            p.margin,
            p.coverage,
            memory.fingerprint(),
            SupportQueryMemory.VERSION);
    List<Q> all = new ArrayList<>(observed);
    all.addAll(fresh);
    List<String> expected = new ArrayList<>();
    for (Q q : all) expected.add(probe(q.label.id, candidate.search(q.label.text, 10)));
    Files.write(out.resolve("expected.tsv"), expected, StandardCharsets.UTF_8);
    System.out.println(report);
  }
}
