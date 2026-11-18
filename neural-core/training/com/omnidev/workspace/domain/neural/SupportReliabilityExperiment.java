package com.omnidev.workspace.domain.neural;

import static com.omnidev.workspace.domain.neural.HybridSupportExperiment.*;
import static com.omnidev.workspace.domain.neural.ProductSupportExperiment.Network;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Eight bounded correctness fits; frozen ranking and separate temperature/decision calibration. */
public final class SupportReliabilityExperiment {
  static final class Datum {
    final Query query;
    final SupportReliabilityModel.Frame frame;
    final int correct;
    final String excludedGroup;
    final String calibrationRole;

    Datum(Query q, SupportQueryMemory memory, String excluded) {
      query = q;
      excludedGroup = excluded;
      calibrationRole = "unassigned";
      frame = new SupportReliabilityModel.Frame(memory, q.text, excluded);
      correct = !frame.hits.isEmpty() && q.primary.contains(frame.hits.get(0).topic.id) ? 1 : 0;
    }

    private Datum(Datum original, String role) {
      query = original.query;
      frame = original.frame;
      correct = original.correct;
      excludedGroup = original.excludedGroup;
      calibrationRole = role;
    }
  }

  static final class Fit {
    final Network n;
    final int epoch, ran;
    final long seed;
    final double l2, nll;

    Fit(Network n, int epoch, int ran, long seed, double l2, double nll) {
      this.n = n;
      this.epoch = epoch;
      this.ran = ran;
      this.seed = seed;
      this.l2 = l2;
      this.nll = nll;
    }

    String json() {
      return "{\"width\":"
          + n.width
          + ",\"seed\":"
          + seed
          + ",\"l2\":"
          + l2
          + ",\"epoch\":"
          + epoch
          + ",\"epochs_run\":"
          + ran
          + ",\"development_nll\":"
          + nll
          + ",\"parameters\":"
          + n.w.length
          + "}";
    }
  }

  static final class Policy {
    final double threshold, coverage, margin;
    final boolean active;

    Policy(double threshold, double coverage, double margin, boolean active) {
      this.threshold = threshold;
      this.coverage = coverage;
      this.margin = margin;
      this.active = active;
    }

    String json() {
      return "{\"threshold\":"
          + threshold
          + ",\"coverage\":"
          + coverage
          + ",\"margin\":"
          + margin
          + ",\"learned_policy_active\":"
          + active
          + "}";
    }
  }

  static final class Stats {
    int rows, correct, wrong, unknown, unknownRefused, supported, top1, allPrimaryAt3;
    double nll, brier;
    final double[] confidence = new double[10], positive = new double[10];
    final int[] bins = new int[10];

    String json() {
      double ece = 0;
      for (int i = 0; i < 10; i++)
        if (bins[i] > 0) ece += Math.abs(confidence[i] - positive[i]) / Math.max(1, rows);
      return "{\"queries\":"
          + rows
          + ",\"supported\":"
          + supported
          + ",\"top1_correct\":"
          + top1
          + ",\"all_primary_at3\":"
          + allPrimaryAt3
          + ",\"accepted_correct\":"
          + correct
          + ",\"accepted_wrong\":"
          + wrong
          + ",\"unsupported\":"
          + unknown
          + ",\"unsupported_refused\":"
          + unknownRefused
          + ",\"selector_nll\":"
          + nll / Math.max(1, rows)
          + ",\"selector_brier\":"
          + brier / Math.max(1, rows)
          + ",\"selector_ece10\":"
          + ece
          + "}";
    }
  }

  static double probability(Datum d, TinyNeuralRuntime n, double temperature) {
    return d.frame.hits.isEmpty()
        ? 0
        : ProductSupportModel.sigmoid(n.infer(d.frame.x)[0] / temperature);
  }

  static double bce(double p, int y) {
    p = Math.max(1e-12, Math.min(1 - 1e-12, p));
    return -y * Math.log(p) - (1 - y) * Math.log1p(-p);
  }

