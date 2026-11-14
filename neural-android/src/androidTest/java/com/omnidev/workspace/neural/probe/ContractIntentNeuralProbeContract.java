package com.omnidev.workspace.neural.probe;

import com.omnidev.workspace.domain.neural.*;
import java.io.*;
import java.util.*;

/**
 * Pairing and numeric parity for broader-supervision weights; raw predictions stay diagnostic only.
 */
public final class ContractIntentNeuralProbeContract {
  public static final int EXPECTED_PROBES = 1366;

  public static UtilityExpertDecisionModel model(boolean sparse) {
    TinyNeuralRuntime n = TrainedContractExpert.model();
    return new UtilityExpertDecisionModel(
        UtilityNeuralProbeContract.teacher(),
        TrainedRichVocabulary.encoder(),
        sparse ? n.withSparseInput() : n,
        TrainedContractPolicy.TEMPERATURE,
        TrainedContractPolicy.reject(),
        TrainedContractPolicy.change(),
        TrainedContractPolicy.support(),
        TrainedContractPolicy.VOCABULARY_SHA256);
  }

  private static RichTextDecisionModel raw(boolean sparse) {
    TinyNeuralRuntime n = TrainedContractExpert.model();
    return new RichTextDecisionModel(
        TrainedRichVocabulary.encoder(),
        sparse ? n.withSparseInput() : n,
        TrainedContractPolicy.TEMPERATURE,
        RichTextEncoder.VERSION,
        TrainedContractPolicy.VOCABULARY_SHA256);
  }

  public static int verify(Reader source) throws Exception {
    require(!TrainedContractPolicy.PRODUCTION_ELIGIBLE, "Unverified contract promotion");
    BufferedReader reader = new BufferedReader(source);
    String[] metadata = {
      "runtime_sha256\t" + TrainedContractPolicy.RUNTIME_SHA256,
      "source\t" + TrainedContractPolicy.SOURCE,
      "temperature\t" + TrainedContractPolicy.TEMPERATURE,
      "vocabulary_sha256\t" + TrainedContractPolicy.VOCABULARY_SHA256,
      "reject\t" + Arrays.toString(TrainedContractPolicy.reject()),
      "change\t" + Arrays.toString(TrainedContractPolicy.change()),
      "support\t" + Arrays.toString(TrainedContractPolicy.support())
    };
    for (String value : metadata)
      require(value.equals(reader.readLine()), "Contract metadata pairing changed");
    require(
        "id\ttext\tteacher_status\tteacher_choice\tteacher_probabilities\tprotected_status\tprotected_choice\tprotected_probabilities\traw_status\traw_choice\traw_probabilities"
            .equals(reader.readLine()),
        "Contract schema changed");
    UtilityExpertDecisionModel a = model(false), b = model(true);
    RichTextDecisionModel raw = raw(false), sparseRaw = raw(true);
    RichIntentRefusalModel teacher = UtilityNeuralProbeContract.teacher();
    require(
        a.checkpointFingerprint().equals(TrainedContractPolicy.RUNTIME_SHA256)
            && b.checkpointFingerprint().equals(TrainedContractPolicy.RUNTIME_SHA256),
        "Foreign contract model/policy pairing");
    require(
        TrainedContractExpert.model().parameterCount() <= 20000,
        "Contract parameter budget changed");
    int count = 0;
    String line;
    while ((line = reader.readLine()) != null) {
      require(count < EXPECTED_PROBES && line.length() < 12000, "Contract probe bounds exceeded");
      String[] c = line.split("\t", -1);
      require(
          c.length == 11 && c[0].equals(Integer.toString(count)) && c[1].length() <= 2048,
          "Contract probe order changed");
      count++;
      TinyDecisionModel.Decision before = teacher.classify(c[1], .6, .15),
          protectedDecision = a.classify(c[1], .6, .15),
          diagnostic = raw.classify(c[1], .6, .15);
      decision(before, c, 2);
      decision(protectedDecision, c, 5);
      decision(diagnostic, c, 8);
      require(
          exact(protectedDecision, b.classify(c[1], .6, .15))
              && exact(diagnostic, sparseRaw.classify(c[1], .6, .15)),
          "Contract dense/sparse drift");
      if (before.status == TinyDecisionModel.Status.ABSTAIN)
        require(exact(before, protectedDecision), "Protected contract upgraded prior refusal");
      require(
          !"__reject__".equals(protectedDecision.choice) && !"__reject__".equals(diagnostic.choice),
          "Reject escaped as a choice");
      TinyDecisionModel.Decision strict = a.classify(c[1], .95, .9);
      require(
          strict.status != TinyDecisionModel.Status.CHOICE
              || strict.probability >= .95 && strict.margin > .9,
          "Contract bypassed caller confidence");
    }
    require(count == EXPECTED_PROBES, "Missing contract probes");
    return count;
  }

  private static void decision(TinyDecisionModel.Decision actual, String[] c, int offset) {
    require(
        actual.status.name().equals(c[offset])
            && Objects.equals(actual.choice == null ? "-" : actual.choice, c[offset + 1]),
        "Contract JVM/ART choice drift");
    double[] p = actual.probabilitiesCopy();
    String[] v = c[offset + 2].split(",", -1);
    require(
        v.length == p.length && (p.length == 5 || p.length == 6),
        "Contract probability shape changed");
    for (int i = 0; i < p.length; i++) {
      double d = Double.parseDouble(v[i]);
      require(
          Double.isFinite(d) && d >= 0 && d <= 1 && Math.abs(d - p[i]) <= 1e-6,
          "Contract JVM/ART numeric drift");
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

  private ContractIntentNeuralProbeContract() {}
}
