package com.omnidev.workspace.domain.neural;

import com.omnidev.workspace.domain.neural.DistilledAdamTrainer.Example;
import com.omnidev.workspace.domain.neural.PublicRefusalCorpus.Row;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Same frozen representation/optimizer, broader authored supervision, and an equally retuned frozen
 * control.
 */
public final class ContractIntentExperiment {
  private static final class Candidate {
    final TinyNeuralRuntime runtime;
    final DistilledAdamTrainer.Model fitted;
    final String source;
    final double temperature, loss;
    final ExpertPolicyTrainer.Policy policy;

    Candidate(
        TinyNeuralRuntime runtime,
        DistilledAdamTrainer.Model fitted,
        String source,
        double temperature,
        double loss,
        ExpertPolicyTrainer.Policy policy) {
      this.runtime = runtime;
      this.fitted = fitted;
      this.source = source;
      this.temperature = temperature;
      this.loss = loss;
      this.policy = policy;
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1)
      throw new IllegalArgumentException("Expected contract artifact directory");
    List<Row> rows = ResidualAdapterExperiment.rows();
    rows.addAll(ContractIntentCorpus.load("train"));
    rows.addAll(ContractIntentCorpus.load("development"));
    rows.addAll(ContractIntentCorpus.load("calibration"));
    List<Row> train = PublicRefusalCorpus.split(rows, "train"),
        dev = PublicRefusalCorpus.split(rows, "development"),
        cal = PublicRefusalCorpus.split(rows, "calibration");
    disjoint(rows);
    RichTextEncoder encoder = PublicRefusalExperiment.encoder();
    RichIntentRefusalModel teacher = ResidualAdapterExperiment.teacher(true);
    Map<Row, float[]> features = new IdentityHashMap<>();
    Map<Row, TinyDecisionModel.Decision> before = new IdentityHashMap<>();
    for (Row row : rows)
      if (Arrays.asList("train", "development", "calibration").contains(row.split)) {
        features.put(row, encoder.encode(row.text));
        before.put(row, teacher.classify(row.text, .6, .15));
      }
    List<Example> fitting = examples(train, features, before, true),
        validation = examples(dev, features, before, false),
        calibration = examples(cal, features, before, false);
    Map<String, List<Example>> strata = new TreeMap<>();
    for (Example e : fitting)
      strata.computeIfAbsent(e.language + ":" + e.label, k -> new ArrayList<>()).add(e);
    if (strata.size() != 18) throw new IllegalArgumentException("Contract fitting strata missing");
    int maximum = 0;
    for (List<Example> s : strata.values()) maximum = Math.max(maximum, s.size());
    List<Example> balanced = new ArrayList<>();
    for (int i = 0; i < maximum; i++)
      for (List<Example> s : strata.values()) balanced.add(s.get(i % s.size()));
    Candidate selected = null;
    StringBuilder details = new StringBuilder("[");
    for (int width : new int[] {0, 16})
      for (long seed : new long[] {42, 97}) {
        DistilledAdamTrainer.Result fit =
            DistilledAdamTrainer.fit(balanced, validation, width, seed);
        double temperature = calibrate(fit.model.runtime(), calibration);
        Candidate candidate =
            new Candidate(
                fit.model.runtime().withSparseInput(),
                fit.model,
                "new_width" + width + "_seed" + seed,
                temperature,
                fit.loss,
                UtilityExpertExperiment.policy(
                    fit.model.runtime().withSparseInput(),
                    temperature,
                    dev,
                    train,
                    features,
                    before,
                    teacher));
        if (better(candidate, selected)) selected = candidate;
        if (details.length() > 1) details.append(',');
        details
            .append("{\"source\":")
            .append(q(candidate.source))
            .append(",\"parameters\":")
            .append(candidate.runtime.parameterCount())
            .append(",\"epoch\":")
            .append(fit.epoch)
            .append(",\"epochs_run\":")
            .append(fit.epochsRun)
            .append(",\"temperature\":")
            .append(temperature)
            .append(",\"development_macro_accuracy\":")
            .append(fit.accuracy)
            .append(",\"development_nll\":")
            .append(fit.loss)
            .append(",\"policy_utility\":")
            .append(candidate.policy.utility)
            .append('}');
        System.err.println(candidate.source + " utility=" + candidate.policy.utility);
      }
    TinyNeuralRuntime frozen = PrototypeSupportExperiment.network(true);
    double controlTemperature = calibrate(frozen, calibration);
    Candidate control =
        new Candidate(
            frozen,
            null,
            "retuned_frozen_utility_v2",
            controlTemperature,
            nll(frozen, validation, 1),
            UtilityExpertExperiment.policy(
                frozen, controlTemperature, dev, train, features, before, teacher));
    details
        .append(",{\"source\":")
        .append(q(control.source))
        .append(",\"parameters\":")
        .append(frozen.parameterCount())
        .append(",\"temperature\":")
        .append(controlTemperature)
        .append(",\"policy_utility\":")
        .append(control.policy.utility)
        .append("}]");
    if (better(control, selected)) selected = control;
    UtilityExpertDecisionModel protectedModel =
        model(
            selected.runtime,
            selected.temperature,
            selected.policy.reject,
            selected.policy.change,
            selected.policy.support,
            true);
    RichTextDecisionModel raw =
        new RichTextDecisionModel(
            encoder,
            selected.runtime,
            selected.temperature,
            RichTextEncoder.VERSION,
            encoder.fingerprint());
    Path out = Paths.get(args[0]);
    Files.createDirectories(out);
    String factory =
        selected.fitted != null
            ? selected.fitted.javaSource(
                selected.temperature,
                selected.policy.reject,
                selected.policy.change,
                selected.policy.support,
                encoder.fingerprint())
            : new String(
                Files.readAllBytes(
                    Paths.get(
                        "neural-core/checkpoints/utility-expert-v2/TrainedUtilityExpert.java")),
                StandardCharsets.UTF_8);
    factory = bind(factory, selected, encoder.fingerprint());
    Files.write(
        out.resolve("TrainedContractExpert.java"), factory.getBytes(StandardCharsets.UTF_8));
    Files.write(
        out.resolve("TrainedContractPolicy.java"),
        policySource(selected, protectedModel.checkpointFingerprint(), encoder.fingerprint())
            .getBytes(StandardCharsets.UTF_8));
    // No new authored test score is computed until both exported files are fixed.
    List<Row> old = ExpertTrustExperiment.observed();
    old.addAll(ExpertTrustExperiment.fresh());
    List<Row> fresh = ContractIntentCorpus.load("test");
    List<Row> all = new ArrayList<>(old);
    all.addAll(ContractIntentCorpus.load("all"));
    UtilityExpertDecisionModel baseline = PrototypeSupportExperiment.utility(true);
    List<String> expected =
        new ArrayList<>(
            Arrays.asList(
                "runtime_sha256\t" + protectedModel.checkpointFingerprint(),
                "source\t" + selected.source,
                "temperature\t" + selected.temperature,
                "protected_status\tprotected_choice\tprotected_probabilities\traw_status"
                    + "\traw_choice\traw_probabilities"));
    for (Row row : all) {
      TinyDecisionModel.Decision p = protectedModel.classify(row.text, .6, .15),
          r = raw.classify(row.text, .6, .15);
      UtilityExpertExperiment.priorRefusal(teacher.classify(row.text, .6, .15), p);
      expected.add(decision(p) + "\t" + decision(r));
    }
    Files.write(out.resolve("expected.tsv"), expected, StandardCharsets.UTF_8);
    StringBuilder report =
        new StringBuilder(
                "{\"production_eligible\":false,\"data\":\"assistant_authored_source_anchors_not_execution_or_independent_human_labels\",\"old_status\":\"4381_observed_regressions\",\"new_test\":\"36_pre_fit_frozen_authored_scenario_rows_not_semantic_OOD\",\"selected\":")
            .append(q(selected.source))
            .append(",\"expert_parameters\":")
            .append(selected.runtime.parameterCount())
            .append(",\"temperature\":")
            .append(selected.temperature)
            .append(",\"train_rows\":")
            .append(train.size())
            .append(",\"balanced_train_rows\":")
            .append(balanced.size())
            .append(",\"vocabulary_sha256\":")
            .append(q(encoder.fingerprint()))
            .append(",\"runtime_sha256\":")
            .append(q(protectedModel.checkpointFingerprint()))
            .append(",\"reject\":")
            .append(Arrays.toString(selected.policy.reject))
            .append(",\"change\":")
            .append(Arrays.toString(selected.policy.change))
            .append(",\"support\":")
            .append(Arrays.toString(selected.policy.support))
            .append(",\"candidates\":")
            .append(details)
            .append(",\"evaluations\":{");
    evaluate(report, "old_observed", held(old), teacher, baseline, protectedModel, raw);
    for (String lang : new String[] {"ar", "en", "mixed"}) {
      List<Row> subset = new ArrayList<>();
      for (Row row : fresh) if (row.language.equals(lang)) subset.add(row);
      report.append(',');
      evaluate(report, "new_authored_" + lang, subset, teacher, baseline, protectedModel, raw);
    }
    report
        .append("},\"host_timing\":")
        .append(benchmark(train, teacher, baseline, protectedModel))
        .append("}\n");
    Files.write(out.resolve("report.json"), report.toString().getBytes(StandardCharsets.UTF_8));
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
    return UtilityExpertExperiment.model(runtime, temperature, reject, change, support, sparse);
  }