  static double macroNll(List<Datum> rows, TinyNeuralRuntime n, double temperature) {
    double sum = 0;
    for (String lang : new String[] {"en", "ar", "mixed"}) {
      double loss = 0;
      int count = 0;
      for (Datum d : rows)
        if (d.query.language.equals(lang)) {
          loss += bce(probability(d, n, temperature), d.correct);
          count++;
        }
      if (count == 0) throw new IllegalArgumentException("Missing selector language");
      sum += loss / count;
    }
    return sum / 3;
  }

  static double objective(Datum d, Network n, double[] gradient, double l2) {
    double loss = 0;
    if (!d.frame.hits.isEmpty()) {
      double z = n.forward(d.frame.x)[n.width];
      loss = Math.max(z, 0) - z * d.correct + Math.log1p(Math.exp(-Math.abs(z)));
      n.gradient(new ProductSupportExperiment.Pair(d.frame.x, d.correct), 1, gradient);
    }
    for (int i = 0; i < n.w.length; i++) {
      loss += l2 * n.w[i] * n.w[i];
      gradient[i] += 2 * l2 * n.w[i];
    }
    return loss;
  }

  static void requireSplit(List<Datum> rows, String split) {
    if (rows.isEmpty()) throw new IllegalArgumentException("Empty selector split");
    for (Datum d : rows)
      if (!d.query.split.equals(split) || split.equals("train") && d.excludedGroup == null)
        throw new IllegalArgumentException("Foreign selector " + split + " row");
  }

  static Fit fit(List<Datum> train, List<Datum> dev, int width, double l2, long seed) {
    requireSplit(train, "train");
    requireSplit(dev, "development");
    if ((width != 0 && width != 8) || (l2 != 0 && l2 != .01))
      throw new IllegalArgumentException("Foreign selector fit configuration");
    Network n = new Network(width, seed), best = new Network(width, n.w);
    double bestLoss = macroNll(dev, n.runtime(), 1);
    int bestEpoch = 0, ran = 0, step = 0;
    double[] m = new double[n.w.length], v = new double[n.w.length];
    Random random = new Random(seed);
    List<Datum> order = new ArrayList<>(train);
    for (int epoch = 1; epoch <= 120; epoch++) {
      Collections.shuffle(order, random);
      for (int start = 0; start < order.size(); start += 32) {
        int count = Math.min(32, order.size() - start);
        double[] g = new double[n.w.length];
        for (int j = start; j < start + count; j++) objective(order.get(j), n, g, l2);
        double norm = 0;
        for (int i = 0; i < g.length; i++) {
          g[i] /= count;
          norm += g[i] * g[i];
        }
        double scale = norm > 25 ? 5 / Math.sqrt(norm) : 1;
        step++;
        for (int i = 0; i < g.length; i++) {
          double delta = g[i] * scale;
          m[i] = .9 * m[i] + .1 * delta;
          v[i] = .999 * v[i] + .001 * delta * delta;
          n.w[i] -=
              .01
                  * ((m[i] / (1 - Math.pow(.9, step)))
                          / (Math.sqrt(v[i] / (1 - Math.pow(.999, step))) + 1e-8)
                      + 1e-4 * n.w[i]);
        }
      }
      double loss = macroNll(dev, n.runtime(), 1);
      ran = epoch;
      if (loss < bestLoss - 1e-8) {
        best = new Network(width, n.w);
        bestLoss = loss;
        bestEpoch = epoch;
      }
      if (epoch - bestEpoch >= 15) break;
    }
    return new Fit(best, bestEpoch, ran, seed, l2, bestLoss);
  }

  static boolean learned(Datum d, double p, Policy policy) {
    return !d.frame.hits.isEmpty()
        && p >= policy.threshold
        && d.frame.hits.get(0).coverage >= policy.coverage
        && (d.frame.hits.size() == 1
            || d.frame.hits.get(0).score - d.frame.hits.get(1).score >= policy.margin);
  }

