package com.omnidev.workspace.domain.neural;

import com.omnidev.workspace.domain.neural.PublicRefusalCorpus.Row;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * One bounded support fit, empirical calibration, development route selection, then held-out
 * scoring.
 */
public final class PrototypeSupportExperiment {
  private static final class Observation {
    final Row row;
    final TinyDecisionModel.Decision before, utility, guarded;

    Observation(
        Row row,
        TinyDecisionModel.Decision before,
        TinyDecisionModel.Decision utility,
        TinyDecisionModel.Decision guarded) {
      this.row = row;
      this.before = before;
      this.utility = utility;
      this.guarded = guarded;
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1)
      throw new IllegalArgumentException("Expected prototype artifact directory");
    List<Row> rows = ResidualAdapterExperiment.rows();
    List<Row> train = PublicRefusalCorpus.split(rows, "train"),
        cal = PublicRefusalCorpus.split(rows, "calibration"),
        dev = PublicRefusalCorpus.split(rows, "development");
    requireDisjoint(rows);
    RichTextEncoder encoder = PublicRefusalExperiment.encoder();
    RichIntentRefusalModel teacher = ResidualAdapterExperiment.teacher(true);
    PrototypeSupportTrainer.Fit fit = PrototypeSupportTrainer.fit(train, encoder, teacher);
    PrototypeSupportTrainer.Calibration calibration =
        PrototypeSupportTrainer.calibrate(cal, fit.bank, encoder, teacher);
    UtilityExpertDecisionModel utility = utility(true);
    List<Observation> observations =
        observe(dev, encoder, teacher, utility, fit.bank, calibration.cutoffs);
    Map<String, Integer> totals = new TreeMap<>();
    for (Observation o : observations)
      totals.merge(o.row.language + ":" + o.row.label, 1, Integer::sum);
    double[] beforeScore = new double[5], guardScore = new double[5];
    boolean[] enabled = new boolean[5];
    for (Observation o : observations)
      if (o.before.status == TinyDecisionModel.Status.CHOICE) {
        int d = UtilityExpertDecisionModel.domain(o.before.choice);
        double weight = 1.0 / totals.get(o.row.language + ":" + o.row.label);
        if (correct(o.row, o.before)) beforeScore[d] += weight;
        if (correct(o.row, o.guarded)) guardScore[d] += weight;
        if (o.row.label == 0
            && Objects.equals(o.before.choice, o.row.expected)
            && !correct(o.row, o.guarded))
          throw new AssertionError("Prototype policy lost correct development case");
      }
    for (Observation o : observe(train, encoder, teacher, utility, fit.bank, calibration.cutoffs))
      if (o.row.label == 0
          && Objects.equals(o.before.choice, o.row.expected)
          && !correct(o.row, o.guarded))
        throw new AssertionError("Prototype policy lost correct training case");
    for (int d = 0; d < 5; d++)
      enabled[d] = calibration.counts[d] > 0 && guardScore[d] > beforeScore[d] + 1e-12;
    double[] reject = field("reject"), change = field("change");
    for (int d = 0; d < 5; d++)
      if (!enabled[d]) {
        reject[d] = 1.1;
        change[d] = 1.1;
      }
    Path output = Paths.get(args[0]);
    Files.createDirectories(output);
    Files.write(
        output.resolve("TrainedDomainPrototypes.java"),
        fit.javaSource().getBytes(StandardCharsets.UTF_8));
    Files.write(
        output.resolve("TrainedPrototypePolicy.java"),
        policySource(reject, change, calibration.cutoffs, fit.bank)
            .getBytes(StandardCharsets.UTF_8));
    // Prototype, floor and enabled-domain selection are now fixed. Only now read/evaluate authored
    // challenges.
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
    List<Row> authored = authored();
    rows.addAll(authored);
    UtilityExpertDecisionModel guarded = model(fit.bank, calibration.cutoffs, reject, change, true);
    List<String> expected =
        new ArrayList<>(
            Arrays.asList(
                "bank_sha256\t" + fit.bank.fingerprint(),
                "reject\t" + Arrays.toString(reject),
                "change\t" + Arrays.toString(change),
                "cutoffs\t" + Arrays.toString(calibration.cutoffs),
                "status\tchoice\tprobabilities\tsupport"));
    for (Row r : rows) {
      TinyDecisionModel.Decision before = teacher.classify(r.text, .6, .15),
          d = guarded.classify(r.text, .6, .15);
      UtilityExpertExperiment.priorRefusal(before, d);
      float[] x = encoder.encode(r.text);
      StringBuilder line =
          new StringBuilder(
              d.status
                  + "\t"
                  + (d.choice == null ? "-" : d.choice)
                  + "\t"
                  + join(d.probabilitiesCopy())
                  + "\t");
      for (int i = 0; i < 5; i++) {
        if (i > 0) line.append(',');
        line.append(fit.bank.similarity(x, i));
      }
      expected.add(line.toString());
    }
    Files.write(output.resolve("expected.tsv"), expected, StandardCharsets.UTF_8);
    StringBuilder report =
        new StringBuilder(
                "{\"production_eligible\":false,\"prior_sets\":\"observed_regressions\",\"authored_suite\":\"new_assistant_authored_challenge_not_independent_traffic\",\"prototype_floats\":15360,\"prototype_count\":15,\"training_rows_by_stratum\":")
            .append(Arrays.toString(fit.rowCounts))
            .append(",\"training_groups_by_stratum\":")
            .append(Arrays.toString(fit.groupCounts))
            .append(",\"calibration_correct_counts\":")
            .append(Arrays.toString(calibration.counts))
            .append(",\"cutoffs\":")
            .append(Arrays.toString(calibration.cutoffs))
            .append(",\"enabled\":")
            .append(Arrays.toString(enabled))
            .append(",\"development_teacher_score\":")
            .append(Arrays.toString(beforeScore))
            .append(",\"development_guarded_score\":")
            .append(Arrays.toString(guardScore))
            .append(",\"evaluations\":{");
    List<Row> oldHeld = new ArrayList<>();
    for (Row r : rows)
      if (Arrays.asList("test", "regression").contains(r.split) && !r.kind.startsWith("authored_"))
        oldHeld.add(r);
    evaluate(report, "old_observed", oldHeld, teacher, utility, guarded);
    for (String kind : new String[] {"old_original", "old_composed_same", "old_composed_cross"}) {
      List<Row> subset = new ArrayList<>();
      for (Row r : oldHeld) if (r.kind.equals(kind)) subset.add(r);
      report.append(',');
      evaluate(report, kind, subset, teacher, utility, guarded);
    }
    for (String language : new String[] {"ar", "en", "mixed"}) {
      List<Row> subset = new ArrayList<>();
      for (Row r : authored) if (r.language.equals(language)) subset.add(r);
      report.append(',');
      evaluate(report, "authored_" + language, subset, teacher, utility, guarded);
    }
    report
        .append("},\"host_timing\":")
        .append(benchmark(train, teacher, utility, guarded))
        .append("}\n");
    Files.write(output.resolve("report.json"), report.toString().getBytes(StandardCharsets.UTF_8));
    System.out.print(report);
  }

