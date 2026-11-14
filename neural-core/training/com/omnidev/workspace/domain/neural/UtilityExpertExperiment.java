package com.omnidev.workspace.domain.neural;

import com.omnidev.workspace.domain.neural.DistilledAdamTrainer.Example;
import com.omnidev.workspace.domain.neural.PublicRefusalCorpus.Row;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Four new fits plus an explicit SGD control; select calibrated domain policies by retained outcome
 * utility.
 */
public final class UtilityExpertExperiment {
  private static volatile double sink;

  private static final class Candidate {
    final TinyNeuralRuntime runtime;
    final DistilledAdamTrainer.Model model;
    final String source;
    final double temperature, loss;
    final ExpertPolicyTrainer.Policy policy;

    Candidate(
        TinyNeuralRuntime runtime,
        DistilledAdamTrainer.Model model,
        String source,
        double temperature,
        double loss,
        ExpertPolicyTrainer.Policy policy) {
      this.runtime = runtime;
      this.model = model;
      this.source = source;
      this.temperature = temperature;
      this.loss = loss;
      this.policy = policy;
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Expected utility output directory");
    List<Row> rows = ResidualAdapterExperiment.rows();
    RichTextEncoder encoder = PublicRefusalExperiment.encoder();
    RichIntentRefusalModel teacher = ResidualAdapterExperiment.teacher(true);
    List<Row> train = PublicRefusalCorpus.split(rows, "train"),
        dev = PublicRefusalCorpus.split(rows, "development"),
        cal = PublicRefusalCorpus.split(rows, "calibration");
    Map<Row, float[]> features = new IdentityHashMap<>();
    Map<Row, TinyDecisionModel.Decision> before = new IdentityHashMap<>();
    for (Row r : rows)
      if (Arrays.asList("train", "development", "calibration").contains(r.split)) {
        features.put(r, encoder.encode(r.text));
        before.put(r, teacher.classify(r.text, .6, .15));
      }
    List<Example> trainExamples = examples(train, features, before, true),
        validation = examples(dev, features, before, false),
        calibration = examples(cal, features, before, false);
    requireDisjoint(rows);
    Map<String, List<Example>> strata = new TreeMap<>();
    for (Example e : trainExamples)
      strata.computeIfAbsent(e.language + ":" + e.label, k -> new ArrayList<>()).add(e);
    if (strata.size() != 18)
      throw new IllegalArgumentException("Missing utility language/class strata");
    int maximum = 0;
    for (List<Example> g : strata.values()) maximum = Math.max(maximum, g.size());
    List<Example> balanced = new ArrayList<>();
    for (int i = 0; i < maximum; i++)
      for (List<Example> g : strata.values()) balanced.add(g.get(i % g.size()));
    double referenceDifference = 0;
    Candidate selected = null;
    StringBuilder details = new StringBuilder("[");
    for (int width : new int[] {0, 16})
      for (long seed : new long[] {42, 97}) {
        DistilledAdamTrainer.Result fitted =
            DistilledAdamTrainer.fit(balanced, validation, width, seed);
        double[] snapshot = DistilledAdamTrainer.evaluate(fitted.model, validation, 1);
        if (Math.abs(snapshot[0] - fitted.accuracy) > 1e-9
            || Math.abs(snapshot[1] - fitted.loss) > 1e-9)
          throw new AssertionError("Adam best snapshot drift");
        double temperature = 1, bestCal = Double.POSITIVE_INFINITY;
        for (double t : new double[] {.5, .75, 1, 1.5, 2, 3, 5}) {
          double loss = DistilledAdamTrainer.evaluate(fitted.model, calibration, t)[1];
          if (loss < bestCal) {
            bestCal = loss;
            temperature = t;
          }
        }
        if (width == 16 && seed == 42) {
          TinyNeuralRuntime reference =
              (TinyNeuralRuntime)
                  Class.forName("TrainedUtilityV1Reference").getMethod("model").invoke(null);
          for (float[] x : features.values()) {
            float[] a = reference.infer(x), b = fitted.model.runtime().infer(x);
            for (int i = 0; i < 6; i++)
              referenceDifference = Math.max(referenceDifference, Math.abs((double) a[i] - b[i]));
          }
          if (referenceDifference > 2e-6)
            throw new AssertionError("Archived v1 neural refit drift");
        }
        TinyNeuralRuntime runtime = fitted.model.runtime().withSparseInput();
        ExpertPolicyTrainer.Policy policy =
            policy(runtime, temperature, dev, train, features, before, teacher);
        Candidate candidate =
            new Candidate(
                runtime,
                fitted.model,
                "adamw_width" + width + "_seed" + seed,
                temperature,
                fitted.loss,
                policy);
        System.err.printf(
            Locale.ROOT,
            "Utility width=%d seed=%d epoch=%d raw_dev_macro=%.6f policy_utility=%.6f%n",
            width,
            seed,
            fitted.epoch,
            fitted.accuracy,
            policy.utility);
        if (details.length() > 1) details.append(',');
        details.append(
            String.format(
                Locale.ROOT,
                "{\"source\":\"%s\",\"parameters\":%d,\"epoch\":%d,\"epochs_run\":%d,\"development_macro_accuracy\":%.9f,\"development_macro_nll\":%.9f,\"temperature\":%s,\"policy_utility\":%.9f}",
                candidate.source,
                runtime.parameterCount(),
                fitted.epoch,
                fitted.epochsRun,
                fitted.accuracy,
                fitted.loss,
                temperature,
                policy.utility));
        if (better(candidate, selected)) selected = candidate;
      }
    Class<?> control = Class.forName("TrainedPublicDecision");
    TinyNeuralRuntime old = (TinyNeuralRuntime) control.getMethod("model").invoke(null);
    double oldTemperature = control.getField("TEMPERATURE").getDouble(null);
    ExpertPolicyTrainer.Policy oldPolicy =
        policy(old.withSparseInput(), oldTemperature, dev, train, features, before, teacher);
    double oldLoss = macroLoss(old, oldTemperature, validation);
    Candidate fixed =
        new Candidate(
            old.withSparseInput(), null, "frozen_sgd_control", oldTemperature, oldLoss, oldPolicy);
    details
        .append(
            String.format(
                Locale.ROOT,
                ",{\"source\":\"frozen_sgd_control\",\"parameters\":6150,\"temperature\":%s,\"policy_utility\":%.9f}",
                oldTemperature,
                oldPolicy.utility))
        .append(']');
    if (better(fixed, selected)) selected = fixed;
    Path output = Paths.get(args[0]);
    Files.createDirectories(output);
    String source =
        selected.model != null
            ? selected.model.javaSource(
                selected.temperature,
                selected.policy.reject,
                selected.policy.change,
                selected.policy.support,
                encoder.fingerprint())
            : controlSource(selected.policy);
    source =
        source.replace(
            "public final class TrainedUtilityExpert {",
            "public final class TrainedUtilityExpert {\n"
                + "public static final boolean PRODUCTION_ELIGIBLE=false;\n"
                + "public static final String SOURCE=\""
                + selected.source
                + "\";\n");
    Files.write(
        output.resolve("TrainedUtilityExpert.java"), source.getBytes(StandardCharsets.UTF_8));
    // Weight, expert/control and policy selection are complete before any fresh text is loaded or
    // scored.
    rows.addAll(
        PublicRefusalCorpus.old(
            BilingualRefusalCorpus.freshEnglish(
                Paths.get("neural-core/fixtures/rich-text/fresh-english.tsv"))));
    rows.addAll(
        PublicRefusalCorpus.old(
            BilingualRefusalCorpus.challenges(
                Paths.get("neural-core/fixtures/decision-challenges.tsv"))));
    rows.addAll(ResidualAdapterExperiment.fresh());
    List<Row> fresh = fresh();
    rows.addAll(fresh);
    UtilityExpertDecisionModel chosen =
        model(
            selected.runtime,
            selected.temperature,
            selected.policy.reject,
            selected.policy.change,
            selected.policy.support,
            true);
    List<String> numeric =
        new ArrayList<>(
            Arrays.asList(
                "source\t" + selected.source,
                "vocabulary_sha256\t" + encoder.fingerprint(),
                "temperature\t" + selected.temperature,
                "reject\t" + Arrays.toString(selected.policy.reject),
                "change\t" + Arrays.toString(selected.policy.change),
                "support\t" + Arrays.toString(selected.policy.support),
                "status\tchoice\tprobabilities\texpert_probabilities"));
    TinyDecisionModel expert =
        new TinyDecisionModel(
            selected.runtime, RichTextDecisionModel.labels(), selected.temperature);
    for (Row r : rows) {
      TinyDecisionModel.Decision d = chosen.classify(r.text, .6, .15),
          prior = teacher.classify(r.text, .6, .15);
      priorRefusal(prior, d);
      numeric.add(
          d.status
              + "\t"
              + (d.choice == null ? "-" : d.choice)
              + "\t"
              + join(d.probabilitiesCopy())
              + "\t"
              + join(expert.decide(encoder.encode(r.text), 0, 0).probabilitiesCopy()));
    }
    Files.write(output.resolve("expected.tsv"), numeric, StandardCharsets.UTF_8);
    StringBuilder report =
        new StringBuilder(
                "{\"production_eligible\":false,\"old_sets\":\"all_v2_tests_observed_followup_regressions\",\"fresh_supported_source\":\"unused"
                    + " upstream TRAIN, held out from every own fit; volume up/mute"
                    + " only\",\"candidates\":")
            .append(details)
            .append(",\"v1_refit_max_logit_difference\":")
            .append(referenceDifference)
            .append(",\"selected_source\":\"")
            .append(selected.source)
            .append("\",\"parameters\":")
            .append(selected.runtime.parameterCount())
            .append(",\"temperature\":")
            .append(selected.temperature)
            .append(",\"reject\":")
            .append(Arrays.toString(selected.policy.reject))
            .append(",\"change\":")
            .append(Arrays.toString(selected.policy.change))
            .append(",\"support\":")
            .append(Arrays.toString(selected.policy.support))
            .append(",\"development_policy_utility\":")
            .append(selected.policy.utility)
            .append(",\"train_unique\":")
            .append(train.size())
            .append(",\"train_balanced\":")
            .append(balanced.size())
            .append(",\"evaluations\":{");
    boolean first = true;
    for (String language : new String[] {"ar", "en"}) {
      if (!first) report.append(',');
      first = false;
      List<Row> subset = new ArrayList<>();
      for (Row r : rows)
        if (r.split.equals("test")
            && r.language.equals(language)
            && (r.kind.equals("massive") || r.kind.equals("held_out_topic"))) subset.add(r);
      evaluate(report, "massive_" + language, subset, teacher, chosen);
    }
    for (String kind :
        new String[] {
          "all",
          "old_original",
          "old_composed_same",
          "old_composed_cross",
          "old_fresh_english",
          "old_old_challenge"
        }) {
      List<Row> subset = new ArrayList<>();
      for (Row r : rows)
        if (r.kind.startsWith("old_")
            && Arrays.asList("test", "regression").contains(r.split)
            && (kind.equals("all") || r.kind.equals(kind))) subset.add(r);
      report.append(',');
      evaluate(report, "regression_" + kind, subset, teacher, chosen);
    }
    for (String language : new String[] {"ar", "en"}) {
      List<Row> subset = new ArrayList<>();
      for (Row r : fresh) if (r.language.equals(language)) subset.add(r);
      report.append(',');
      evaluate(report, "reserved_" + language, subset, teacher, chosen);
    }
    List<Row> holdout = new ArrayList<>(), priorReservation = new ArrayList<>();
    for (Row row : rows) {
      if (Arrays.asList("test", "regression").contains(row.split)) holdout.add(row);
      if (row.kind.equals("fresh")) priorReservation.add(row);
    }
    report.append(',');
    evaluate(report, "all_observed_holdout", holdout, teacher, chosen);
    report.append(',');
    evaluate(report, "prior_residual_reservation", priorReservation, teacher, chosen);
    report.append("},\"host_timing\":");
    benchmark(report, train, teacher, chosen);
    report.append("}\n");
    Files.write(output.resolve("report.json"), report.toString().getBytes(StandardCharsets.UTF_8));
    System.out.print(report);
  }

