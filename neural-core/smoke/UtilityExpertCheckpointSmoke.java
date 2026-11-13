package com.omnidev.workspace.domain.neural;

import com.omnidev.workspace.domain.neural.PublicRefusalCorpus.Row;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Real v1/v2 checkpoints, metadata, variable five/six-label outputs and shared/unshared numerical
 * parity.
 */
public final class UtilityExpertCheckpointSmoke {
  public static void main(String[] args) throws Exception {
    RichTextEncoder encoder = PublicRefusalExperiment.encoder();
    Class<?> type = Class.forName("TrainedUtilityExpert");
    String source = (String) type.getField("SOURCE").get(null),
        fingerprint = (String) type.getField("VOCABULARY_SHA256").get(null);
    double temperature = type.getField("TEMPERATURE").getDouble(null);
    double[] reject = (double[]) type.getMethod("reject").invoke(null),
        change = (double[]) type.getMethod("change").invoke(null),
        support;
    boolean guarded = true;
    try {
      support = (double[]) type.getMethod("support").invoke(null);
    } catch (NoSuchMethodException initial) {
      guarded = false;
      support = new double[] {1.1, 1.1, 1.1, 1.1, 1.1};
    }
    if (guarded && type.getField("PRODUCTION_ELIGIBLE").getBoolean(null))
      throw new AssertionError("Ineligible utility model was promoted");
    if (!fingerprint.equals(encoder.fingerprint()))
      throw new AssertionError("Utility vocabulary pairing changed");
    TinyNeuralRuntime dense = (TinyNeuralRuntime) type.getMethod("model").invoke(null),
        sparse = dense.withSparseInput();
    UtilityExpertDecisionModel
        a = UtilityExpertExperiment.model(dense, temperature, reject, change, support, false),
        b = UtilityExpertExperiment.model(sparse, temperature, reject, change, support, true);
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
    List<String> expected = Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8);
    List<String> metadata =
        new ArrayList<>(
            Arrays.asList(
                "source\t" + source,
                "vocabulary_sha256\t" + fingerprint,
                "temperature\t" + temperature,
                "reject\t" + Arrays.toString(reject),
                "change\t" + Arrays.toString(change)));
    if (guarded) metadata.add("support\t" + Arrays.toString(support));
    metadata.add("status\tchoice\tprobabilities\texpert_probabilities");
    if (expected.size() != rows.size() + metadata.size()
        || !expected.subList(0, metadata.size()).equals(metadata))
      throw new AssertionError("Utility metadata/count drift");
    RichIntentRefusalModel teacher = ResidualAdapterExperiment.teacher(true);
    TinyDecisionModel expert =
        new TinyDecisionModel(dense, RichTextDecisionModel.labels(), temperature);
    for (int i = 0; i < rows.size(); i++) {
      String text = rows.get(i).text;
      float[] features = encoder.encode(text);
      if (!Arrays.equals(dense.infer(features), sparse.infer(features)))
        throw new AssertionError("Utility dense/sparse logits drift");
      TinyDecisionModel.Decision d = a.classify(text, .6, .15),
          s = b.classify(text, .6, .15),
          unshared = a.classifyUnshared(text, .6, .15);
      exact(d, s);
      exact(d, unshared);
      UtilityExpertExperiment.priorRefusal(teacher.classify(text, .6, .15), d);
      String[] ref = expected.get(i + metadata.size()).split("\t", -1);
      if (ref.length != 4
          || !d.status.name().equals(ref[0])
          || !(d.choice == null ? "-" : d.choice).equals(ref[1]))
        throw new AssertionError("Utility trained/exported decision drift");
      numeric(d.probabilitiesCopy(), ref[2]);
      numeric(expert.decide(features, 0, 0).probabilitiesCopy(), ref[3]);
    }
    System.out.println(
        "Utility "
            + source
            + " metadata/numeric/decision/dense-sparse/shared-unshared integrity passed on "
            + rows.size()
            + " observations.");
  }

  private static void exact(TinyDecisionModel.Decision a, TinyDecisionModel.Decision b) {
    if (a.status != b.status
        || !Objects.equals(a.choice, b.choice)
        || !Arrays.equals(a.probabilitiesCopy(), b.probabilitiesCopy()))
      throw new AssertionError("Utility implementation equivalence changed");
  }

  private static void numeric(double[] values, String row) {
    String[] p = row.split(",", -1);
    if (values.length != p.length) throw new AssertionError("Utility distribution schema drift");
    for (int i = 0; i < p.length; i++) {
      double ref = Double.parseDouble(p[i]);
      if (!Double.isFinite(ref) || Math.abs(ref - values[i]) > 2e-6)
        throw new AssertionError("Utility numeric export drift");
    }
  }
}