  private static volatile double timingSink;

  private static String benchmark(
      List<Row> train,
      RichIntentRefusalModel teacher,
      UtilityExpertDecisionModel utility,
      UtilityExpertDecisionModel guarded) {
    Map<String, List<String>> buckets = new TreeMap<>();
    for (Row row : train)
      buckets.computeIfAbsent(row.expected, k -> new ArrayList<>()).add(row.text);
    List<String> texts = new ArrayList<>();
    for (List<String> bucket : buckets.values()) {
      if (bucket.size() < 24) throw new AssertionError("Timing stratum too small");
      texts.addAll(bucket.subList(0, 24));
    }
    if (texts.size() != 144) throw new AssertionError("Timing probe count changed");
    for (int mode = 0; mode < 3; mode++)
      for (int i = 0; i < 512; i++)
        consume(timedClassify(mode, texts.get(i % texts.size()), teacher, utility, guarded));
    double[][] samples = new double[3][8];
    for (int round = 0; round < 8; round++)
      for (int position = 0; position < 3; position++) {
        int mode = (round + position) % 3;
        long start = System.nanoTime();
        for (String text : texts) consume(timedClassify(mode, text, teacher, utility, guarded));
        samples[mode][round] = (System.nanoTime() - start) / (double) texts.size();
      }
    StringBuilder out =
        new StringBuilder(
                "{\"scope\":\"host_JVM_descriptive_not_phone_or_speed_guarantee\",\"calls_per_batch\":144,\"warmup_per_mode\":512,\"batches\":8,\"encoding_included\":true,\"construction_included\":false,\"checksum\":")
            .append(timingSink);
    for (int mode = 0; mode < 3; mode++) {
      double[] sorted = samples[mode].clone();
      Arrays.sort(sorted);
      out.append(",\"")
          .append(new String[] {"teacher", "utility_v2", "prototype"}[mode])
          .append("\":{\"batch_average_ns\":")
          .append(Arrays.toString(samples[mode]))
          .append(",\"median_batch_average_ns\":")
          .append((sorted[3] + sorted[4]) / 2)
          .append('}');
    }
    return out.append('}').toString();
  }