  static UtilityExpertDecisionModel model(
      TinyNeuralRuntime runtime,
      double temperature,
      double[] reject,
      double[] change,
      double[] support,
      boolean sparse)
      throws Exception {
    return new UtilityExpertDecisionModel(
        ResidualAdapterExperiment.teacher(sparse),
        PublicRefusalExperiment.encoder(),
        sparse ? runtime.withSparseInput() : runtime,
        temperature,
        reject,
        change,
        support,
        PublicRefusalExperiment.encoder().fingerprint());
  }

  static List<Row> fresh() throws Exception {
    Path path = Paths.get("neural-core/fixtures/utility-expert/fresh.tsv");
    if (Files.size(path) > 100_000)
      throw new IllegalArgumentException("Utility fresh bounds exceeded");
    byte[] bytes = Files.readAllBytes(path);
    StringBuilder hash = new StringBuilder();
    for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
      hash.append(String.format(Locale.ROOT, "%02x", b & 255));
    if (!hash.toString().equals("adc025471833aa05b2affa2f296bdf83fea1be7c50215aebbcc3706a41204213"))
      throw new IllegalArgumentException("Utility fresh hash changed");
    List<String> lines = Arrays.asList(new String(bytes, StandardCharsets.UTF_8).split("\n"));
    if (!lines.get(0).equals("source_id\tsource_partition\tlocale\tintent\texpected_domain\ttext"))
      throw new IllegalArgumentException("Utility fresh schema changed");
    List<Row> rows = new ArrayList<>();
    for (String line : lines.subList(1, lines.size())) {
      String[] c = line.split("\t", -1);
      if (c.length != 6) throw new IllegalArgumentException("Utility fresh row changed");
      rows.add(
          new Row(
              "test",
              "massive-" + c[0],
              c[2].startsWith("ar") ? "ar" : "en",
              c[4],
              c[4].equals("-") ? "fresh_unsupported_test" : "fresh_supported_train",
              c[5]));
    }
    return rows;
  }

