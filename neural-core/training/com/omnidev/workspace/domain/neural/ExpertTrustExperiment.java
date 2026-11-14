package com.omnidev.workspace.domain.neural;

import com.omnidev.workspace.domain.neural.PublicRefusalCorpus.Row;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Four counterfactual trust fits, frozen-base selection, export, then observed/new-negative
 * scoring.
 */
public final class ExpertTrustExperiment {
  static final class Item {
    final Row row;
    final UtilityExpertDecisionModel.Review review;

    Item(Row row, UtilityExpertDecisionModel.Review review) {
      this.row = row;
      this.review = review;
    }
  }

  private static final class Candidate {
    final OfflineDecisionTrainer.Model fitted;
    final TinyNeuralRuntime network;
    final String source;
    final double temperature, utility, loss;
    final double[] cutoffs;

    Candidate(
        OfflineDecisionTrainer.Model fitted,
        TinyNeuralRuntime network,
        String source,
        double temperature,
        double utility,
        double loss,
        double[] cutoffs) {
      this.fitted = fitted;
      this.network = network;
      this.source = source;
      this.temperature = temperature;
      this.utility = utility;
      this.loss = loss;
      this.cutoffs = cutoffs;
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 1
        || args.length > 2
        || args.length == 2 && !args[1].equals("--paired-rehearsal"))
      throw new IllegalArgumentException("Expected trust artifact directory");
    boolean paired = args.length == 2;
    UtilityExpertDecisionModel base = PrototypeSupportExperiment.utility(true);
    List<Row> rows = ResidualAdapterExperiment.rows();
    disjoint(rows);
    List<Row> sourceTrain = PublicRefusalCorpus.split(rows, "train"),
        fittingRows = paired ? PairedTrustRehearsal.augment(sourceTrain) : sourceTrain;
    List<Item> train = items(fittingRows, base),
        dev = items(PublicRefusalCorpus.split(rows, "development"), base),
        cal = items(PublicRefusalCorpus.split(rows, "calibration"), base);
    Map<String, List<OfflineDecisionTrainer.Example>> fitting = examples(train),
        validation = examples(dev),
        calibration = examples(cal);
    List<OfflineDecisionTrainer.Example> balanced = new ArrayList<>();
    int maximum = 0;
    for (List<OfflineDecisionTrainer.Example> stratum : fitting.values())
      maximum = Math.max(maximum, stratum.size());
    for (int i = 0; i < maximum; i++)
      for (List<OfflineDecisionTrainer.Example> stratum : fitting.values())
        balanced.add(stratum.get(i % stratum.size()));
    List<Item> constraints = new ArrayList<>(train);
    constraints.addAll(dev);
    constraints.addAll(cal);
    TinyNeuralRuntime dummy =
        new TinyNeuralRuntime(
            new TinyNeuralRuntime.Dense(
                20, 2, new float[40], new float[2], TinyNeuralRuntime.Activation.LINEAR));
    double[] disabled = {1.1, 1.1, 1.1, 1.1, 1.1};
    Candidate selected =
        new Candidate(
            null, dummy, "full_teacher_fallback", 1, utility(dev, null, null), 0, disabled);
    StringBuilder fits = new StringBuilder("[");
    for (int width : new int[] {0, 8})
      for (long seed : new long[] {42, 97}) {
        OfflineDecisionTrainer.TrainingResult fit =
            OfflineDecisionTrainer.fitEarlyStopped(
                balanced, 2, width, 200, seed, new ArrayList<>(validation.values()));
        double temperature = 1, loss = Double.POSITIVE_INFINITY;
        for (double t : new double[] {.5, .75, 1, 1.5, 2, 3, 5}) {
          double value = 0;
          for (List<OfflineDecisionTrainer.Example> s : calibration.values())
            value += OfflineDecisionTrainer.logLoss(fit.model, s, t);
          value /= calibration.size();
          if (value < loss) {
            loss = value;
            temperature = t;
          }
        }
        TinyNeuralRuntime network = fit.model.runtime().withSparseInput();
        TinyDecisionModel trust =
            new TinyDecisionModel(network, new String[] {"keep", "use"}, temperature);
        double[] cutoffs = selectCutoffs(dev, constraints, trust);
        Candidate candidate =
            new Candidate(
                fit.model,
                network,
                (paired ? "paired_" : "") + "width" + width + "_seed" + seed,
                temperature,
                utility(dev, trust, cutoffs),
                fit.validationLoss,
                cutoffs);
        if (candidate.utility > selected.utility + 1e-12
            || Math.abs(candidate.utility - selected.utility) <= 1e-12
                && selected.fitted != null
                && (network.parameterCount() < selected.network.parameterCount()
                    || network.parameterCount() == selected.network.parameterCount()
                        && candidate.loss < selected.loss - 1e-12)) selected = candidate;
        if (fits.length() > 1) fits.append(',');
        fits.append("{\"source\":")
            .append(q(candidate.source))
            .append(",\"parameters\":")
            .append(network.parameterCount())
            .append(",\"epoch\":")
            .append(fit.bestEpoch)
            .append(",\"epochs_run\":")
            .append(fit.epochsRun)
            .append(",\"validation_nll\":")
            .append(fit.validationLoss)
            .append(",\"temperature\":")
            .append(temperature)
            .append(",\"development_utility\":")
            .append(candidate.utility)
            .append(",\"cutoffs\":")
            .append(Arrays.toString(cutoffs))
            .append('}');
        System.err.println(
            "Trust "
                + candidate.source
                + " epoch="
                + fit.bestEpoch
                + " utility="
                + candidate.utility);
      }
    fits.append(']');
    Path output = Paths.get(args[0]);
    Files.createDirectories(output);
    String source =
        selected.fitted == null
            ? dummySource()
            : selected.fitted.javaSource(selected.temperature, "TrainedExpertTrust");
    source =
        source.substring(0, source.lastIndexOf('}'))
            + "public static final boolean PRODUCTION_ELIGIBLE=false;\n"
            + "public static final String FEATURE_VERSION="
            + q(ExpertTrustDecisionModel.VERSION)
            + ";\npublic static final String BASE_SHA256="
            + q(base.checkpointFingerprint())
            + ";\npublic static final String SOURCE="
            + q(selected.source)
            + ";\npublic static double[] cutoffs(){return new double[]"
            + Arrays.toString(selected.cutoffs).replace('[', '{').replace(']', '}')
            + ";}\n}\n";
    Files.write(output.resolve("TrainedExpertTrust.java"), source.getBytes(StandardCharsets.UTF_8));
    // Selection and exported bytes are fixed. Only now read the new reservation for scoring.
    rows = observed();
    int oldCount = rows.size();
    rows.addAll(fresh());
    ExpertTrustDecisionModel model =
        new ExpertTrustDecisionModel(
            base,
            selected.network,
            selected.temperature,
            selected.cutoffs,
            ExpertTrustDecisionModel.VERSION,
            base.checkpointFingerprint());
    RichIntentRefusalModel teacher = ResidualAdapterExperiment.teacher(true);
    UtilityExpertDecisionModel prototype = prototype(true);
    List<String> expected =
        new ArrayList<>(
            Arrays.asList(
                "base_sha256\t" + base.checkpointFingerprint(),
                "source\t" + selected.source,
                "temperature\t" + selected.temperature,
                "cutoffs\t" + Arrays.toString(selected.cutoffs),
                "status\tchoice\tprobabilities\ttrust_score\tfeatures"));
    TinyDecisionModel risk =
        new TinyDecisionModel(selected.network, new String[] {"keep", "use"}, selected.temperature);
    for (Row row : rows) {
      UtilityExpertDecisionModel.Review review = base.review(row.text, .6, .15);
      TinyDecisionModel.Decision after = model.classify(row.text, .6, .15);
      UtilityExpertExperiment.priorRefusal(review.before, after);
      expected.add(
          after.status
              + "\t"
              + (after.choice == null ? "-" : after.choice)
              + "\t"
              + join(after.probabilitiesCopy())
              + "\t"
              + (review.features == null ? -1 : score(risk, review))
              + "\t"
              + (review.features == null ? "-" : join(review.features)));
    }
    Files.write(output.resolve("expected.tsv"), expected, StandardCharsets.UTF_8);
    StringBuilder report =
        new StringBuilder(
                "{\"production_eligible\":false,\"prior_sets\":\"4301_observed_regressions\",\"new_reservation\":\"80_public_official_test_negatives_only_no_positive_claim\",\"base_sha256\":")
            .append(q(base.checkpointFingerprint()))
            .append(",\"paired_rehearsal\":")
            .append(paired)
            .append(",\"synthetic_train_added\":")
            .append(fittingRows.size() - sourceTrain.size())
            .append(",\"selected\":")
            .append(q(selected.source))
            .append(",\"parameters\":")
            .append(selected.network.parameterCount())
            .append(",\"active_learned_parameters\":")
            .append(selected.fitted == null ? 0 : selected.network.parameterCount())
            .append(",\"temperature\":")
            .append(selected.temperature)
            .append(",\"cutoffs\":")
            .append(Arrays.toString(selected.cutoffs))
            .append(",\"development_utility\":")
            .append(selected.utility)
            .append(",\"teacher_development_utility\":")
            .append(utility(dev, null, null))
            .append(",\"counterfactual_train\":")
            .append(count(fitting))
            .append(",\"balanced_train\":")
            .append(balanced.size())
            .append(",\"counterfactual_development\":")
            .append(count(validation))
            .append(",\"counterfactual_calibration\":")
            .append(count(calibration))
            .append(",\"fits\":")
            .append(fits)
            .append(",\"evaluations\":{");
    evaluate(
        report,
        "observed_holdout",
        held(rows.subList(0, oldCount)),
        teacher,
        base,
        prototype,
        model);
    for (String language : new String[] {"ar", "en", "mixed"}) {
      List<Row> authored = new ArrayList<>(), fresh = new ArrayList<>();
      for (Row r : rows) {
        if (r.kind.startsWith("authored_") && r.language.equals(language)) authored.add(r);
        if (r.kind.equals("trust_fresh") && r.language.equals(language)) fresh.add(r);
      }
      report.append(',');
      evaluate(report, "authored_" + language, authored, teacher, base, prototype, model);
      if (!fresh.isEmpty()) {
        report.append(',');
        evaluate(report, "new_negative_" + language, fresh, teacher, base, prototype, model);
      }
    }
    report
        .append("},\"host_timing\":")
        .append(benchmark(train, teacher, base, model))
        .append("}\n");
    if (paired) {
      int pos = report.indexOf("80_public_official_test_negatives_only_no_positive_claim");
      if (pos >= 0)
        report.replace(
            pos,
            pos + "80_public_official_test_negatives_only_no_positive_claim".length(),
            "observed_V1_negative_reservation_not_fresh");
    }
    Files.write(output.resolve("report.json"), report.toString().getBytes(StandardCharsets.UTF_8));
    System.out.print(report);
  }

