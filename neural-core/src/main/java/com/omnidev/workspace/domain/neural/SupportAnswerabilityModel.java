package com.omnidev.workspace.domain.neural;

import java.util.*;

/** Experimental answerability veto: cannot upgrade a proposal refusal or change its references. */
public final class SupportAnswerabilityModel {
  public static final String VERSION = "support-answerability-linear-veto-v1";

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

  public SupportAnswerabilityModel(
      SupportSubwordModel proposal,
      SupportPairEncoder encoder,
      TinyNeuralRuntime head,
      double temperature,
      double threshold,
      String expectedBinding,
      String version) {
    if (proposal == null
        || encoder == null
        || head == null
        || head.inputSize() != 1024
        || head.outputSize() != 1
        || head.parameterCount() != 1025
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
    this.head = head;
    this.temperature = temperature;
    this.threshold = threshold;
    fingerprint =
        new CheckpointFingerprint(VERSION)
            .text(expectedBinding)
            .text(head.checkpointFingerprint())
            .number(temperature)
            .number(threshold)
            .finish();
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

  public SupportAnswerabilityModel withThreshold(double threshold) {
    return new SupportAnswerabilityModel(
        proposal, encoder, head, temperature, threshold, binding(proposal, encoder), VERSION);
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