  static boolean decision(Datum d, double p, Policy policy, String mode) {
    if (mode.equals("v5")) return d.frame.baselineAccepted;
    boolean yes = learned(d, p, policy);
    if (mode.equals("selected")) return policy.active ? yes : d.frame.baselineAccepted;
    if (mode.equals("veto")) return yes && d.frame.baselineAccepted;
    if (mode.equals("raw")) return yes;
    throw new IllegalArgumentException("Unknown selector mode");
  }

  static Stats stats(
      List<Datum> rows, TinyNeuralRuntime n, double temperature, Policy policy, String mode) {
    Stats s = new Stats();
    for (Datum d : rows) {
      double p = probability(d, n, temperature);
      boolean accept = decision(d, p, policy, mode);
      s.rows++;
      if (accept) {
        if (d.correct == 1) s.correct++;
        else s.wrong++;
      }
      if (d.query.primary.isEmpty()) {
        s.unknown++;
        if (!accept) s.unknownRefused++;
      } else {
        s.supported++;
        s.top1 += d.correct;
        Set<String> firstThree = new HashSet<>();
        for (int i = 0; i < Math.min(3, d.frame.hits.size()); i++)
          firstThree.add(d.frame.hits.get(i).topic.id);
        if (firstThree.containsAll(d.query.primary)) s.allPrimaryAt3++;
      }
      s.nll += bce(p, d.correct);
      s.brier += (p - d.correct) * (p - d.correct);
      int bin = Math.min(9, (int) (p * 10));
      s.bins[bin]++;
      s.confidence[bin] += p;
      s.positive[bin] += d.correct;
    }
    return s;
  }

  static double temperature(List<Datum> rows, TinyNeuralRuntime n) {
    requireSplit(rows, "calibration");
    if (rows.size() != 48 || rows.stream().anyMatch(d -> !d.calibrationRole.equals("temperature")))
      throw new IllegalArgumentException("Temperature requires its reserved calibration groups");
    double best = 1, loss = Double.POSITIVE_INFINITY;
    for (double t : new double[] {.5, .75, 1, 1.5, 2, 3}) {
      double next = macroNll(rows, n, t);
      if (next < loss - 1e-12) {
        best = t;
        loss = next;
      }
    }
    return best;
  }

  static Policy calibrate(List<Datum> rows, TinyNeuralRuntime n, double temperature) {
    requireSplit(rows, "calibration");
    if (rows.size() != 51 || rows.stream().anyMatch(d -> !d.calibrationRole.equals("decision")))
      throw new IllegalArgumentException("Decision calibration requires its reserved groups");
    Policy best = null;
    int utility = Integer.MIN_VALUE, wrong = Integer.MAX_VALUE;
    Stats prior = stats(rows, n, temperature, new Policy(1, 1, 1, false), "v5");
    for (int cut = 0; cut <= 40; cut++)
      for (double coverage : new double[] {0, .25, .5, .75})
        for (double margin : new double[] {0, .025, .05, .1}) {
          Policy p = new Policy(cut / 40.0, coverage, margin, true);
          Stats s = stats(rows, n, temperature, p, "raw");
          int u = s.correct - 3 * s.wrong;
          if (s.wrong <= prior.wrong && (u > utility || u == utility && s.wrong < wrong)) {
            best = p;
            utility = u;
            wrong = s.wrong;
          }
        }
    if (best == null) throw new IllegalStateException("No feasible selector policy");
    return new Policy(
        best.threshold, best.coverage, best.margin, utility > prior.correct - 3 * prior.wrong);
  }

  static List<Datum> data(
      List<Query> labels, SupportQueryMemory memory, Map<String, String> groups, boolean train)
      throws Exception {
    List<Datum> rows = new ArrayList<>();
    Map<String, String> canonical =
        train ? QueryMemoryExperiment.trainingGroups() : Collections.emptyMap();
    for (Query q : labels) {
      String group = train ? groups.get(q.id) : null;
      if (train
          && (!q.split.equals("train") || group == null || !group.equals(canonical.get(q.id))))
        throw new IllegalArgumentException("Unattributed selector TRAIN row");
      rows.add(new Datum(q, memory, group));
    }
    return rows;
  }