  static List<Item> items(List<Row> rows, UtilityExpertDecisionModel base) {
    List<Item> out = new ArrayList<>();
    for (Row r : rows) out.add(new Item(r, base.review(r.text, .6, .15)));
    return out;
  }

  static int counterfactualLabel(Item item) {
    return correctRaw(item.row, item.review.rawExpert) && !correct(item.row, item.review.before)
        ? 1
        : 0;
  }

  static boolean counterfactual(Item item) {
    return item.review.rawExpert != null
        && !Objects.equals(item.review.before.choice, item.review.rawExpert.choice);
  }

  static Map<String, List<OfflineDecisionTrainer.Example>> examples(List<Item> items) {
    Map<String, List<OfflineDecisionTrainer.Example>> out = new TreeMap<>();
    for (Item item : items)
      if (counterfactual(item)) {
        int label = counterfactualLabel(item);
        out.computeIfAbsent(item.row.language + ":" + label, k -> new ArrayList<>())
            .add(new OfflineDecisionTrainer.Example(item.row.group, item.review.features, label));
      }
    if (out.isEmpty()) throw new IllegalArgumentException("No counterfactual examples");
    return out;
  }

  private static int count(Map<String, List<OfflineDecisionTrainer.Example>> strata) {
    int n = 0;
    for (List<?> s : strata.values()) n += s.size();
    return n;
  }