  private static List<Example> examples(
      List<Row> rows,
      Map<Row, float[]> features,
      Map<Row, TinyDecisionModel.Decision> before,
      boolean fit) {
    List<Example> result = new ArrayList<>();
    List<String> labels = Arrays.asList(RichTextDecisionModel.labels());
    for (Row r : rows) {
      int label = r.label == 1 ? 5 : labels.indexOf(r.expected);
      double[] target = new double[6];
      Arrays.fill(target, .02 / 6);
      target[label] += .98;
      if (fit
          && r.kind.startsWith("old_")
          && r.label == 0
          && Objects.equals(before.get(r).choice, r.expected)) {
        double[] teacher = before.get(r).probabilitiesCopy();
        for (int i = 0; i < 6; i++) target[i] = (target[i] + (i < 5 ? teacher[i] : 0)) / 2;
      }
      result.add(new Example(r.group, r.language, label, features.get(r), target));
    }
    return result;
  }

  static ExpertPolicyTrainer.Policy policy(
      TinyNeuralRuntime runtime,
      double temperature,
      List<Row> rows,
      List<Row> training,
      Map<Row, float[]> features,
      Map<Row, TinyDecisionModel.Decision> before,
      RichIntentRefusalModel teacher) {
    TinyDecisionModel expert =
        new TinyDecisionModel(runtime, RichTextDecisionModel.labels(), temperature);
    List<ExpertPolicyTrainer.Observation> observations = new ArrayList<>(),
        rehearsal = new ArrayList<>();
    for (Row r : rows) observations.add(observation(r, expert, features, before, teacher));
    for (Row r : training)
      if (r.label == 0 && Objects.equals(before.get(r).choice, r.expected))
        rehearsal.add(observation(r, expert, features, before, teacher));
    return ExpertPolicyTrainer.fit(observations, rehearsal);
  }

