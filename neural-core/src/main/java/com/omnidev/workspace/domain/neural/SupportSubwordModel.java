package com.omnidev.workspace.domain.neural;

import java.util.*;

/** Experimental soft-coverage selector with frozen ranking and original-only evidence. */
public final class SupportSubwordModel {
  public static final String VERSION = "support-soft-token-context12-v1";

  public static final class Result {
    public final List<SupportQueryMemory.Hit> matches;
    public final boolean accepted, learnedAccepted, baselineAccepted;
    public final double reliability, softCoverage;

    private Result(
        SupportReliabilityModel.Frame frame,
        double p,
        double coverage,
        boolean learned,
        boolean active,
        int limit) {
      matches =
          Collections.unmodifiableList(
              new ArrayList<>(frame.hits.subList(0, Math.min(limit, frame.hits.size()))));
      reliability = p;
      softCoverage = coverage;
      learnedAccepted = learned;
      baselineAccepted = frame.baselineAccepted;
      accepted = active ? learned : baselineAccepted;
    }
  }

  private final SupportQueryMemory memory;
  private final TrainTokenCoverage tokens;
  private final TinyNeuralRuntime head;
  private final double temperature, threshold, coverage, margin;
  private final boolean active;
  private final String fingerprint;

  public SupportSubwordModel(
      SupportQueryMemory memory,
      TrainTokenCoverage tokens,
      TinyNeuralRuntime head,
      double temperature,
      double threshold,
      double coverage,
      double margin,
      boolean active,
      String expectedBinding,
      String version) {
    if (memory == null
        || tokens == null
        || head == null
        || head.inputSize() != 12
        || head.outputSize() != 1
        || head.parameterCount() > 225
        || !Double.isFinite(temperature)
        || temperature < .05
        || temperature > 100
        || !unit(threshold)
        || !unit(coverage)
        || !unit(margin)
        || !VERSION.equals(version)
        || !binding(memory, tokens).equals(expectedBinding))
      throw new IllegalArgumentException("Invalid subword checkpoint contract");
    this.memory = memory;
    this.tokens = tokens;
    this.head = head;
    this.temperature = temperature;
    this.threshold = threshold;
    this.coverage = coverage;
    this.margin = margin;
    this.active = active;
    fingerprint =
        new CheckpointFingerprint(VERSION)
            .text(expectedBinding)
            .text(head.checkpointFingerprint())
            .number(temperature)
            .number(threshold)
            .number(coverage)
            .number(margin)
            .integer(active ? 1 : 0)
            .finish();
  }

  static String binding(SupportQueryMemory memory, TrainTokenCoverage tokens) {
    return new CheckpointFingerprint(VERSION)
        .text(memory.contractFingerprint())
        .text(tokens.fingerprint())
        .finish();
  }

  public String fingerprint() {
    return fingerprint;
  }

  public int parameterCount() {
    return head.parameterCount();
  }

  static float[] features(
      SupportReliabilityModel.Frame frame,
      TrainTokenCoverage index,
      String query,
      String excluded) {
    float[] x = new float[12];
    if (frame.hits.isEmpty()) return x;
    Map<String, Double> soft = index.coverage(query, excluded);
    System.arraycopy(frame.rows.get(0).featuresCopy(), 0, x, 0, 3);
    x[3] = soft.getOrDefault(frame.hits.get(0).topic.id, 0.0).floatValue();
    x[4] =
        frame.hits.size() > 1 ? soft.getOrDefault(frame.hits.get(1).topic.id, 0.0).floatValue() : 0;
    x[5] = (float) frame.hits.get(0).coverage;
    x[6] = frame.hits.size() > 1 ? (float) frame.hits.get(1).coverage : 0;
    x[7] =
        (float) (frame.hits.get(0).score - (frame.hits.size() > 1 ? frame.hits.get(1).score : 0));
    x[8] =
        (float) (frame.hits.get(0).score - (frame.hits.size() > 2 ? frame.hits.get(2).score : 0));
    x[9] = x[3] - x[4];
    x[10] = Math.min(1, frame.hits.size() / 29f);
    String normalized = HybridSupportRetriever.normalize(query, true);
    x[11] = Math.min(1, normalized.isEmpty() ? 0 : normalized.split(" ").length / 32f);
    return x;
  }

  public Result search(String query, int limit) {
    if (limit < 1 || limit > 10) throw new IllegalArgumentException("Invalid subword result limit");
    SupportReliabilityModel.Frame frame = new SupportReliabilityModel.Frame(memory, query, null);
    float[] x = features(frame, tokens, query, null);
    double p =
        frame.hits.isEmpty() ? 0 : ProductSupportModel.sigmoid(head.infer(x)[0] / temperature);
    boolean learned =
        !frame.hits.isEmpty()
            && p >= threshold
            && x[3] >= coverage
            && (frame.hits.size() == 1
                || frame.hits.get(0).score - frame.hits.get(1).score >= margin);
    return new Result(frame, p, x[3], learned, active, limit);
  }

  private static boolean unit(double x) {
    return Double.isFinite(x) && x >= 0 && x <= 1;
  }
}