  static double[] selectCutoffs(List<Item> dev, List<Item> constraints, TinyDecisionModel risk) {
    double[] cutoffs = {1.1, 1.1, 1.1, 1.1, 1.1};
    Map<String, Integer> totals = totals(dev);
    IdentityHashMap<Item, Double> scores = new IdentityHashMap<>();
    for (Item item : constraints)
      scores.put(item, item.review.features == null ? 0 : score(risk, item.review));
    for (Item item : dev)
      scores.put(item, item.review.features == null ? 0 : score(risk, item.review));
    for (int domain = 0; domain < 5; domain++) {
      double best = Double.NEGATIVE_INFINITY;
      for (double cutoff : new double[] {1.1, .99, .95, .9, .8, .7, .6, .5, 0}) {
        boolean eligible = true;
        for (Item item : constraints)
          if (domain(item) == domain
              && correct(item.row, item.review.before)
              && !correct(
                  item.row,
                  ExpertTrustDecisionModel.select(item.review, scores.get(item), cutoff))) {
            eligible = false;
            break;
          }
        if (!eligible) continue;
        double value = 0;
        for (Item item : dev)
          if (domain(item) == domain
              && correct(
                  item.row, ExpertTrustDecisionModel.select(item.review, scores.get(item), cutoff)))
            value += 1.0 / totals.get(stratum(item));
        if (value > best + 1e-12) {
          best = value;
          cutoffs[domain] = cutoff;
        }
      }
    }
    return cutoffs;
  }

