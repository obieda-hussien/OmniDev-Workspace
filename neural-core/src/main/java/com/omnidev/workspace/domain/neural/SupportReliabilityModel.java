package com.omnidev.workspace.domain.neural;

import java.util.*;

/** Experimental first-topic correctness selector. Its scores never authorize or verify actions. */
public final class SupportReliabilityModel {
  public static final String VERSION = "support-rank-context12-correctness-v1";
  public static final int FEATURES = 12;

  static final class Frame {
    final List<SupportQueryMemory.Row> rows;
    final List<SupportQueryMemory.Hit> hits;
    final float[] x;
    final boolean baselineAccepted;

    Frame(SupportQueryMemory memory, String query, String excludedGroup) {
      List<SupportQueryMemory.Row> ordered = new ArrayList<>();
      Map<SupportQueryMemory.Row, Double> scores = new IdentityHashMap<>();
      for (SupportQueryMemory.Row row : memory.rows(query, excludedGroup)) {
        double score = memory.score(row);
        if (score > 0) {
          ordered.add(row);
          scores.put(row, score);
        }
      }
      ordered.sort(
          Comparator.comparingDouble((SupportQueryMemory.Row row) -> scores.get(row))
              .reversed()
              .thenComparing(row -> row.topic.id));
      rows = Collections.unmodifiableList(ordered);
      List<SupportQueryMemory.Hit> rank = new ArrayList<>();
      for (SupportQueryMemory.Row row : ordered)
        rank.add(new SupportQueryMemory.Hit(row, scores.get(row)));
      hits = Collections.unmodifiableList(rank);
      baselineAccepted = memory.accepts(hits);
      x = new float[FEATURES];
      if (rows.isEmpty()) return;
      System.arraycopy(rows.get(0).featuresCopy(), 0, x, 0, 3);
      if (rows.size() > 1) System.arraycopy(rows.get(1).featuresCopy(), 0, x, 3, 3);
      x[6] = (float) rows.get(0).coverage;
      x[7] = rows.size() > 1 ? (float) rows.get(1).coverage : 0;
      x[8] = (float) (hits.get(0).score - (hits.size() > 1 ? hits.get(1).score : 0));
      x[9] = (float) (hits.get(0).score - (hits.size() > 2 ? hits.get(2).score : 0));
      x[10] = Math.min(1, rows.size() / 29f);
      String normalized = HybridSupportRetriever.normalize(query, true);
      x[11] = Math.min(1, normalized.isEmpty() ? 0 : normalized.split(" ").length / 32f);
    }
  }

  public static final class Result {
    public final List<SupportQueryMemory.Hit> matches;
    public final double reliability;
    public final boolean accepted, learnedAccepted, baselineAccepted;

    private Result(Frame frame, double reliability, boolean learned, boolean active, int limit) {
      matches =
          Collections.unmodifiableList(
              new ArrayList<>(frame.hits.subList(0, Math.min(limit, frame.hits.size()))));
      this.reliability = reliability;
      learnedAccepted = learned;
      baselineAccepted = frame.baselineAccepted;
      accepted = active ? learned : baselineAccepted;
    }
  }

  private final SupportQueryMemory memory;
  private final TinyNeuralRuntime network;
  private final double temperature, threshold, minimumCoverage, margin;
  private final boolean active;
  private final String fingerprint;

  public SupportReliabilityModel(
      SupportQueryMemory memory,
      TinyNeuralRuntime network,
      double temperature,
      double threshold,
      double coverage,
      double margin,
      boolean active,
      String expectedContract,
      String expectedVersion) {
    if (memory == null
        || network == null
        || network.inputSize() != FEATURES
        || network.outputSize() != 1
        || network.parameterCount() > 225
        || !Double.isFinite(temperature)
        || temperature < .05
        || temperature > 100
        || !unit(threshold)
        || !unit(coverage)
        || !unit(margin)
        || !VERSION.equals(expectedVersion)
        || expectedContract == null
        || !expectedContract.equals(memory.contractFingerprint()))
      throw new IllegalArgumentException("Invalid reliability checkpoint contract");
    this.memory = memory;
    this.network = network;
    this.temperature = temperature;
    this.threshold = threshold;
    this.minimumCoverage = coverage;
    this.margin = margin;
    this.active = active;
    fingerprint =
        new CheckpointFingerprint(VERSION)
            .text(expectedContract)
            .text(network.checkpointFingerprint())
            .number(temperature)
            .number(threshold)
            .number(coverage)
            .number(margin)
            .integer(active ? 1 : 0)
            .finish();
  }

  public String fingerprint() {
    return fingerprint;
  }

  public int parameterCount() {
    return network.parameterCount();
  }

  public Result search(String query, int limit) {
    if (limit < 1 || limit > 10)
      throw new IllegalArgumentException("Invalid reliability result limit");
    Frame frame = new Frame(memory, query, null);
    double probability = probability(frame);
    boolean learned =
        !frame.hits.isEmpty()
            && probability >= threshold
            && frame.hits.get(0).coverage >= minimumCoverage
            && (frame.hits.size() == 1
                || frame.hits.get(0).score - frame.hits.get(1).score >= margin);
    return new Result(frame, probability, learned, active, limit);
  }

  double probability(Frame frame) {
    return frame.hits.isEmpty()
        ? 0
        : ProductSupportModel.sigmoid(network.infer(frame.x)[0] / temperature);
  }

  private static boolean unit(double value) {
    return Double.isFinite(value) && value >= 0 && value <= 1;
  }
}