  static List<Example> examples(
      List<Row> rows,
      Map<Row, float[]> features,
      Map<Row, TinyDecisionModel.Decision> before,
      boolean train) {
    List<Example> out = new ArrayList<>();
    List<String> labels = Arrays.asList(RichTextDecisionModel.labels());
    for (Row row : rows) {
      int label = row.label == 1 ? 5 : labels.indexOf(row.expected);
      double[] target = new double[6];
      Arrays.fill(target, .02 / 6);
      target[label] += .98;
      if (train
          && (row.kind.startsWith("old_") || row.kind.equals("contract_authored"))
          && row.label == 0
          && Objects.equals(before.get(row).choice, row.expected)) {
        double[] p = before.get(row).probabilitiesCopy();
        for (int i = 0; i < 6; i++) target[i] = (target[i] + (i < 5 ? p[i] : 0)) / 2;
      }
      out.add(new Example(row.group, row.language, label, features.get(row), target));
    }
    return out;
  }

  private static double calibrate(TinyNeuralRuntime network, List<Example> rows) {
    double chosen = 1, best = Double.POSITIVE_INFINITY;
    for (double t : new double[] {.5, .75, 1, 1.5, 2, 3, 5}) {
      double loss = nll(network, rows, t);
      if (loss < best) {
        best = loss;
        chosen = t;
      }
    }
    return chosen;
  }