  private static TinyDecisionModel.Decision timedClassify(
      int mode,
      String text,
      RichIntentRefusalModel teacher,
      UtilityExpertDecisionModel utility,
      UtilityExpertDecisionModel guarded) {
    return mode == 0
        ? teacher.classify(text, .6, .15)
        : (mode == 1 ? utility : guarded).classify(text, .6, .15);
  }

  private static void consume(TinyDecisionModel.Decision decision) {
    double total = 0;
    for (double p : decision.probabilitiesCopy()) total += p * p;
    timingSink = total;
  }

  static UtilityExpertDecisionModel utility(boolean sparse) throws Exception {
    return UtilityExpertExperiment.model(
        network(sparse), temperature(), field("reject"), field("change"), field("support"), sparse);
  }

  static UtilityExpertDecisionModel model(
      DomainPrototypeSupport bank,
      double[] floors,
      double[] reject,
      double[] change,
      boolean sparse)
      throws Exception {
    return new UtilityExpertDecisionModel(
        ResidualAdapterExperiment.teacher(sparse),
        PublicRefusalExperiment.encoder(),
        network(sparse),
        temperature(),
        reject,
        change,
        field("support"),
        PublicRefusalExperiment.encoder().fingerprint(),
        bank,
        floors,
        bank.fingerprint());
  }

  static TinyNeuralRuntime network(boolean sparse) throws Exception {
    TinyNeuralRuntime n =
        (TinyNeuralRuntime) Class.forName("TrainedUtilityExpert").getMethod("model").invoke(null);
    return sparse ? n.withSparseInput() : n;
  }

  static double temperature() throws Exception {
    return Class.forName("TrainedUtilityExpert").getField("TEMPERATURE").getDouble(null);
  }

  static double[] field(String method) throws Exception {
    return (double[]) Class.forName("TrainedUtilityExpert").getMethod(method).invoke(null);
  }

  private static List<Observation> observe(
      List<Row> rows,
      RichTextEncoder encoder,
      RichIntentRefusalModel teacher,
      UtilityExpertDecisionModel utility,
      DomainPrototypeSupport bank,
      double[] floors) {
    List<Observation> result = new ArrayList<>();
    for (Row r : rows) {
      TinyDecisionModel.Decision before = teacher.classify(r.text, .6, .15),
          candidate = utility.classify(r.text, .6, .15),
          after = before;
      if (before.status == TinyDecisionModel.Status.CHOICE) {
        int d = UtilityExpertDecisionModel.domain(before.choice);
        after =
            UtilityExpertDecisionModel.prototypeGuard(
                before, candidate, bank.similarity(encoder.encode(r.text), d), floors[d]);
      }
      result.add(new Observation(r, before, candidate, after));
    }
    return result;
  }

  static List<Row> authored() throws Exception {
    Path path = Paths.get("neural-core/fixtures/prototype-support/authored.tsv");
    if (Files.size(path) > 100_000) throw new IllegalArgumentException("Authored challenge bounds");
    byte[] bytes = Files.readAllBytes(path);
    StringBuilder hash = new StringBuilder();
    for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
      hash.append(String.format(Locale.ROOT, "%02x", b & 255));
    if (!hash.toString().equals("631dd708c7bd59dd6bca98b9204a9cdf1b8953d39280bb28861fa3cbc49ff102"))
      throw new IllegalArgumentException("Authored challenge hash changed");
    List<String> lines = Arrays.asList(new String(bytes, StandardCharsets.UTF_8).split("\n"));
    if (!lines.get(0).equals("id\tgroup\tlanguage\texpected_domain\tkind\ttext"))
      throw new IllegalArgumentException("Authored challenge schema");
    List<Row> rows = new ArrayList<>();
    for (String line : lines.subList(1, lines.size())) {
      String[] c = line.split("\t", -1);
      if (c.length != 6) throw new IllegalArgumentException("Authored row schema");
      rows.add(new Row("test", c[1], c[2], c[3], "authored_" + c[4], c[5]));
    }
    return rows;
  }

