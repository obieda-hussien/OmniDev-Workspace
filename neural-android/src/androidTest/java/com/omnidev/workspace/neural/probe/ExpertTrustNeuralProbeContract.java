package com.omnidev.workspace.neural.probe;

import com.omnidev.workspace.domain.neural.*;
import java.io.*;
import java.util.*;

/**
 * Packaged selector/base fingerprint pairing and whole-text ART parity; not a quality promotion.
 */
public final class ExpertTrustNeuralProbeContract {
  public static final int EXPECTED_PROBES = 1150;

  public static ExpertTrustDecisionModel model(boolean sparse) {
    TinyNeuralRuntime n = TrainedExpertTrust.model();
    return new ExpertTrustDecisionModel(
        UtilityNeuralProbeContract.model(sparse),
        sparse ? n.withSparseInput() : n,
        TrainedExpertTrust.TEMPERATURE,
        TrainedExpertTrust.cutoffs(),
        TrainedExpertTrust.FEATURE_VERSION,
        TrainedExpertTrust.BASE_SHA256);
  }

  public static int verify(Reader source) throws Exception {
    require(!TrainedExpertTrust.PRODUCTION_ELIGIBLE, "Unverified trust promotion");
    BufferedReader r = new BufferedReader(source);
    String[] meta = {
      "base_sha256\t" + TrainedExpertTrust.BASE_SHA256,
      "feature_version\t" + TrainedExpertTrust.FEATURE_VERSION,
      "temperature\t" + TrainedExpertTrust.TEMPERATURE,
      "cutoffs\t" + Arrays.toString(TrainedExpertTrust.cutoffs()),
      "source\t" + TrainedExpertTrust.SOURCE
    };
    for (String value : meta) require(value.equals(r.readLine()), "Trust metadata pairing changed");
    require(
        "id\ttext\tfeatures\ttrust_probability\tteacher_status\tteacher_choice\tteacher_probabilities\tutility_status\tutility_choice\tutility_probabilities\ttrust_status\ttrust_choice\ttrust_probabilities"
            .equals(r.readLine()),
        "Trust schema changed");
    UtilityExpertDecisionModel base = UtilityNeuralProbeContract.model(false);
    ExpertTrustDecisionModel dense = model(false), sparse = model(true);
    require(
        base.checkpointFingerprint().equals(TrainedExpertTrust.BASE_SHA256)
            && sparse.baseFingerprint().equals(dense.baseFingerprint()),
        "Trust base checkpoint drift");
    TinyNeuralRuntime network = TrainedExpertTrust.model();
    require(
        network.inputSize() == 20 && network.outputSize() == 2 && network.parameterCount() <= 512,
        "Trust budget changed");
    TinyDecisionModel risk =
        new TinyDecisionModel(
            network, new String[] {"keep", "use"}, TrainedExpertTrust.TEMPERATURE);
    RichIntentRefusalModel teacher = UtilityNeuralProbeContract.teacher();
    int count = 0;
    String line;
    while ((line = r.readLine()) != null) {
      require(count < EXPECTED_PROBES && line.length() < 12000, "Trust probe bounds exceeded");
      String[] c = line.split("\t", -1);
      require(
          c.length == 13 && c[0].equals(Integer.toString(count)) && c[1].length() <= 2048,
          "Trust probe order changed");
      count++;
      double expected = Double.parseDouble(c[3]);
      require(Double.isFinite(expected), "Nonfinite trust score");
      if (c[2].equals("-")) require(expected == -1, "Missing trust feature score");
      else {
        String[] v = c[2].split(",", -1);
        require(v.length == 20, "Trust feature shape drift");
        float[] x = new float[20];
        for (int i = 0; i < 20; i++) {
          x[i] = Float.parseFloat(v[i]);
          require(Float.isFinite(x[i]) && x[i] >= 0 && x[i] <= 1, "Unbounded trust features");
        }
        require(
            expected >= 0
                && expected <= 1
                && Math.abs(risk.decide(x, 0, 0).probabilitiesCopy()[1] - expected) <= 1e-6,
            "Trust JVM/ART numerical drift");
        require(
            Arrays.equals(network.infer(x), network.withSparseInput().infer(x)),
            "Trust sparse logits drift");
      }
      TinyDecisionModel.Decision before = teacher.classify(c[1], .6, .15),
          proposal = base.classify(c[1], .6, .15),
          after = dense.classify(c[1], .6, .15);
      decision(before, c, 4);
      decision(proposal, c, 7);
      decision(after, c, 10);
      require(exact(after, sparse.classify(c[1], .6, .15)), "Trust sparse choice drift");
      require(exact(after, before) || exact(after, proposal), "Trust invented a proposal");
      if (before.status == TinyDecisionModel.Status.ABSTAIN)
        require(exact(before, after), "Trust upgraded refusal");
      TinyDecisionModel.Decision strict = dense.classify(c[1], .95, .9);
      require(
          strict.status != TinyDecisionModel.Status.CHOICE
              || strict.probability >= .95 && strict.margin > .9,
          "Trust bypassed caller confidence");
    }
    require(count == EXPECTED_PROBES, "Missing trust probes");
    return count;
  }

  private static void decision(TinyDecisionModel.Decision actual, String[] c, int start) {
    require(
        actual.status.name().equals(c[start])
            && Objects.equals(actual.choice == null ? "-" : actual.choice, c[start + 1]),
        "Trust JVM/ART decision drift");
    double[] p = actual.probabilitiesCopy();
    String[] encoded = c[start + 2].split(",", -1);
    require(
        (p.length == 5 || p.length == 6) && p.length == encoded.length,
        "Trust probability shape drift");
    for (int i = 0; i < p.length; i++) {
      double value = Double.parseDouble(encoded[i]);
      require(
          Double.isFinite(value) && value >= 0 && value <= 1 && Math.abs(p[i] - value) <= 1e-6,
          "Trust JVM/ART probability drift");
    }
  }

  private static boolean exact(TinyDecisionModel.Decision a, TinyDecisionModel.Decision b) {
    return a.status == b.status
        && Objects.equals(a.choice, b.choice)
        && Arrays.equals(a.probabilitiesCopy(), b.probabilitiesCopy());
  }

  private static void require(boolean value, String message) {
    if (!value) throw new IllegalStateException(message);
  }

  private ExpertTrustNeuralProbeContract() {}
}