  private static double nll(TinyNeuralRuntime network, List<Example> rows, double temperature) {
    TinyDecisionModel model =
        new TinyDecisionModel(network, RichTextDecisionModel.labels(), temperature);
    Map<String, double[]> strata = new TreeMap<>();
    for (Example row : rows) {
      double[] s = strata.computeIfAbsent(row.language + ":" + row.label, k -> new double[2]);
      s[0] -= Math.log(Math.max(1e-30, model.decide(row.x, 0, 0).probabilitiesCopy()[row.label]));
      s[1]++;
    }
    double loss = 0;
    for (double[] s : strata.values()) loss += s[0] / s[1];
    return loss / strata.size();
  }

  private static boolean better(Candidate a, Candidate b) {
    return b == null
        || a.policy.utility > b.policy.utility + 1e-12
        || Math.abs(a.policy.utility - b.policy.utility) <= 1e-12
            && (a.runtime.parameterCount() < b.runtime.parameterCount()
                || a.runtime.parameterCount() == b.runtime.parameterCount() && a.loss < b.loss);
  }

  private static String bind(String source, Candidate selected, String vocabulary) {
    source =
        source
            .replace("TrainedUtilityExpert", "TrainedContractExpert")
            .replaceFirst(
                "TEMPERATURE\\s*=\\s*[^;]+",
                Matcher.quoteReplacement("TEMPERATURE=" + selected.temperature));
    for (String method : new String[] {"reject", "change", "support"}) {
      double[] values =
          method.equals("reject")
              ? selected.policy.reject
              : method.equals("change") ? selected.policy.change : selected.policy.support;
      String pattern =
          "public static double\\[\\] "
              + method
              + "\\(\\)\\{return new double\\[\\]\\{[^}]*\\};\\}";
      String replacement =
          "public static double[] " + method + "(){return new double[]" + array(values) + ";}";
      Matcher m = Pattern.compile(pattern).matcher(source);
      if (!m.find()) throw new IllegalArgumentException("Contract factory policy export shape");
      source = m.replaceFirst(Matcher.quoteReplacement(replacement));
    }
    return source;
  }

