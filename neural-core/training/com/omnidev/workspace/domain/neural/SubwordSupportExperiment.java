package com.omnidev.workspace.domain.neural;

import static com.omnidev.workspace.domain.neural.HybridSupportExperiment.*;
import static com.omnidev.workspace.domain.neural.SupportReliabilityExperiment.*;
import static com.omnidev.workspace.domain.neural.SupportReliabilityExperiment.Policy;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Matched clean/typo-view training with a frozen retrieval ranking and separate calibration. */
public final class SubwordSupportExperiment {
  static <T> T checkpoint(
      String name,
      List<ProductSupportModel.Article> docs,
      List<SupportQueryMemory.Exemplar> examples,
      Class<T> type)
      throws Exception {
    return type.cast(
        Class.forName("com.omnidev.workspace.domain.neural." + name)
            .getMethod("createCandidate", List.class, List.class)
            .invoke(null, docs, examples));
  }

  static final class Candidate {
    final Fit fit;
    final String arm;
    final double temperature;
    final Policy policy;

    Candidate(Fit f, String arm, List<Datum> calibration) {
      fit = f;
      this.arm = arm;
      temperature = temperature(calibrationPartUnchecked(calibration, true), f.n.runtime());
      policy =
          calibrateSoft(calibrationPartUnchecked(calibration, false), f.n.runtime(), temperature);
    }

    String json() {
      return "{\"arm\":\""
          + arm
          + "\",\"fit\":"
          + fit.json()
          + ",\"temperature\":"
          + temperature
          + ",\"policy\":"
          + policy.json()
          + "}";
    }
  }