  static List<Query> fresh(HybridSupportRetriever base) throws Exception {
    List<Query> rows = new ArrayList<>();
    List<String> lines =
        Files.readAllLines(
            Paths.get("neural-core/fixtures/support-reliability-v6/fresh.tsv"),
            StandardCharsets.UTF_8);
    for (String line : lines.subList(1, lines.size())) {
      String[] r = line.split("\t", -1);
      rows.add(new Query(r[0], "fresh", r[2], r[5], ids(r[3]), ids(r[4]), base));
    }
    if (rows.size() != 129) throw new IllegalArgumentException("Missing selector test rows");
    return rows;
  }

  static List<Query> observed(HybridSupportRetriever base) throws Exception {
    List<Query> rows = ExpandedSupportExperiment.observed(base);
    for (String split : new String[] {"train", "development", "calibration", "fresh"})
      rows.addAll(ExpandedSupportExperiment.load(split, base));
    rows.addAll(QueryMemoryExperiment.fresh(base));
    if (rows.size() != 1239) throw new AssertionError("Missing selector regressions");
    return rows;
  }

  static String hash(String text) throws Exception {
    byte[] bytes =
        MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    StringBuilder out = new StringBuilder();
    for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
    return out.toString();
  }

  static List<Datum> calibrationPart(List<Datum> all, boolean temperature) throws Exception {
    requireSplit(all, "calibration");
    Set<String> unique = new HashSet<>();
    for (Datum d : all)
      if (!unique.add(d.query.id))
        throw new IllegalArgumentException("Duplicate selector calibration ID");
    if (all.size() != 99) throw new IllegalArgumentException("Incomplete selector calibration set");
    Map<String, String> hashes = new HashMap<>();
    Map<String, String> idGroups;
    // Calibration grouping comes from the source TSV, never from language-specific query IDs.
    List<String> lines =
        Files.readAllLines(
            Paths.get("neural-core/fixtures/support-data-v4/queries.tsv"), StandardCharsets.UTF_8);
    idGroups = new HashMap<>();
    for (String line : lines.subList(1, lines.size())) {
      String[] r = line.split("\t", -1);
      if (r[2].equals("calibration")) {
        idGroups.put(r[0], r[1]);
        hashes.put(r[1], hash(r[1]));
      }
    }
    List<String> groups = new ArrayList<>(hashes.keySet());
    groups.sort(Comparator.comparing(hashes::get));
    if (groups.size() != 33) throw new IllegalArgumentException("Selector calibration groups");
    Set<String> first = new HashSet<>(groups.subList(0, 16));
    List<Datum> out = new ArrayList<>();
    for (Datum d : all) {
      String group = idGroups.get(d.query.id);
      if (group == null) throw new IllegalArgumentException("Foreign calibration query");
      if (first.contains(group) == temperature)
        out.add(new Datum(d, temperature ? "temperature" : "decision"));
    }
    if (out.size() != (temperature ? 48 : 51))
      throw new AssertionError("Selector calibration partition");
    return out;
  }

  static String networkSource(Network n) {
    if (n.width == 0)
      return "new TinyNeuralRuntime(new TinyNeuralRuntime.Dense(12,1,"
          + ProductSupportExperiment.array(ProductSupportExperiment.floats(n.w, 0, 12))
          + ","
          + ProductSupportExperiment.array(ProductSupportExperiment.floats(n.w, 12, 1))
          + ",TinyNeuralRuntime.Activation.LINEAR))";
    int h = n.width;
    return "new TinyNeuralRuntime(new TinyNeuralRuntime.Dense(12,"
        + h
        + ","
        + ProductSupportExperiment.array(ProductSupportExperiment.floats(n.w, 0, h * 12))
        + ","
        + ProductSupportExperiment.array(ProductSupportExperiment.floats(n.w, h * 12, h))
        + ",TinyNeuralRuntime.Activation.TANH),new TinyNeuralRuntime.Dense("
        + h
        + ",1,"
        + ProductSupportExperiment.array(ProductSupportExperiment.floats(n.w, h * 13, h))
        + ","
        + ProductSupportExperiment.array(ProductSupportExperiment.floats(n.w, n.w.length - 1, 1))
        + ",TinyNeuralRuntime.Activation.LINEAR))";
  }