  private static double utility(List<Item> items, TinyDecisionModel risk, double[] cutoffs) {
    Map<String, Integer> totals = totals(items);
    double sum = 0;
    for (Item item : items) {
      TinyDecisionModel.Decision d =
          risk == null || item.review.features == null
              ? item.review.before
              : ExpertTrustDecisionModel.select(
                  item.review, score(risk, item.review), cutoffs[domain(item)]);
      if (correct(item.row, d)) sum += 1.0 / totals.get(stratum(item));
    }
    return sum / totals.size();
  }

  private static int domain(Item item) {
    return item.review.before.choice == null
        ? -1
        : UtilityExpertDecisionModel.domain(item.review.before.choice);
  }

  private static String stratum(Item item) {
    return item.row.language + ":" + item.row.label;
  }

  private static Map<String, Integer> totals(List<Item> items) {
    Map<String, Integer> out = new TreeMap<>();
    for (Item item : items) out.merge(stratum(item), 1, Integer::sum);
    return out;
  }

  static boolean correct(Row row, TinyDecisionModel.Decision decision) {
    return row.label == 1
        ? decision.status == TinyDecisionModel.Status.ABSTAIN
        : Objects.equals(row.expected, decision.choice);
  }

  private static boolean correctRaw(Row row, TinyDecisionModel.Decision decision) {
    return decision != null
        && (row.label == 1
            ? "__reject__".equals(decision.choice)
            : Objects.equals(row.expected, decision.choice));
  }

  static double score(TinyDecisionModel model, UtilityExpertDecisionModel.Review review) {
    return model.decide(review.features, 0, 0).probabilitiesCopy()[1];
  }