  static List<Datum> calibrationPartUnchecked(List<Datum> rows, boolean temperature) {
    try {
      return calibrationPart(rows, temperature);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  static List<Datum> enriched(
      List<Query> labels, SupportQueryMemory memory, TrainTokenCoverage tokens, boolean train)
      throws Exception {
    List<Datum> out = data(labels, memory, QueryMemoryExperiment.trainingGroups(), train);
    for (Datum d : out) {
      float[] x = SupportSubwordModel.features(d.frame, tokens, d.query.text, d.excludedGroup);
      System.arraycopy(x, 0, d.frame.x, 0, 12);
    }
    return out;
  }

  static String typo(String id, String text) {
    Matcher matcher = Pattern.compile("\\p{L}{6,}").matcher(text);
    List<int[]> spans = new ArrayList<>();
    while (matcher.find())
      if (text.codePointCount(matcher.start(), matcher.end()) >= 6)
        spans.add(new int[] {matcher.start(), matcher.end()});
    if (spans.isEmpty()) return text;
    int[] span = spans.get(Math.floorMod(id.hashCode(), spans.size()));
    int length = text.codePointCount(span[0], span[1]),
        offset = text.offsetByCodePoints(span[0], length / 2);
    return text.substring(0, offset)
        + text.substring(offset + Character.charCount(text.codePointAt(offset)));
  }

  static boolean accepts(Datum d, double probability, Policy p) {
    return !d.frame.hits.isEmpty()
        && probability >= p.threshold
        && d.frame.x[3] >= p.coverage
        && (d.frame.hits.size() == 1
            || d.frame.hits.get(0).score - d.frame.hits.get(1).score >= p.margin);
  }

  static Stats measured(
      List<Datum> rows,
      TinyNeuralRuntime network,
      double temperature,
      Policy policy,
      String mode,
      SupportReliabilityModel oldV6) {
    Stats s = new Stats();
    for (Datum d : rows) {
      double probability = probability(d, network, temperature);
      boolean learned = accepts(d, probability, policy), accepted;
      switch (mode) {
        case "v5":
          accepted = d.frame.baselineAccepted;
          break;
        case "v6":
          accepted = oldV6.search(d.query.text, 3).accepted;
          break;
        case "veto":
          accepted = learned && d.frame.baselineAccepted;
          break;
        case "raw":
          accepted = learned;
          break;
        case "selected":
          accepted = policy.active ? learned : d.frame.baselineAccepted;
          break;
        default:
          throw new IllegalArgumentException("Unknown subword policy mode");
      }
      s.rows++;
      if (accepted) {
        if (d.correct == 1) s.correct++;
        else s.wrong++;
      }
      if (d.query.primary.isEmpty()) {
        s.unknown++;
        if (!accepted) s.unknownRefused++;
      } else {
        s.supported++;
        s.top1 += d.correct;
        Set<String> top = new HashSet<>();
        for (int i = 0; i < Math.min(3, d.frame.hits.size()); i++)
          top.add(d.frame.hits.get(i).topic.id);
        if (top.containsAll(d.query.primary)) s.allPrimaryAt3++;
      }
      s.nll += bce(probability, d.correct);
      s.brier += (probability - d.correct) * (probability - d.correct);
      int bin = Math.min(9, (int) (probability * 10));
      s.bins[bin]++;
      s.confidence[bin] += probability;
      s.positive[bin] += d.correct;
    }
    return s;
  }

  static Policy calibrateSoft(List<Datum> rows, TinyNeuralRuntime network, double temperature) {
    requireSplit(rows, "calibration");
    if (rows.size() != 51 || rows.stream().anyMatch(d -> !d.calibrationRole.equals("decision")))
      throw new IllegalArgumentException("Wrong soft calibration role");
    Stats old = measured(rows, network, temperature, new Policy(1, 1, 1, false), "v5", null);
    Policy best = null;
    int utility = Integer.MIN_VALUE, wrong = Integer.MAX_VALUE;
    for (int t = 0; t <= 40; t++)
      for (double coverage : new double[] {0, .25, .5, .75})
        for (double margin : new double[] {0, .025, .05, .1}) {
          Policy p = new Policy(t / 40.0, coverage, margin, true);
          Stats s = measured(rows, network, temperature, p, "raw", null);
          int u = s.correct - 3 * s.wrong;
          if (s.wrong <= old.wrong && (u > utility || u == utility && s.wrong < wrong)) {
            best = p;
            utility = u;
            wrong = s.wrong;
          }
        }
    if (best == null) throw new IllegalStateException("No feasible soft-coverage policy");
    return new Policy(
        best.threshold, best.coverage, best.margin, utility > old.correct - 3 * old.wrong);
  }

  static boolean better(Fit a, String armA, Fit b, String armB) {
    return b == null
        || a.nll < b.nll - 1e-8
        || Math.abs(a.nll - b.nll) <= 1e-8
            && (a.n.w.length < b.n.w.length
                || a.n.w.length == b.n.w.length
                    && (!armA.equals(armB) ? armA.equals("original") : a.seed < b.seed));
  }

  static String compare(List<Datum> rows, Candidate c, SupportReliabilityModel oldV6) {
    StringBuilder out = new StringBuilder("{");
    for (String mode : new String[] {"v5", "v6", "raw", "selected", "veto"}) {
      if (out.length() > 1) out.append(',');
      out.append('"')
          .append(mode)
          .append("\":")
          .append(measured(rows, c.fit.n.runtime(), c.temperature, c.policy, mode, oldV6).json());
    }
    return out.append('}').toString();
  }

  static List<Query> fresh(HybridSupportRetriever base) throws Exception {
    List<Query> out = new ArrayList<>();
    List<String> lines =
        Files.readAllLines(
            Paths.get("neural-core/fixtures/support-subword-v7/fresh.tsv"), StandardCharsets.UTF_8);
    for (String line : lines.subList(1, lines.size())) {
      String[] r = line.split("\t", -1);
      out.add(new Query(r[0], "fresh", r[2], r[5], ids(r[3]), ids(r[4]), base));
    }
    if (out.size() != 120) throw new IllegalArgumentException("Missing subword stress queries");
    return out;
  }

  static List<Query> oldQueries(HybridSupportRetriever base) throws Exception {
    List<Query> rows = observed(base);
    rows.addAll(SupportReliabilityExperiment.fresh(base));
    if (rows.size() != 1368) throw new AssertionError("Missing subword regressions");
    return rows;
  }

  static String factory(String name, Candidate c, String binding) {
    return "public static SupportSubwordModel "
        + name
        + "(List<ProductSupportModel.Article>d,List<SupportQueryMemory.Exemplar>e){SupportQueryMemory"
        + " m=TrainedQueryMemory.createCandidate(d,e);return new SupportSubwordModel(m,new"
        + " TrainTokenCoverage(e),"
        + networkSource(c.fit.n)
        + ","
        + c.temperature
        + ","
        + c.policy.threshold
        + ","
        + c.policy.coverage
        + ","
        + c.policy.margin
        + ","
        + c.policy.active
        + ",\""
        + binding
        + "\",SupportSubwordModel.VERSION);}\n";
  }

  static String probe(String id, SupportSubwordModel.Result r) {
    StringBuilder out =
        new StringBuilder(
            id
                + "\t"
                + r.accepted
                + "\t"
                + r.learnedAccepted
                + "\t"
                + r.baselineAccepted
                + "\t"
                + String.format(Locale.ROOT, "%.9f", r.reliability)
                + "\t"
                + String.format(Locale.ROOT, "%.9f", r.softCoverage));
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
    Path output = Paths.get(args[0]);
    Files.createDirectories(output);
    List<ProductSupportModel.Article> docs =
        ProductSupportExperiment.loadArticles(Paths.get("build/product-support/documents.tsv"));
    List<SupportQueryMemory.Exemplar> examples =
        QueryMemoryExperiment.examples(Paths.get("build/query-memory-v5/exemplars.tsv"));
    SupportQueryMemory memory =
        checkpoint("TrainedQueryMemory", docs, examples, SupportQueryMemory.class);
    TrainTokenCoverage tokens = new TrainTokenCoverage(examples);
    HybridSupportRetriever base = QueryMemoryExperiment.base(docs);
    List<Query> cleanLabels = old("train", base);
    cleanLabels.addAll(ExpandedSupportExperiment.load("train", base));
    List<Query> views = new ArrayList<>();
    int changed = 0;
    for (Query q : cleanLabels) {
      views.add(q);
      String text = typo(q.id, q.text);
      if (!text.equals(q.text)) changed++;
      views.add(new Query(q.id, "train", q.language, text, q.primary, q.secondary, base));
    }
    List<Datum> clean = enriched(cleanLabels, memory, tokens, true),
        aug = enriched(views, memory, tokens, true),
        dev = enriched(ExpandedSupportExperiment.load("development", base), memory, tokens, false),
        cal = enriched(ExpandedSupportExperiment.load("calibration", base), memory, tokens, false);
    Fit chosen = null, oldBest = null, augBest = null;
    String chosenArm = null;
    StringBuilder fits = new StringBuilder("[");
    for (String arm : new String[] {"original", "augmented"})
      for (int width : new int[] {0, 8})
        for (double l2 : new double[] {0, .01})
          for (long seed : new long[] {42, 97}) {
            Fit fit = fit(arm.equals("original") ? clean : aug, dev, width, l2, seed);
            if (fits.length() > 1) fits.append(',');
            fits.append("{\"arm\":\"")
                .append(arm)
                .append("\",\"fit\":")
                .append(fit.json())
                .append('}');
            if (better(fit, arm, chosen, chosenArm)) {
              chosen = fit;
              chosenArm = arm;
            }
            if (arm.equals("original") && better(fit, arm, oldBest, arm)) oldBest = fit;
            if (arm.equals("augmented") && better(fit, arm, augBest, arm)) augBest = fit;
          }
    Candidate selected = new Candidate(chosen, chosenArm, cal),
        original = new Candidate(oldBest, "original", cal),
        augmented = new Candidate(augBest, "augmented", cal);
    String selection =
        "{\"selected\":"
            + selected.json()
            + ",\"matched_original\":"
            + original.json()
            + ",\"matched_augmented\":"
            + augmented.json()
            + ",\"candidates\":"
            + fits.append(']')
            + "}";
    Files.write(output.resolve("selection.json"), selection.getBytes(StandardCharsets.UTF_8));
    String binding = SupportSubwordModel.binding(memory, tokens),
        source =
            "package com.omnidev.workspace.domain.neural;\n"
                + "import java.util.List;\n"
                + "public final class TrainedSubwordSupport{\n"
                + factory("createCandidate", selected, binding)
                + factory("createMatchedOriginal", original, binding)
                + factory("createMatchedAugmented", augmented, binding)
                + "public static ProductSupportModel"
                + " retainedDefault(List<ProductSupportModel.Article>d){return"
                + " TrainedProductSupport.create(d);}\n"
                + "private TrainedSubwordSupport(){}\n"
                + "}\n";
    Files.write(
        output.resolve("TrainedSubwordSupport.java"), source.getBytes(StandardCharsets.UTF_8));
    // Export selected weights and policy before observing any new stress-set score.
    List<Datum> fresh = enriched(fresh(base), memory, tokens, false),
        regressions = enriched(oldQueries(base), memory, tokens, false);
    SupportReliabilityModel oldV6 =
        checkpoint("TrainedSupportReliability", docs, examples, SupportReliabilityModel.class);
    StringBuilder languages = new StringBuilder("{");
    for (String lang : new String[] {"en", "ar", "mixed"}) {
      List<Datum> part = new ArrayList<>();
      for (Datum d : fresh) if (d.query.language.equals(lang)) part.add(d);
      if (languages.length() > 1) languages.append(',');
      languages.append('"').append(lang).append("\":").append(compare(part, selected, oldV6));
    }
    String report =
        "{\"selection\":"
            + selection
            + ",\"original_train_queries\":"
            + clean.size()
            + ",\"augmented_train_views\":"
            + aug.size()
            + ",\"changed_typo_views\":"
            + changed
            + ",\"lexicon_words\":"
            + tokens.wordCount()
            + ",\"lexicon_grams\":"
            + tokens.gramCount()
            + ",\"lexicon_postings\":"
            + tokens.postingCount()
            + ",\"development\":"
            + compare(dev, selected, oldV6)
            + ",\"fresh_stress\":"
            + compare(fresh, selected, oldV6)
            + ",\"matched_original_fresh\":"
            + compare(fresh, original, oldV6)
            + ",\"matched_augmented_fresh\":"
            + compare(fresh, augmented, oldV6)
            + ",\"languages\":"
            + languages.append('}')
            + ",\"observed_regressions\":"
            + compare(regressions, selected, oldV6)
            + ",\"production_eligible\":false,\"independent_human_labels\":false}";
    Files.write(output.resolve("report.json"), report.getBytes(StandardCharsets.UTF_8));
    SupportSubwordModel model =
        new SupportSubwordModel(
            memory,
            tokens,
            selected.fit.n.runtime(),
            selected.temperature,
            selected.policy.threshold,
            selected.policy.coverage,
            selected.policy.margin,
            selected.policy.active,
            binding,
            SupportSubwordModel.VERSION);
    List<Datum> all = new ArrayList<>(regressions);
    all.addAll(fresh);
    StringBuilder expected = new StringBuilder();
    for (Datum d : all)
      expected.append(probe(d.query.id, model.search(d.query.text, 10))).append('\n');
    Files.write(
        output.resolve("expected.tsv"), expected.toString().getBytes(StandardCharsets.UTF_8));
    SupportSubwordModel oldArm =
        new SupportSubwordModel(
            memory,
            tokens,
            original.fit.n.runtime(),
            original.temperature,
            original.policy.threshold,
            original.policy.coverage,
            original.policy.margin,
            original.policy.active,
            binding,
            SupportSubwordModel.VERSION);
    SupportSubwordModel augArm =
        new SupportSubwordModel(
            memory,
            tokens,
            augmented.fit.n.runtime(),
            augmented.temperature,
            augmented.policy.threshold,
            augmented.policy.coverage,
            augmented.policy.margin,
            augmented.policy.active,
            binding,
            SupportSubwordModel.VERSION);
    StringBuilder armProbes = new StringBuilder();
    for (Datum d : all)
      armProbes
          .append(
              armProbe(
                  d.query.id, oldArm.search(d.query.text, 10), augArm.search(d.query.text, 10)))
          .append('\n');
    Files.write(
        output.resolve("arm-expected.tsv"), armProbes.toString().getBytes(StandardCharsets.UTF_8));
    System.out.println(report);
  }

  static String armProbe(
      String id, SupportSubwordModel.Result original, SupportSubwordModel.Result augmented) {
    StringBuilder out = new StringBuilder(id);
    for (SupportSubwordModel.Result r : new SupportSubwordModel.Result[] {original, augmented})
      out.append('\t')
          .append(r.accepted)
          .append('\t')
          .append(r.learnedAccepted)
          .append('\t')
          .append(String.format(Locale.ROOT, "%.9f", r.reliability))
          .append('\t')
          .append(String.format(Locale.ROOT, "%.9f", r.softCoverage));
    return out.toString();
  }
}