  private static String policySource(Candidate c, String runtime, String vocabulary) {
    return "public final class TrainedContractPolicy{\n"
               + "public static final boolean PRODUCTION_ELIGIBLE=false;\n"
               + "public static final String SOURCE="
        + q(c.source)
        + ";\npublic static final String VOCABULARY_SHA256="
        + q(vocabulary)
        + ";\npublic static final String RUNTIME_SHA256="
        + q(runtime)
        + ";\npublic static final double TEMPERATURE="
        + c.temperature
        + ";\npublic static double[] reject(){return new double[]"
        + array(c.policy.reject)
        + ";}\npublic static double[] change(){return new double[]"
        + array(c.policy.change)
        + ";}\npublic static double[] support(){return new double[]"
        + array(c.policy.support)
        + ";}\n}\n";
  }

  private static List<Row> held(List<Row> rows) {
    List<Row> out = new ArrayList<>();
    for (Row row : rows)
      if (row.split.equals("test") || row.split.equals("regression")) out.add(row);
    return out;
  }

  private static void disjoint(List<Row> rows) {
    Map<String, String> groups = new HashMap<>();
    for (Row row : rows) {
      String previous = groups.putIfAbsent(row.group, row.split);
      if (previous != null && !previous.equals(row.split))
        throw new IllegalArgumentException("Contract training group leakage");
    }
  }

