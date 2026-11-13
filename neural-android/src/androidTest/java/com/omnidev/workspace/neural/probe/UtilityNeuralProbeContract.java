package com.omnidev.workspace.neural.probe;

import com.omnidev.workspace.domain.neural.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Host/ART parity for the selected utility expert. Runtime compatibility is separate from promotion
 * quality.
 */
public final class UtilityNeuralProbeContract {
  public static final int EXPECTED_PROBES = 995;

  public static UtilityExpertDecisionModel model(boolean sparse) {
    TinyNeuralRuntime network = TrainedUtilityExpert.model();
    return model(
        teacher(), TrainedRichVocabulary.encoder(), sparse ? network.withSparseInput() : network);
  }

  public static int verify(Reader source) throws Exception {
    require(!TrainedUtilityExpert.PRODUCTION_ELIGIBLE, "Unverified utility promotion");
    BufferedReader reader = new BufferedReader(source);
    String[] metadata = {
      "source\t" + TrainedUtilityExpert.SOURCE,
      "vocabulary_sha256\t" + TrainedUtilityExpert.VOCABULARY_SHA256,
      "temperature\t" + TrainedUtilityExpert.TEMPERATURE,
      "reject\t" + Arrays.toString(TrainedUtilityExpert.reject()),
      "change\t" + Arrays.toString(TrainedUtilityExpert.change()),
      "support\t" + Arrays.toString(TrainedUtilityExpert.support())
    };
    for (String value : metadata)
      require(value.equals(reader.readLine()), "Utility metadata pairing changed");
    require(
        "id\ttext\tfeature_sha256\texpert_probabilities\tsupport_scores\tteacher_status\tteacher_choice\tteacher_probabilities\tutility_status\tutility_choice\tutility_probabilities"
            .equals(reader.readLine()),
        "Utility probe schema changed");
    RichTextEncoder encoder = TrainedRichVocabulary.encoder();
    require(
        encoder.fingerprint().equals(TrainedUtilityExpert.VOCABULARY_SHA256),
        "Foreign utility vocabulary");
    TinyNeuralRuntime dense = TrainedUtilityExpert.model(),
        sparse = dense.withSparseInput(),
        presence = TrainedRichPresence.model();
    RichIntentRefusalModel teacher = teacher();
    UtilityExpertDecisionModel a = model(teacher, encoder, dense),
        b = model(teacher, encoder, sparse);
    TinyDecisionModel expert =
        new TinyDecisionModel(
            dense, RichTextDecisionModel.labels(), TrainedUtilityExpert.TEMPERATURE);
    int count = 0;
    String line;
    while ((line = reader.readLine()) != null) {
      require(count < EXPECTED_PROBES && line.length() < 12000, "Utility probe bounds exceeded");
      String[] c = line.split("\t", -1);
      require(
          c.length == 11 && c[0].equals(Integer.toString(count)) && c[1].length() <= 2048,
          "Utility probe order changed");
      count++;
      float[] features = encoder.encode(c[1]);
      require(c[2].equals(hash(features)), "Utility JVM/ART feature drift");
      require(
          Arrays.equals(dense.infer(features), sparse.infer(features)),
          "Utility dense/sparse logits drift");
      numeric(expert.decide(features, 0, 0).probabilitiesCopy(), c[3], 6, 1e-6);
      float[] support = presence.infer(features);
      double[] supportDouble = new double[5];
      for (int i = 0; i < 5; i++) supportDouble[i] = support[i];
      numeric(supportDouble, c[4], 5, 2e-6);
      TinyDecisionModel.Decision before = teacher.classify(c[1], .6, .15),
          after = a.classify(c[1], .6, .15),
          s = b.classify(c[1], .6, .15);
      decision(before, c[5], c[6], c[7]);
      decision(after, c[8], c[9], c[10]);
      exact(after, s);
      if (before.status == TinyDecisionModel.Status.ABSTAIN) exact(before, after);
      require(!"__reject__".equals(after.choice), "Reject escaped as a proposal");
    }
    require(count == EXPECTED_PROBES, "Missing utility probes");
    return count;
  }

  private static UtilityExpertDecisionModel model(
      RichIntentRefusalModel teacher, RichTextEncoder encoder, TinyNeuralRuntime network) {
    return new UtilityExpertDecisionModel(
        teacher,
        encoder,
        network,
        TrainedUtilityExpert.TEMPERATURE,
        TrainedUtilityExpert.reject(),
        TrainedUtilityExpert.change(),
        TrainedUtilityExpert.support(),
        TrainedUtilityExpert.VOCABULARY_SHA256);
  }

  private static RichIntentRefusalModel teacher() {
    return new RichIntentRefusalModel(
        NeuralProbeContract.gated(
            TrainedTextIntentCheckpoint.model().withSparseInput(),
            TrainedRefusalGateCheckpoint.model().withSparseInput()),
        TrainedRichVocabulary.encoder(),
        TrainedRichPresence.model().withSparseInput(),
        TrainedRichVocabulary.THRESHOLD,
        TrainedRichVocabulary.REQUIRE_MATCH,
        RichTextEncoder.VERSION,
        TrainedPublicRefusal.VOCABULARY_SHA256,
        TrainedPublicRefusal.model().withSparseInput(),
        TrainedPublicRefusal.CUTOFF);
  }

  private static void numeric(double[] values, String encoded, int length, double tolerance) {
    String[] p = encoded.split(",", -1);
    require(values.length == length && p.length == length, "Utility probability shape changed");
    for (int i = 0; i < length; i++) {
      double value = Double.parseDouble(p[i]);
      require(
          Double.isFinite(value)
              && value >= 0
              && value <= 1
              && Math.abs(values[i] - value) <= tolerance,
          "Utility JVM/ART probability drift");
    }
  }

  private static void decision(
      TinyDecisionModel.Decision actual, String status, String choice, String probabilities) {
    require(
        actual.status.name().equals(status)
            && Objects.equals(actual.choice == null ? "-" : actual.choice, choice),
        "Utility JVM/ART choice drift");
    int length = actual.probabilitiesCopy().length;
    require(length == 5 || length == 6, "Utility output schema changed");
    numeric(actual.probabilitiesCopy(), probabilities, length, 1e-6);
  }

  private static void exact(TinyDecisionModel.Decision a, TinyDecisionModel.Decision b) {
    require(
        a.status == b.status
            && Objects.equals(a.choice, b.choice)
            && Arrays.equals(a.probabilitiesCopy(), b.probabilitiesCopy()),
        "Utility refusal/sparse integrity changed");
  }

  private static String hash(float[] x) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    for (float f : x) {
      int v = Float.floatToIntBits(f);
      digest.update((byte) (v >>> 24));
      digest.update((byte) (v >>> 16));
      digest.update((byte) (v >>> 8));
      digest.update((byte) v);
    }
    StringBuilder s = new StringBuilder();
    for (byte b : digest.digest()) s.append(String.format(Locale.ROOT, "%02x", b & 255));
    return s.toString();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  private UtilityNeuralProbeContract() {}
}