  private static ExpertPolicyTrainer.Observation observation(
      Row row,
      TinyDecisionModel expert,
      Map<Row, float[]> features,
      Map<Row, TinyDecisionModel.Decision> before,
      RichIntentRefusalModel teacher) {
    TinyDecisionModel.Decision d = before.get(row);
    double support =
        d.status == TinyDecisionModel.Status.CHOICE
            ? teacher.domainSupport(features.get(row))[UtilityExpertDecisionModel.domain(d.choice)]
            : 0;
    return new ExpertPolicyTrainer.Observation(
        row, d, expert.decide(features.get(row), 0, 0), support);
  }

  private static boolean better(Candidate a, Candidate b) {
    return b == null
        || a.policy.utility > b.policy.utility + 1e-12
        || Math.abs(a.policy.utility - b.policy.utility) <= 1e-12
            && (a.runtime.parameterCount() < b.runtime.parameterCount()
                || a.runtime.parameterCount() == b.runtime.parameterCount() && a.loss < b.loss);
  }

  private static double macroLoss(
      TinyNeuralRuntime runtime, double temperature, List<Example> rows) {
    TinyDecisionModel d =
        new TinyDecisionModel(runtime, RichTextDecisionModel.labels(), temperature);
    Map<String, double[]> strata = new TreeMap<>();
    for (Example e : rows) {
      double[] n = strata.computeIfAbsent(e.language + ":" + e.label, k -> new double[2]);
      n[0] -= Math.log(Math.max(1e-30, d.decide(e.x, 0, 0).probabilitiesCopy()[e.label]));
      n[1]++;
    }
    double loss = 0;
    for (double[] n : strata.values()) loss += n[0] / n[1];
    return loss / strata.size();
  }