  static List<Row> observed() throws Exception {
    List<Row> rows = ResidualAdapterExperiment.rows();
    rows.addAll(
        PublicRefusalCorpus.old(
            BilingualRefusalCorpus.freshEnglish(
                Paths.get("neural-core/fixtures/rich-text/fresh-english.tsv"))));
    rows.addAll(
        PublicRefusalCorpus.old(
            BilingualRefusalCorpus.challenges(
                Paths.get("neural-core/fixtures/decision-challenges.tsv"))));
    rows.addAll(ResidualAdapterExperiment.fresh());
    rows.addAll(UtilityExpertExperiment.fresh());
    rows.addAll(PrototypeSupportExperiment.authored());
    return rows;
  }

  static List<Row> fresh() throws Exception {
    List<String> lines =
        Files.readAllLines(
            Paths.get("neural-core/fixtures/expert-trust/fresh.tsv"), StandardCharsets.UTF_8);
    if (lines.size() != 81
        || !lines
            .get(0)
            .equals("source_id\tsource_partition\tlocale\tintent\texpected_domain\ttext"))
      throw new IllegalArgumentException("Trust reservation schema");
    List<Row> rows = new ArrayList<>();
    for (String line : lines.subList(1, lines.size())) {
      String[] c = line.split("\t", -1);
      if (c.length != 6 || !c[1].equals("test") || !c[4].equals("-"))
        throw new IllegalArgumentException("Trust negative-only reservation changed");
      rows.add(
          new Row(
              "test",
              "trust-fresh-" + c[0],
              c[2].equals("ar-SA") ? "ar" : "en",
              "-",
              "trust_fresh",
              c[5]));
    }
    return rows;
  }

  static UtilityExpertDecisionModel prototype(boolean sparse) throws Exception {
    Class<?> b = Class.forName("TrainedDomainPrototypes"),
        p = Class.forName("TrainedPrototypePolicy");
    return PrototypeSupportExperiment.model(
        (DomainPrototypeSupport) b.getMethod("model").invoke(null),
        (double[]) p.getMethod("cutoffs").invoke(null),
        (double[]) p.getMethod("reject").invoke(null),
        (double[]) p.getMethod("change").invoke(null),
        sparse);
  }

  private static List<Row> held(List<Row> rows) {
    List<Row> out = new ArrayList<>();
    for (Row r : rows) if (r.split.equals("test") || r.split.equals("regression")) out.add(r);
    return out;
  }

  private static void disjoint(List<Row> rows) {
    Map<String, String> groups = new HashMap<>();
    for (Row r : rows) {
      String old = groups.putIfAbsent(r.group, r.split);
      if (old != null && !old.equals(r.split))
        throw new IllegalArgumentException("Trust partition leakage");
    }
  }

  private static void evaluate(
      StringBuilder out,
      String name,
      List<Row> rows,
      RichIntentRefusalModel teacher,
      UtilityExpertDecisionModel utility,
      UtilityExpertDecisionModel prototype,
      ExpertTrustDecisionModel trust) {
    int known = 0;
    for (Row r : rows) if (r.label == 0) known++;
    out.append(q(name))
        .append(":{\"rows\":")
        .append(rows.size())
        .append(",\"known\":")
        .append(known)
        .append(",\"required_refusals\":")
        .append(rows.size() - known);
    for (int mode = 0; mode < 4; mode++) {
      int accepted = 0, right = 0, refused = 0, lost = 0;
      List<String> losses = new ArrayList<>();
      for (Row r : rows) {
        TinyDecisionModel.Decision before = teacher.classify(r.text, .6, .15),
            after =
                mode == 0
                    ? before
                    : mode == 1
                        ? utility.classify(r.text, .6, .15)
                        : mode == 2
                            ? prototype.classify(r.text, .6, .15)
                            : trust.classify(r.text, .6, .15);
        UtilityExpertExperiment.priorRefusal(before, after);
        if (after.status == TinyDecisionModel.Status.CHOICE) {
          accepted++;
          if (Objects.equals(r.expected, after.choice)) right++;
        } else if (r.label == 1) refused++;
        if (r.label == 0 && correct(r, before) && !correct(r, after)) {
          lost++;
          if (losses.size() < 64)
            losses.add("{\"group\":" + q(r.group) + ",\"language\":" + q(r.language) + "}");
        }
      }
      out.append(',')
          .append(q(new String[] {"teacher", "utility_v2", "prototype", "trust"}[mode]))
          .append(":{\"accepted\":")
          .append(accepted)
          .append(",\"accepted_correct\":")
          .append(right)
          .append(",\"accepted_errors\":")
          .append(accepted - right)
          .append(",\"unsupported_refused\":")
          .append(refused)
          .append(",\"previous_correct_lost\":")
          .append(lost)
          .append(",\"lost_cases\":[")
          .append(String.join(",", losses))
          .append("],\"lost_cases_truncated\":")
          .append(lost > 64)
          .append('}');
    }
    out.append('}');
  }