  private static void evaluate(
      StringBuilder out,
      String name,
      List<Row> rows,
      RichIntentRefusalModel teacher,
      UtilityExpertDecisionModel baseline,
      UtilityExpertDecisionModel selected,
      RichTextDecisionModel raw) {
    int known = 0;
    for (Row row : rows) if (row.label == 0) known++;
    out.append(q(name))
        .append(":{\"rows\":")
        .append(rows.size())
        .append(",\"known\":")
        .append(known)
        .append(",\"required_refusals\":")
        .append(rows.size() - known);
    for (int mode = 0; mode < 4; mode++) {
      int accepted = 0, right = 0, refused = 0, lost = 0, upgrades = 0;
      List<String> losses = new ArrayList<>();
      for (Row row : rows) {
        TinyDecisionModel.Decision before = teacher.classify(row.text, .6, .15),
            after =
                mode == 0
                    ? before
                    : mode == 1
                        ? baseline.classify(row.text, .6, .15)
                        : mode == 2
                            ? selected.classify(row.text, .6, .15)
                            : raw.classify(row.text, .6, .15);
        if (mode != 3) UtilityExpertExperiment.priorRefusal(before, after);
        if (after.status == TinyDecisionModel.Status.CHOICE) {
          accepted++;
          if (Objects.equals(row.expected, after.choice)) right++;
          if (before.status == TinyDecisionModel.Status.ABSTAIN) upgrades++;
        } else if (row.label == 1) refused++;
        if (row.label == 0
            && Objects.equals(before.choice, row.expected)
            && !Objects.equals(after.choice, row.expected)) {
          lost++;
          if (losses.size() < 64)
            losses.add("{\"group\":" + q(row.group) + ",\"language\":" + q(row.language) + "}");
        }
      }
      out.append(',')
          .append(q(new String[] {"teacher", "utility_v2", "protected", "raw_diagnostic"}[mode]))
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
          .append(",\"teacher_abstentions_upgraded\":")
          .append(upgrades)
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
      List<Row> rows,
      RichIntentRefusalModel teacher,
      UtilityExpertDecisionModel baseline,
      UtilityExpertDecisionModel selected) {
    List<String> texts = new ArrayList<>();
    Map<String, Integer> counts = new TreeMap<>();
    for (Row row : rows)
      if (counts.getOrDefault(row.expected, 0) < 24) {
        texts.add(row.text);
        counts.merge(row.expected, 1, Integer::sum);
      }
    if (texts.size() != 144) throw new AssertionError("Contract timing strata");
    for (int m = 0; m < 3; m++)
      for (int i = 0; i < 512; i++)
        consume(infer(m, texts.get(i % 144), teacher, baseline, selected));
    double[][] samples = new double[3][8];
    for (int r = 0; r < 8; r++)
      for (int p = 0; p < 3; p++) {
        int m = (r + p) % 3;
        long start = System.nanoTime();
        for (String text : texts) consume(infer(m, text, teacher, baseline, selected));
        samples[m][r] = (System.nanoTime() - start) / 144.0;
      }
    StringBuilder out =
        new StringBuilder(
                "{\"phone_measurement\":false,\"encoding_included\":true,\"calls_per_batch\":144,\"warmup_per_mode\":512,\"batches\":8,\"checksum\":")
            .append(sink);
    for (int m = 0; m < 3; m++) {
      double[] sorted = samples[m].clone();
      Arrays.sort(sorted);
      out.append(',')
          .append(q(new String[] {"teacher", "utility_v2", "protected"}[m]))
          .append(":{\"batch_average_ns\":")
          .append(Arrays.toString(samples[m]))
          .append(",\"median_batch_average_ns\":")
          .append((sorted[3] + sorted[4]) / 2)
          .append('}');
    }
    return out.append('}').toString();
  }

  private static TinyDecisionModel.Decision infer(
      int mode,
      String text,
      RichIntentRefusalModel teacher,
      UtilityExpertDecisionModel base,
      UtilityExpertDecisionModel selected) {
    return mode == 0
        ? teacher.classify(text, .6, .15)
        : (mode == 1 ? base : selected).classify(text, .6, .15);
  }

  private static void consume(TinyDecisionModel.Decision d) {
    double sum = 0;
    for (double p : d.probabilitiesCopy()) sum += p * p;
    sink = sum;
  }

  private static String decision(TinyDecisionModel.Decision d) {
    return d.status
        + "\t"
        + (d.choice == null ? "-" : d.choice)
        + "\t"
        + join(d.probabilitiesCopy());
  }

  private static String join(double[] x) {
    StringBuilder out = new StringBuilder();
    for (double v : x) {
      if (out.length() > 0) out.append(',');
      out.append(v);
    }
    return out.toString();
  }

  private static String array(double[] x) {
    return Arrays.toString(x).replace('[', '{').replace(']', '}');
  }

  private static String q(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
  }

  private ContractIntentExperiment() {}
}
