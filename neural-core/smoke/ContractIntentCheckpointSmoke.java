package com.omnidev.workspace.domain.neural;

import com.omnidev.workspace.domain.neural.PublicRefusalCorpus.Row;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Real exported/committed network-policy pairing, protected decisions, diagnostic separation and
 * bounds.
 */
public final class ContractIntentCheckpointSmoke {
  static final class Loaded {
    final TinyNeuralRuntime network;
    final double temperature;
    final String source, fingerprint;
    final double[] reject, change, support;

    Loaded() throws Exception {
      Class<?> expert = Class.forName("TrainedContractExpert"),
          policy = Class.forName("TrainedContractPolicy");
      network = (TinyNeuralRuntime) expert.getMethod("model").invoke(null);
      temperature = policy.getField("TEMPERATURE").getDouble(null);
      source = (String) policy.getField("SOURCE").get(null);
      fingerprint = (String) policy.getField("RUNTIME_SHA256").get(null);
      reject = (double[]) policy.getMethod("reject").invoke(null);
      change = (double[]) policy.getMethod("change").invoke(null);
      support = (double[]) policy.getMethod("support").invoke(null);
      if (policy.getField("PRODUCTION_ELIGIBLE").getBoolean(null)
          || !PublicRefusalExperiment.encoder()
              .fingerprint()
              .equals(policy.getField("VOCABULARY_SHA256").get(null))
          || network.parameterCount() > 20000)
        throw new AssertionError("Foreign/promoted contract checkpoint");
      if (expert.getField("TEMPERATURE").getDouble(null) != temperature)
        throw new AssertionError("Factory/policy temperature drift");
      for (String name : new String[] {"reject", "change", "support"})
        if (!Arrays.equals(
            (double[]) expert.getMethod(name).invoke(null),
            (double[]) policy.getMethod(name).invoke(null)))
          throw new AssertionError("Factory/policy metadata drift");
    }

    UtilityExpertDecisionModel model(boolean sparse) throws Exception {
      return ContractIntentExperiment.model(network, temperature, reject, change, support, sparse);
    }

    RichTextDecisionModel raw(boolean sparse) throws Exception {
      return new RichTextDecisionModel(
          PublicRefusalExperiment.encoder(),
          sparse ? network.withSparseInput() : network,
          temperature,
          RichTextEncoder.VERSION,
          PublicRefusalExperiment.encoder().fingerprint());
    }
  }

  public static void main(String[] args) throws Exception {
    Loaded loaded = new Loaded();
    UtilityExpertDecisionModel dense = loaded.model(false), sparse = loaded.model(true);
    if (!dense.checkpointFingerprint().equals(loaded.fingerprint)
        || !sparse.checkpointFingerprint().equals(loaded.fingerprint))
      throw new AssertionError("Contract frozen runtime pairing drift");
    RichTextDecisionModel raw = loaded.raw(false), rawSparse = loaded.raw(true);
    RichIntentRefusalModel teacher = ResidualAdapterExperiment.teacher(true);
    List<Row> rows = ExpertTrustExperiment.observed();
    rows.addAll(ExpertTrustExperiment.fresh());
    rows.addAll(ContractIntentCorpus.load("all"));
    List<String> expected = Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8);
    List<String> meta =
        Arrays.asList(
            "runtime_sha256\t" + loaded.fingerprint,
            "source\t" + loaded.source,
            "temperature\t" + loaded.temperature,
            "protected_status\tprotected_choice\tprotected_probabilities\traw_status\traw_choice"
                + "\traw_probabilities");
    if (rows.size() != 4597 || expected.size() != 4601 || !expected.subList(0, 4).equals(meta))
      throw new AssertionError("Contract metadata/count drift");
    for (int i = 0; i < rows.size(); i++) {
      String text = rows.get(i).text;
      TinyDecisionModel.Decision p = dense.classify(text, .6, .15), r = raw.classify(text, .6, .15);
      exact(p, sparse.classify(text, .6, .15));
      exact(p, dense.classifyUnshared(text, .6, .15));
      exact(r, rawSparse.classify(text, .6, .15));
      UtilityExpertExperiment.priorRefusal(teacher.classify(text, .6, .15), p);
      String[] c = expected.get(i + 4).split("\t", -1);
      if (c.length != 6) throw new AssertionError("Contract expected schema changed");
      verify(p, c, 0);
      verify(r, c, 3);
      TinyDecisionModel.Decision strict = dense.classify(text, .95, .9);
      if (strict.status == TinyDecisionModel.Status.CHOICE
          && (strict.probability < .95 || strict.margin <= .9))
        throw new AssertionError("Contract bypassed caller confidence");
    }
    // Actual policy contracts on precisely the partitions used by fitting/selection.
    List<Row> constrained = ResidualAdapterExperiment.rows();
    constrained.addAll(ContractIntentCorpus.load("train"));
    constrained.addAll(ContractIntentCorpus.load("development"));
    for (Row row : constrained)
      if ((row.split.equals("train") || row.split.equals("development"))
          && row.label == 0
          && Objects.equals(teacher.classify(row.text, .6, .15).choice, row.expected)
          && !Objects.equals(dense.classify(row.text, .6, .15).choice, row.expected))
        throw new AssertionError("Contract lost constrained teacher choice");
    System.out.println(
        "Contract exported/committed"
            + " runtime/metadata/numeric/decision/dense-sparse/shared-unshared/caller/retention"
            + " integrity passed on4597 observations.");
  }

  private static void verify(TinyDecisionModel.Decision d, String[] c, int start) {
    if (!d.status.name().equals(c[start])
        || !Objects.equals(d.choice == null ? "-" : d.choice, c[start + 1]))
      throw new AssertionError("Contract typed decision drift");
    String[] v = c[start + 2].split(",", -1);
    double[] p = d.probabilitiesCopy();
    if (v.length != p.length || p.length != 5 && p.length != 6)
      throw new AssertionError("Contract probability shape");
    for (int i = 0; i < p.length; i++) {
      double x = Double.parseDouble(v[i]);
      if (!Double.isFinite(x) || x < 0 || x > 1 || Math.abs(x - p[i]) > 2e-6)
        throw new AssertionError("Contract probability drift");
    }
  }

  private static void exact(TinyDecisionModel.Decision a, TinyDecisionModel.Decision b) {
    if (a.status != b.status
        || !Objects.equals(a.choice, b.choice)
        || !Arrays.equals(a.probabilitiesCopy(), b.probabilitiesCopy()))
      throw new AssertionError("Contract exact identity drift");
  }
}