  private static String policySource(
      double[] reject, double[] change, double[] cutoffs, DomainPrototypeSupport bank)
      throws Exception {
    return "public final class TrainedPrototypePolicy {\n"
        + "public static final boolean PRODUCTION_ELIGIBLE=false;\n"
        + "public static final String BANK_SHA256=\""
        + bank.fingerprint()
        + "\";\npublic static final String VOCABULARY_SHA256=\""
        + bank.vocabularyFingerprint()
        + "\";\npublic static double[] reject(){return new double[]"
        + array(reject)
        + ";}\npublic static double[] change(){return new double[]"
        + array(change)
        + ";}\npublic static double[] cutoffs(){return new double[]"
        + array(cutoffs)
        + ";}\n}\n";
  }

  private static String array(double[] a) {
    return Arrays.toString(a).replace('[', '{').replace(']', '}');
  }

  private static String join(double[] a) {
    StringBuilder s = new StringBuilder();
    for (int i = 0; i < a.length; i++) {
      if (i > 0) s.append(',');
      s.append(a[i]);
    }
    return s.toString();
  }

  private static boolean correct(Row r, TinyDecisionModel.Decision d) {
    return r.label == 1
        ? d.status == TinyDecisionModel.Status.ABSTAIN
        : Objects.equals(r.expected, d.choice);
  }

  private static void requireDisjoint(List<Row> rows) {
    Map<String, String> groups = new HashMap<>();
    for (Row r : rows) {
      String old = groups.putIfAbsent(r.group, r.split);
      if (old != null && !old.equals(r.split))
        throw new IllegalArgumentException("Prototype source group leakage");
    }
  }

  private static void evaluate(
      StringBuilder report,
      String name,
      List<Row> rows,
      RichIntentRefusalModel teacher,
      UtilityExpertDecisionModel utility,
      UtilityExpertDecisionModel guarded) {
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
    for (int mode = 0; mode < 3; mode++) {
      int accepted = 0, correct = 0, refused = 0, lost = 0;
      List<String> lostCases = new ArrayList<>();
      for (Row r : rows) {
        TinyDecisionModel.Decision before = teacher.classify(r.text, .6, .15),
            after =
                mode == 0
                    ? before
                    : mode == 1
                        ? utility.classify(r.text, .6, .15)
                        : guarded.classify(r.text, .6, .15);
        UtilityExpertExperiment.priorRefusal(before, after);
        if (after.status == TinyDecisionModel.Status.CHOICE) {
          accepted++;
          if (Objects.equals(after.choice, r.expected)) correct++;
        } else if (r.label == 1) refused++;
        if (r.label == 0
            && Objects.equals(before.choice, r.expected)
            && !Objects.equals(after.choice, r.expected)) {
          lost++;
          if (lostCases.size() < 64)
            lostCases.add(
                "{\"group\":"
                    + quoted(r.group)
                    + ",\"language\":"
                    + quoted(r.language)
                    + ",\"expected\":"
                    + quoted(r.expected)
                    + ",\"actual\":"
                    + quoted(after.choice == null ? "-" : after.choice)
                    + "}");
        }
      }
      report.append(
          String.format(
              Locale.ROOT,
              ",\"%s\":{\"accepted\":%d,\"accepted_correct\":%d,\"accepted_errors\":%d,\"unsupported_refused\":%d,\"previous_correct_lost\":%d}",
              mode == 0 ? "teacher" : mode == 1 ? "utility_v2" : "prototype",
              accepted,
              correct,
              accepted - correct,
              refused,
              lost));
      report.setLength(report.length() - 1);
      report
          .append(",\"lost_cases\":[")
          .append(String.join(",", lostCases))
          .append("],\"lost_cases_truncated\":")
          .append(lost > 64)
          .append('}');
    }
    report.append('}');
  }

  private static String quoted(String text) {
    return "\""
        + text.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        + "\"";
  }

  private PrototypeSupportExperiment() {}
}