  static String probe(String id, SupportReliabilityModel.Result result) {
    StringBuilder out =
        new StringBuilder(
            id
                + "\t"
                + result.accepted
                + "\t"
                + result.learnedAccepted
                + "\t"
                + result.baselineAccepted
                + "\t"
                + String.format(Locale.ROOT, "%.9f", result.reliability));
    for (SupportQueryMemory.Hit hit : result.matches)
      out.append('\t')
          .append(hit.topic.id)
          .append(':')
          .append(String.format(Locale.ROOT, "%.9f", hit.score))
          .append(':')
          .append(String.format(Locale.ROOT, "%.9f", hit.coverage));
    return out.toString();
  }

  static String comparison(List<Datum> rows, Fit fit, double t, Policy p) {
    StringBuilder out = new StringBuilder("{");
    for (String mode : new String[] {"v5", "raw", "selected", "veto"}) {
      if (out.length() > 1) out.append(',');
      out.append('"')
          .append(mode)
          .append("\":")
          .append(stats(rows, fit.n.runtime(), t, p, mode).json());
    }
    return out.append('}').toString();
  }

  public static void main(String[] args) throws Exception {
    Path output = Paths.get(args[0]);
    Files.createDirectories(output);
    List<ProductSupportModel.Article> docs =
        ProductSupportExperiment.loadArticles(Paths.get("build/product-support/documents.tsv"));
    HybridSupportRetriever base = QueryMemoryExperiment.base(docs);
    SupportQueryMemory memory =
        (SupportQueryMemory)
            Class.forName("com.omnidev.workspace.domain.neural.TrainedQueryMemory")
                .getMethod("createCandidate", List.class, List.class)
                .invoke(
                    null,
                    docs,
                    QueryMemoryExperiment.examples(
                        Paths.get("build/query-memory-v5/exemplars.tsv")));
    List<Query> trainLabels = old("train", base);
    trainLabels.addAll(ExpandedSupportExperiment.load("train", base));
    List<Datum> train = data(trainLabels, memory, QueryMemoryExperiment.trainingGroups(), true),
        dev =
            data(
                ExpandedSupportExperiment.load("development", base),
                memory,
                Collections.emptyMap(),
                false),
        cal =
            data(
                ExpandedSupportExperiment.load("calibration", base),
                memory,
                Collections.emptyMap(),
                false);
    Fit selected = null;
    StringBuilder fits = new StringBuilder("[");
    for (int width : new int[] {0, 8})
      for (double l2 : new double[] {0, .01})
        for (long seed : new long[] {42, 97}) {
          Fit f = fit(train, dev, width, l2, seed);
          if (fits.length() > 1) fits.append(',');
          fits.append(f.json());
          if (selected == null
              || f.nll < selected.nll - 1e-8
              || Math.abs(f.nll - selected.nll) <= 1e-8 && f.n.w.length < selected.n.w.length)
            selected = f;
        }
    List<Datum> tempRows = calibrationPart(cal, true), decisionRows = calibrationPart(cal, false);
    double t = temperature(tempRows, selected.n.runtime());
    Policy policy = calibrate(decisionRows, selected.n.runtime(), t);
    String selection =
        "{\"selected\":"
            + selected.json()
            + ",\"temperature\":"
            + t
            + ",\"policy\":"
            + policy.json()
            + ",\"candidates\":"
            + fits.append(']')
            + "}";
    Files.write(output.resolve("selection.json"), selection.getBytes(StandardCharsets.UTF_8));
    String source =
        "package com.omnidev.workspace.domain.neural;\n"
            + "import java.util.List;\n"
            + "public final class TrainedSupportReliability{\n"
            + "public static TinyNeuralRuntime head(){return "
            + networkSource(selected.n)
            + ";}\n"
            + "public static SupportReliabilityModel"
            + " createCandidate(List<ProductSupportModel.Article>d,List<SupportQueryMemory.Exemplar>e){return"
            + " new SupportReliabilityModel(TrainedQueryMemory.createCandidate(d,e),head(),"
            + t
            + ","
            + policy.threshold
            + ","
            + policy.coverage
            + ","
            + policy.margin
            + ","
            + policy.active
            + ",\""
            + memory.contractFingerprint()
            + "\",SupportReliabilityModel.VERSION);}\n"
            + "public static ProductSupportModel"
            + " retainedDefault(List<ProductSupportModel.Article>d){return"
            + " TrainedProductSupport.create(d);}\n"
            + "private TrainedSupportReliability(){}\n"
            + "}\n";
    Files.write(
        output.resolve("TrainedSupportReliability.java"), source.getBytes(StandardCharsets.UTF_8));
    // The selected source and policy exist before any new challenge is scored.
    List<Datum> fresh = data(fresh(base), memory, Collections.emptyMap(), false),
        observed = data(observed(base), memory, Collections.emptyMap(), false);
    StringBuilder languages = new StringBuilder("{");
    for (String lang : new String[] {"en", "ar", "mixed"}) {
      List<Datum> part = new ArrayList<>();
      for (Datum d : fresh) if (d.query.language.equals(lang)) part.add(d);
      if (languages.length() > 1) languages.append(',');
      languages
          .append('"')
          .append(lang)
          .append("\":")
          .append(comparison(part, selected, t, policy));
    }
    String report =
        "{\"selection\":"
            + selection
            + ",\"train_rows\":"
            + train.size()
            + ",\"train_correct_top1\":"
            + train.stream().filter(d -> d.correct == 1).count()
            + ",\"development\":"
            + comparison(dev, selected, t, policy)
            + ",\"temperature_calibration\":"
            + comparison(tempRows, selected, t, policy)
            + ",\"decision_calibration\":"
            + comparison(decisionRows, selected, t, policy)
            + ",\"fresh_authored\":"
            + comparison(fresh, selected, t, policy)
            + ",\"languages\":"
            + languages.append('}')
            + ",\"observed_regressions\":"
            + comparison(observed, selected, t, policy)
            + ",\"production_eligible\":false,\"independent_human_labels\":false}";
    Files.write(output.resolve("report.json"), report.getBytes(StandardCharsets.UTF_8));
    SupportReliabilityModel model =
        new SupportReliabilityModel(
            memory,
            selected.n.runtime(),
            t,
            policy.threshold,
            policy.coverage,
            policy.margin,
            policy.active,
            memory.contractFingerprint(),
            SupportReliabilityModel.VERSION);
    List<Datum> all = new ArrayList<>(observed);
    all.addAll(fresh);
    StringBuilder expected = new StringBuilder(),
        risk = new StringBuilder("id\tlanguage\tprobability\tcorrect\tselected_accepted\n");
    for (Datum d : all)
      expected.append(probe(d.query.id, model.search(d.query.text, 10))).append('\n');
    TinyNeuralRuntime chosen = selected.n.runtime();
    fresh.sort(
        Comparator.comparingDouble((Datum d) -> probability(d, chosen, t))
            .reversed()
            .thenComparing(d -> d.query.id));
    for (Datum d : fresh) {
      double probability = probability(d, selected.n.runtime(), t);
      risk.append(d.query.id)
          .append('\t')
          .append(d.query.language)
          .append('\t')
          .append(probability)
          .append('\t')
          .append(d.correct)
          .append('\t')
          .append(decision(d, probability, policy, "selected"))
          .append('\n');
    }
    Files.write(
        output.resolve("expected.tsv"), expected.toString().getBytes(StandardCharsets.UTF_8));
    Files.write(
        output.resolve("risk-coverage.tsv"), risk.toString().getBytes(StandardCharsets.UTF_8));
    System.out.println(report);
  }
}
