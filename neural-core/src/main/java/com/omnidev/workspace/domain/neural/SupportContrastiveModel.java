package com.omnidev.workspace.domain.neural;

import java.util.*;

/** Experimental answerability veto: cannot upgrade a proposal refusal or change its references. */
public final class SupportContrastiveModel {
  public static final String VERSION = "support-contrastive-linear-or-tanh8-veto-v1";

  public static final class Result {
    public final List<SupportQueryMemory.Hit> matches;
    public final boolean accepted, proposalAccepted, scored;
    public final double answerability;

    private Result(
        SupportSubwordModel.Result proposal, double probability, boolean scored, double threshold) {
      matches = proposal.matches;
      proposalAccepted = proposal.accepted;
      this.scored = scored;
      answerability = probability;
      accepted = proposalAccepted && scored && probability >= threshold;
    }
  }

  private final SupportSubwordModel proposal;
  private final SupportPairEncoder encoder;
  private final TinyNeuralRuntime head;
  private final double temperature, threshold;
  private final String fingerprint;

  public SupportContrastiveModel(
      SupportSubwordModel proposal,
      SupportPairEncoder encoder,
      float[] parameters,
      int hidden,
      double temperature,
      double threshold,
      String expectedBinding,
      String version) {
    if (proposal == null
        || encoder == null
        || parameters == null
        || (hidden != 0 && hidden != 8)
        || parameters.length != (hidden == 0 ? 1025 : 8209)
        || !Double.isFinite(temperature)
        || temperature < .05
        || temperature > 100
        || !Double.isFinite(threshold)
        || threshold < 0
        || threshold > 1
        || !VERSION.equals(version)
        || !binding(proposal, encoder).equals(expectedBinding))
      throw new IllegalArgumentException("Invalid answerability checkpoint contract");
    this.proposal = proposal;
    this.encoder = encoder;
    this.head = runtime(parameters, hidden);
    this.temperature = temperature;
    this.threshold = threshold;
    fingerprint =
        new CheckpointFingerprint(VERSION)
            .text(expectedBinding)
            .text(this.head.checkpointFingerprint())
            .number(temperature)
            .number(threshold)
            .finish();
  }

  private SupportContrastiveModel(
      SupportSubwordModel proposal,
      SupportPairEncoder encoder,
      TinyNeuralRuntime head,
      double temperature,
      double threshold) {
    if (!Double.isFinite(threshold) || threshold < 0 || threshold > 1)
      throw new IllegalArgumentException("Contrastive cutoff");
    this.proposal = proposal;
    this.encoder = encoder;
    this.head = head;
    this.temperature = temperature;
    this.threshold = threshold;
    fingerprint =
        new CheckpointFingerprint(VERSION)
            .text(binding(proposal, encoder))
            .text(head.checkpointFingerprint())
            .number(temperature)
            .number(threshold)
            .finish();
  }

  static TinyNeuralRuntime runtime(float[] parameters, int hidden) {
    if (parameters == null
        || (hidden != 0 && hidden != 8)
        || parameters.length != (hidden == 0 ? 1025 : 8209))
      throw new IllegalArgumentException("Contrastive topology");
    if (hidden == 0)
      return new TinyNeuralRuntime(
              new TinyNeuralRuntime.Dense(
                  1024,
                  1,
                  Arrays.copyOf(parameters, 1024),
                  new float[] {parameters[1024]},
                  TinyNeuralRuntime.Activation.LINEAR))
          .withSparseInput();
    return new TinyNeuralRuntime(
            new TinyNeuralRuntime.Dense(
                1024,
                8,
                Arrays.copyOf(parameters, 8192),
                Arrays.copyOfRange(parameters, 8192, 8200),
                TinyNeuralRuntime.Activation.TANH),
            new TinyNeuralRuntime.Dense(
                8,
                1,
                Arrays.copyOfRange(parameters, 8200, 8208),
                new float[] {parameters[8208]},
                TinyNeuralRuntime.Activation.LINEAR))
        .withSparseInput();
  }

  static String binding(SupportSubwordModel proposal, SupportPairEncoder encoder) {
    return new CheckpointFingerprint(VERSION)
        .text(proposal.fingerprint())
        .text(encoder.fingerprint())
        .finish();
  }

  public String fingerprint() {
    return fingerprint;
  }

  public int parameterCount() {
    return head.parameterCount();
  }

  public SupportContrastiveModel withThreshold(double threshold) {
    return new SupportContrastiveModel(proposal, encoder, head, temperature, threshold);
  }

  public double score(String query, String topic) {
    return ProductSupportModel.sigmoid(head.infer(encoder.encode(query, topic))[0] / temperature);
  }

  public Result search(String query, int limit) {
    SupportSubwordModel.Result p = proposal.search(query, limit);
    if (!p.accepted || p.matches.isEmpty()) return new Result(p, 0, false, threshold);
    return new Result(p, score(query, p.matches.get(0).topic.id), true, threshold);
  }
}