  private static volatile double sink;

  private static String benchmark(
      List<Item> train,
      RichIntentRefusalModel teacher,
      UtilityExpertDecisionModel base,
      ExpertTrustDecisionModel model) {
    List<String> texts = new ArrayList<>();
    Map<String, Integer> counts = new TreeMap<>();
    for (Item item : train)
      if (counts.getOrDefault(item.row.expected, 0) < 24) {
        texts.add(item.row.text);
        counts.merge(item.row.expected, 1, Integer::sum);
      }
    if (texts.size() != 144) throw new AssertionError("Trust timing strata");
    for (int m = 0; m < 3; m++)
      for (int i = 0; i < 512; i++) consume(infer(m, texts.get(i % 144), teacher, base, model));
    double[][] raw = new double[3][8];
    for (int r = 0; r < 8; r++)
      for (int p = 0; p < 3; p++) {
        int m = (r + p) % 3;
        long t = System.nanoTime();
        for (String text : texts) consume(infer(m, text, teacher, base, model));
        raw[m][r] = (System.nanoTime() - t) / 144.0;
      }
    StringBuilder out =
        new StringBuilder(
                "{\"phone_measurement\":false,\"encoding_included\":true,\"calls_per_batch\":144,\"warmup_per_mode\":512,\"batches\":8,\"checksum\":")
            .append(sink);
    for (int m = 0; m < 3; m++) {
      double[] s = raw[m].clone();
      Arrays.sort(s);
      out.append(',')
          .append(q(new String[] {"teacher", "utility_v2", "trust"}[m]))
          .append(":{\"batch_average_ns\":")
          .append(Arrays.toString(raw[m]))
          .append(",\"median_batch_average_ns\":")
          .append((s[3] + s[4]) / 2)
          .append('}');
    }
    return out.append('}').toString();
  }

  private static TinyDecisionModel.Decision infer(
      int mode,
      String text,
      RichIntentRefusalModel teacher,
      UtilityExpertDecisionModel base,
      ExpertTrustDecisionModel trust) {
    return mode == 0
        ? teacher.classify(text, .6, .15)
        : mode == 1 ? base.classify(text, .6, .15) : trust.classify(text, .6, .15);
  }

  private static void consume(TinyDecisionModel.Decision d) {
    double s = 0;
    for (double p : d.probabilitiesCopy()) s += p * p;
    sink = s;
  }

  private static String dummySource() {
    return "import com.omnidev.workspace.domain.neural.*;\n"
        + "public final class TrainedExpertTrust{public static final double"
        + " TEMPERATURE=1;public static TinyNeuralRuntime model(){return new"
        + " TinyNeuralRuntime(new TinyNeuralRuntime.Dense(20,2,new float[40],new"
        + " float[2],TinyNeuralRuntime.Activation.LINEAR));}\n"
        + "}";
  }

  private static String join(double[] values) {
    StringBuilder out = new StringBuilder();
    for (double v : values) {
      if (out.length() > 0) out.append(',');
      out.append(v);
    }
    return out.toString();
  }

  private static String join(float[] values) {
    double[] x = new double[values.length];
    for (int i = 0; i < x.length; i++) x[i] = values[i];
    return join(x);
  }

  private static String q(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
  }

  private ExpertTrustExperiment() {}
}
