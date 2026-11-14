package com.omnidev.workspace.neural.probe;

import com.omnidev.workspace.domain.neural.*;
import java.io.*;
import java.util.*;

/** Exact packaged bank/policy validation; an ART pass does not establish decision quality. */
public final class PrototypeNeuralProbeContract {
  public static final int EXPECTED_PROBES = 1070;

  public static UtilityExpertDecisionModel model(boolean sparse) {
    return model(UtilityNeuralProbeContract.teacher(), TrainedRichVocabulary.encoder(), sparse);
  }

  private static UtilityExpertDecisionModel model(
      RichIntentRefusalModel teacher, RichTextEncoder encoder, boolean sparse) {
    TinyNeuralRuntime network = TrainedUtilityExpert.model();
    return new UtilityExpertDecisionModel(
        teacher,
        encoder,
        sparse ? network.withSparseInput() : network,
        TrainedUtilityExpert.TEMPERATURE,
        TrainedPrototypePolicy.reject(),
        TrainedPrototypePolicy.change(),
        TrainedUtilityExpert.support(),
        TrainedPrototypePolicy.VOCABULARY_SHA256,
        TrainedDomainPrototypes.model(),
        TrainedPrototypePolicy.cutoffs(),
        TrainedPrototypePolicy.BANK_SHA256);
  }

  public static int verify(Reader source) throws Exception {
    require(!TrainedPrototypePolicy.PRODUCTION_ELIGIBLE, "Unverified prototype promotion");
    BufferedReader reader = new BufferedReader(source);
    String[] metadata = {
      "bank_sha256\t" + TrainedPrototypePolicy.BANK_SHA256,
      "vocabulary_sha256\t" + TrainedPrototypePolicy.VOCABULARY_SHA256,
      "reject\t" + Arrays.toString(TrainedPrototypePolicy.reject()),
      "change\t" + Arrays.toString(TrainedPrototypePolicy.change()),
      "cutoffs\t" + Arrays.toString(TrainedPrototypePolicy.cutoffs())
    };
    for (String value : metadata)
      require(value.equals(reader.readLine()), "Prototype metadata pairing changed");
    require(
        "id\ttext\tfeature_sha256\tsupport_scores\tstatus\tchoice\tprobabilities"
            .equals(reader.readLine()),
        "Prototype schema changed");
    RichTextEncoder encoder = TrainedRichVocabulary.encoder();
    DomainPrototypeSupport bank = TrainedDomainPrototypes.model();
    require(
        bank.fingerprint().equals(TrainedPrototypePolicy.BANK_SHA256)
            && bank.vocabularyFingerprint().equals(encoder.fingerprint())
            && encoder.fingerprint().equals(TrainedPrototypePolicy.VOCABULARY_SHA256),
        "Foreign prototype bank or vocabulary");
    require(
        bank.prototypeCount() == 15 && bank.floatCount() == 15360,
        "Prototype memory budget changed");
    RichIntentRefusalModel teacher = UtilityNeuralProbeContract.teacher();
    UtilityExpertDecisionModel dense = model(teacher, encoder, false),
        sparse = model(teacher, encoder, true);
    int count = 0;
    String line;
    while ((line = reader.readLine()) != null) {
      require(count < EXPECTED_PROBES && line.length() < 12000, "Prototype probe bounds exceeded");
      String[] c = line.split("\t", -1);
      require(
          c.length == 7 && c[0].equals(Integer.toString(count)) && c[1].length() <= 2048,
          "Prototype probe order changed");
      count++;
      float[] features = encoder.encode(c[1]);
      require(
          c[2].equals(UtilityNeuralProbeContract.hash(features)),
          "Prototype JVM/ART feature drift");
      double[] scores = new double[5];
      for (int d = 0; d < 5; d++) scores[d] = bank.similarity(features, d);
      numeric(scores, c[3], 2e-6);
      TinyDecisionModel.Decision before = teacher.classify(c[1], .6, .15),
          after = dense.classify(c[1], .6, .15),
          other = sparse.classify(c[1], .6, .15);
      require(
          after.status.name().equals(c[4])
              && Objects.equals(after.choice == null ? "-" : after.choice, c[5]),
          "Prototype JVM/ART choice drift");
      require(
          after.probabilitiesCopy().length == 5 || after.probabilitiesCopy().length == 6,
          "Prototype decision schema changed");
      numeric(after.probabilitiesCopy(), c[6], 1e-6);
      exact(after, other);
      if (before.status == TinyDecisionModel.Status.ABSTAIN) exact(before, after);
      require(!"__reject__".equals(after.choice), "Reject escaped as a proposal");
      TinyDecisionModel.Decision strict = dense.classify(c[1], .95, .9);
      require(
          strict.status != TinyDecisionModel.Status.CHOICE
              || strict.probability >= .95 && strict.margin > .9,
          "Prototype bypassed caller confidence");
    }
    require(count == EXPECTED_PROBES, "Missing prototype probes");
    return count;
  }

  private static void numeric(double[] actual, String encoded, double tolerance) {
    String[] values = encoded.split(",", -1);
    require(actual.length == values.length, "Prototype numeric shape changed");
    for (int i = 0; i < values.length; i++) {
      double expected = Double.parseDouble(values[i]);
      require(
          Double.isFinite(expected)
              && expected >= 0
              && expected <= 1
              && Math.abs(expected - actual[i]) <= tolerance,
          "Prototype JVM/ART numeric drift");
    }
  }

  private static void exact(TinyDecisionModel.Decision a, TinyDecisionModel.Decision b) {
    require(
        a.status == b.status
            && Objects.equals(a.choice, b.choice)
            && Arrays.equals(a.probabilitiesCopy(), b.probabilitiesCopy()),
        "Prototype refusal/sparse integrity changed");
  }

  private static void require(boolean ok, String message) {
    if (!ok) throw new IllegalStateException(message);
  }

  private PrototypeNeuralProbeContract() {}
}
