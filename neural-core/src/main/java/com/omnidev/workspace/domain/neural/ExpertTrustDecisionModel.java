package com.omnidev.workspace.domain.neural;

/**
 * A bounded learned selector over two existing proposals; predictions never authorize execution.
 */
public final class ExpertTrustDecisionModel {
  public static final String VERSION = "teacher5-expert6-domain5-presence-reject-margins-v1";
  public static final int DIMENSIONS = 20, MAX_PARAMETERS = 512;
  private final UtilityExpertDecisionModel base;
  private final TinyDecisionModel trust;
  private final double[] cutoffs;
  private final String baseFingerprint;

  public ExpertTrustDecisionModel(
      UtilityExpertDecisionModel base,
      TinyNeuralRuntime network,
      double temperature,
      double[] cutoffs,
      String featureVersion,
      String expectedBaseFingerprint) {
    if (base == null
        || base.hasPrototypeProtection()
        || network == null
        || network.inputSize() != DIMENSIONS
        || network.outputSize() != 2
        || network.parameterCount() > MAX_PARAMETERS
        || !VERSION.equals(featureVersion)
        || cutoffs == null
        || cutoffs.length != 5) throw new IllegalArgumentException("Invalid trust selector schema");
    this.cutoffs = cutoffs.clone();
    for (double c : this.cutoffs)
      if (!Double.isFinite(c) || c < 0 || c > 1 && c != 1.1)
        throw new IllegalArgumentException("Invalid trust cutoff");
    baseFingerprint = base.checkpointFingerprint();
    if (!baseFingerprint.equals(expectedBaseFingerprint))
      throw new IllegalArgumentException("Foreign frozen trust base");
    this.base = base;
    trust = new TinyDecisionModel(network, new String[] {"keep", "use"}, temperature);
  }

  public TinyDecisionModel.Decision classify(String text, double probability, double margin) {
    UtilityExpertDecisionModel.Review review = base.review(text, probability, margin);
    if (review.before.status == TinyDecisionModel.Status.ABSTAIN
        || review.proposed == review.before
        || cutoffs[UtilityExpertDecisionModel.domain(review.before.choice)] == 1.1)
      return review.before;
    return select(
        review, score(review), cutoffs[UtilityExpertDecisionModel.domain(review.before.choice)]);
  }

  public String baseFingerprint() {
    return baseFingerprint;
  }

  double score(UtilityExpertDecisionModel.Review review) {
    return trust.decide(review.features, 0, 0).probabilitiesCopy()[1];
  }

  static TinyDecisionModel.Decision select(
      UtilityExpertDecisionModel.Review review, double score, double cutoff) {
    if (review == null
        || review.before == null
        || review.proposed == null
        || !Double.isFinite(score)
        || score < 0
        || score > 1
        || !Double.isFinite(cutoff)
        || cutoff < 0
        || cutoff > 1 && cutoff != 1.1)
      throw new IllegalArgumentException("Invalid trust decision");
    if (review.before.status == TinyDecisionModel.Status.ABSTAIN
        || review.proposed == review.before) return review.before;
    return score >= cutoff ? review.proposed : review.before;
  }

  static float[] features(
      TinyDecisionModel.Decision before, TinyDecisionModel.Decision raw, float support) {
    if (before == null
        || raw == null
        || before.status != TinyDecisionModel.Status.CHOICE
        || raw.status != TinyDecisionModel.Status.CHOICE
        || raw.probabilitiesCopy().length != 6
        || before.probabilitiesCopy().length != 5
        || !Float.isFinite(support)
        || support < 0
        || support > 1) throw new IllegalArgumentException("Invalid trust feature schema");
    int domain = UtilityExpertDecisionModel.domain(before.choice);
    if (!"__reject__".equals(raw.choice)) UtilityExpertDecisionModel.domain(raw.choice);
    float[] x = new float[DIMENSIONS];
    double[] a = before.probabilitiesCopy(), b = raw.probabilitiesCopy();
    for (int i = 0; i < 5; i++) x[i] = (float) a[i];
    for (int i = 0; i < 6; i++) x[5 + i] = (float) b[i];
    x[11 + domain] = 1;
    x[16] = support;
    x[17] = "__reject__".equals(raw.choice) ? 1 : 0;
    x[18] = (float) before.margin;
    x[19] = (float) raw.margin;
    return x;
  }
}