  private static void requireDisjoint(List<Row> rows) {
    Map<String, String> groups = new HashMap<>();
    for (Row r : rows) {
      String old = groups.putIfAbsent(r.group, r.split);
      if (old != null && !old.equals(r.split))
        throw new IllegalArgumentException("Utility group leakage");
    }
  }

  private static String controlSource(ExpertPolicyTrainer.Policy policy) throws Exception {
    String s =
        new String(
                Files.readAllBytes(
                    Paths.get(
                        "neural-core/checkpoints/public-decision-v1/TrainedPublicDecision.java")),
                StandardCharsets.UTF_8)
            .replace("TrainedPublicDecision", "TrainedUtilityExpert");
    String methods =
        "\npublic static double[] reject(){return new double[]"
            + Arrays.toString(policy.reject).replace('[', '{').replace(']', '}')
            + ";}\npublic static double[] change(){return new double[]"
            + Arrays.toString(policy.change).replace('[', '{').replace(']', '}')
            + ";}\n";
    methods +=
        "public static double[] support(){return new double[]"
            + Arrays.toString(policy.support).replace('[', '{').replace(']', '}')
            + ";}\n";
    return s.replace(
        "public final class TrainedUtilityExpert {",
        "public final class TrainedUtilityExpert {" + methods);
  }

  private static String join(double[] values) {
    StringBuilder s = new StringBuilder();
    for (int i = 0; i < values.length; i++) {
      if (i > 0) s.append(',');
      s.append(values[i]);
    }
    return s.toString();
  }

  static void priorRefusal(TinyDecisionModel.Decision before, TinyDecisionModel.Decision after) {
    if (before.status == TinyDecisionModel.Status.ABSTAIN
        && (after.status != before.status
            || !Arrays.equals(before.probabilitiesCopy(), after.probabilitiesCopy())))
      throw new AssertionError("Utility expert upgraded prior refusal");
  }

