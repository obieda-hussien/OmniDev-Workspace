package com.omnidev.workspace.domain.neural;

import com.omnidev.workspace.domain.neural.PublicRefusalCorpus.Row;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Actual compiled/committed selector, frozen-base identity and shared review parity. */
public final class ExpertTrustCheckpointSmoke {
  public static void main(String[] args) throws Exception {
    Class<?> cp = Class.forName("TrainedExpertTrust");
    TinyNeuralRuntime network = (TinyNeuralRuntime) cp.getMethod("model").invoke(null);
    double temperature = cp.getField("TEMPERATURE").getDouble(null);
    double[] cutoffs = (double[]) cp.getMethod("cutoffs").invoke(null);
    String fingerprint = (String) cp.getField("BASE_SHA256").get(null),
        source = (String) cp.getField("SOURCE").get(null),
        version = (String) cp.getField("FEATURE_VERSION").get(null);
    if (cp.getField("PRODUCTION_ELIGIBLE").getBoolean(null))
      throw new AssertionError("Unverified promotion");
    UtilityExpertDecisionModel dense = PrototypeSupportExperiment.utility(false),
        sparse = PrototypeSupportExperiment.utility(true);
    if (!dense.checkpointFingerprint().equals(sparse.checkpointFingerprint()))
      throw new AssertionError("Sparse checkpoint identity drift");
    ExpertTrustDecisionModel
        a =
            new ExpertTrustDecisionModel(
                dense, network, temperature, cutoffs, version, fingerprint),
        b =
            new ExpertTrustDecisionModel(
                sparse, network.withSparseInput(), temperature, cutoffs, version, fingerprint);
    TinyDecisionModel risk =
        new TinyDecisionModel(network, new String[] {"keep", "use"}, temperature);
    List<Row> rows = ExpertTrustExperiment.observed();
    rows.addAll(ExpertTrustExperiment.fresh());
    List<String> expected = Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8);
    List<String> meta =
        Arrays.asList(
            "base_sha256\t" + fingerprint,
            "source\t" + source,
            "temperature\t" + temperature,
            "cutoffs\t" + Arrays.toString(cutoffs),
            "status\tchoice\tprobabilities\ttrust_score\tfeatures");
    if (rows.size() != 4381
        || expected.size() != rows.size() + 5
        || !expected.subList(0, 5).equals(meta))
      throw new AssertionError("Trust metadata/count drift");
    for (int i = 0; i < rows.size(); i++) {
      String text = rows.get(i).text;
      UtilityExpertDecisionModel.Review review = dense.review(text, .6, .15);
      TinyDecisionModel.Decision d = a.classify(text, .6, .15);
      exact(d, b.classify(text, .6, .15));
      exact(review.proposed, dense.classify(text, .6, .15));
      UtilityExpertExperiment.priorRefusal(review.before, d);
      if (!equal(d, review.before) && !equal(d, review.proposed))
        throw new AssertionError("Selector invented a decision");
      String[] c = expected.get(i + 5).split("\t", -1);
      if (c.length != 5
          || !c[0].equals(d.status.name())
          || !c[1].equals(d.choice == null ? "-" : d.choice))
        throw new AssertionError("Trust typed decision drift");
      numeric(d.probabilitiesCopy(), c[2]);
      double score = review.features == null ? -1 : ExpertTrustExperiment.score(risk, review);
      if (!Double.isFinite(Double.parseDouble(c[3]))
          || Math.abs(Double.parseDouble(c[3]) - score) > 2e-6)
        throw new AssertionError("Trust score drift");
      if (review.features == null) {
        if (!c[4].equals("-")) throw new AssertionError("Foreign trust features");
      } else {
        double[] x = new double[20];
        for (int j = 0; j < 20; j++) x[j] = review.features[j];
        numeric(x, c[4]);
      }
    }
    System.out.println(
        "Trust compiled/committed base/feature/numeric/decision/dense-sparse/review integrity"
            + " passed on4381 observations.");
  }

  private static void numeric(double[] actual, String encoded) {
    String[] values = encoded.split(",", -1);
    if (values.length != actual.length) throw new AssertionError("Trust shape drift");
    for (int i = 0; i < values.length; i++) {
      double v = Double.parseDouble(values[i]);
      if (!Double.isFinite(v) || Math.abs(actual[i] - v) > 2e-6)
        throw new AssertionError("Trust numeric drift");
    }
  }

  private static boolean equal(TinyDecisionModel.Decision a, TinyDecisionModel.Decision b) {
    return a.status == b.status
        && Objects.equals(a.choice, b.choice)
        && Arrays.equals(a.probabilitiesCopy(), b.probabilitiesCopy());
  }

  private static void exact(TinyDecisionModel.Decision a, TinyDecisionModel.Decision b) {
    if (!equal(a, b)) throw new AssertionError("Trust decision identity drift");
  }
}