  private static void evaluate(
      StringBuilder report,
      String name,
      List<Row> rows,
      RichIntentRefusalModel teacher,
      UtilityExpertDecisionModel model) {
    int known = 0;
    for (Row r : rows) if (r.label == 0) known++;
    report
        .append('"')
        .append(name)
        .append("\":{\"rows\":")
        .append(rows.size())
        .append(",\"known\":")
        .append(known)
        .append(",\"required_refusals\":")
        .append(rows.size() - known);
    for (int mode = 0; mode < 2; mode++) {
      int accepted = 0, correct = 0, refused = 0, lost = 0, changed = 0;
      for (Row r : rows) {
        TinyDecisionModel.Decision before = teacher.classify(r.text, .6, .15),
            after = mode == 0 ? before : model.classify(r.text, .6, .15);
        priorRefusal(before, after);
        if (after.status == TinyDecisionModel.Status.CHOICE) {
          accepted++;
          if (Objects.equals(after.choice, r.expected)) correct++;
        } else if (r.label == 1) refused++;
        if (r.label == 0
            && Objects.equals(before.choice, r.expected)
            && !Objects.equals(after.choice, r.expected)) lost++;
        if (after.status == TinyDecisionModel.Status.CHOICE
            && !Objects.equals(after.choice, before.choice)) changed++;
      }
      report.append(
          String.format(
              Locale.ROOT,
              ",\"%s\":{\"accepted\":%d,\"accepted_correct\":%d,\"accepted_errors\":%d,\"unsupported_refused\":%d,\"previous_correct_lost\":%d,\"choice_changes\":%d}",
              mode == 0 ? "teacher" : "utility",
              accepted,
              correct,
              accepted - correct,
              refused,
              lost,
              changed));
    }
    report.append('}');
  }

  private static void benchmark(
      StringBuilder report,
      List<Row> train,
      RichIntentRefusalModel teacher,
      UtilityExpertDecisionModel model) {
    List<Row> work = new ArrayList<>();
    for (String language : new String[] {"ar", "en", "mixed"}) {
      int n = 0;
      for (Row r : train) if (r.language.equals(language) && n++ < 64) work.add(r);
    }
    for (int mode = 0; mode < 3; mode++)
      for (int i = 0; i < 512; i++)
        consume(timed(mode, work.get(i % work.size()).text, teacher, model));
    long[][] time = new long[3][8];
    for (int round = 0; round < 8; round++)
      for (int position = 0; position < 3; position++) {
        int mode = (round + position) % 3;
        long start = System.nanoTime();
        for (Row r : work) consume(timed(mode, r.text, teacher, model));
        time[mode][round] = (System.nanoTime() - start) / work.size();
      }
    report.append(
        "{\"physical_phone\":false,\"encoding_included\":true,\"calls_per_batch\":192,\"batches\":8");
    String[] names = {"teacher", "utility_shared", "utility_unshared"};
    for (int i = 0; i < 3; i++) {
      report
          .append(",\"")
          .append(names[i])
          .append("\":{\"batch_average_ns\":")
          .append(Arrays.toString(time[i]));
      Arrays.sort(time[i]);
      report
          .append(",\"median_batch_average_ns\":")
          .append((time[i][3] + time[i][4]) / 2)
          .append('}');
    }
    report.append('}');
  }

  private static TinyDecisionModel.Decision timed(
      int mode, String text, RichIntentRefusalModel teacher, UtilityExpertDecisionModel model) {
    return mode == 0
        ? teacher.classify(text, .6, .15)
        : mode == 1 ? model.classify(text, .6, .15) : model.classifyUnshared(text, .6, .15);
  }

  private static void consume(TinyDecisionModel.Decision d) {
    double sum = 0;
    for (double p : d.probabilitiesCopy()) sum += p * p;
    sink = sum;
  }

  private UtilityExpertExperiment() {}
}
