package com.omnidev.workspace.domain.neural;

/**
 * Domain-specific expert trust regions. Proposals never authorize execution or upgrade teacher
 * refusals.
 */
public final class UtilityExpertDecisionModel {
  public static final double DISABLED = 1.1;
  private static final String[] LABELS = MultiIntentRefusalModel.labels();
  private final RichIntentRefusalModel teacher;
  private final RichTextEncoder encoder;
  private final TinyDecisionModel expert;
  private final double[] reject, change, support;

  public UtilityExpertDecisionModel(
      RichIntentRefusalModel teacher,
      RichTextEncoder encoder,
      TinyNeuralRuntime network,
      double temperature,
      double[] reject,
      double[] change,
      String fingerprint) {
    this(
        teacher,
        encoder,
        network,
        temperature,
        reject,
        change,
        new double[] {1.1, 1.1, 1.1, 1.1, 1.1},
        fingerprint);
  }

  public UtilityExpertDecisionModel(
      RichIntentRefusalModel teacher,
      RichTextEncoder encoder,
      TinyNeuralRuntime network,
      double temperature,
      double[] reject,
      double[] change,
      double[] support,
      String fingerprint) {
    if (teacher == null
        || encoder == null
        || network == null
        || network.inputSize() != 1024
        || network.outputSize() != 6
        || network.parameterCount() > 20_000
        || !encoder.fingerprint().equals(fingerprint)
        || !teacher.featureFingerprint().equals(fingerprint))
      throw new IllegalArgumentException("Invalid utility expert schema");
    validate(reject);
    validate(change);
    validate(support);
    this.reject = reject.clone();
    this.change = change.clone();
    this.support = support.clone();
    this.teacher = teacher;
    this.encoder = encoder;
    this.expert = new TinyDecisionModel(network, RichTextDecisionModel.labels(), temperature);
  }

  public TinyDecisionModel.Decision classify(String text, double probability, double margin) {
    RichIntentRefusalModel.Prepared prepared = teacher.prepare(text, probability, margin);
    TinyDecisionModel.Decision before = prepared.decision;
    if (before.status == TinyDecisionModel.Status.ABSTAIN) return before;
    int domain = domain(before.choice);
    if (reject[domain] == DISABLED && change[domain] == DISABLED) return before;
    return withFeatures(before, prepared.features, domain);
  }

  // Controlled host ablation of the same policy and weights; normal inference shares the teacher
  // encoding.
  TinyDecisionModel.Decision classifyUnshared(String text, double probability, double margin) {
    TinyDecisionModel.Decision before = teacher.classify(text, probability, margin);
    if (before.status == TinyDecisionModel.Status.ABSTAIN) return before;
    int domain = domain(before.choice);
    if (reject[domain] == DISABLED && change[domain] == DISABLED) return before;
    return withFeatures(before, encoder.encode(text), domain);
  }

  private TinyDecisionModel.Decision withFeatures(
      TinyDecisionModel.Decision before, float[] features, int domain) {
    TinyDecisionModel.Decision candidate = expert.decide(features, 0, 0);
    double score =
        support[domain] == DISABLED
                || !"__reject__".equals(candidate.choice)
                || candidate.probability < reject[domain]
            ? 0
            : teacher.domainSupport(features)[domain];
    return choose(before, candidate, reject[domain], change[domain], score, support[domain]);
  }

  static TinyDecisionModel.Decision choose(
      TinyDecisionModel.Decision before,
      TinyDecisionModel.Decision expert,
      double reject,
      double change) {
    return choose(before, expert, reject, change, 0, DISABLED);
  }

  static TinyDecisionModel.Decision choose(
      TinyDecisionModel.Decision before,
      TinyDecisionModel.Decision expert,
      double reject,
      double change,
      double domainSupport,
      double supportCutoff) {
    if (before == null
        || expert == null
        || !threshold(reject)
        || !threshold(change)
        || !threshold(supportCutoff)
        || !Double.isFinite(domainSupport)
        || domainSupport < 0
        || domainSupport > 1) throw new IllegalArgumentException("Invalid utility decision");
    if (before.status == TinyDecisionModel.Status.ABSTAIN) return before;
    domain(before.choice);
    if (expert.probabilitiesCopy().length != 6)
      throw new IllegalArgumentException("Foreign expert distribution");
    if (expert.status == TinyDecisionModel.Status.ABSTAIN) return before;
    if ("__reject__".equals(expert.choice))
      return expert.probability >= reject && domainSupport < supportCutoff
          ? before.asAbstention()
          : before;
    domain(expert.choice);
    if (expert.choice.equals(before.choice)) return before;
    return expert.probability >= change && expert.margin > 0.15 ? expert : before;
  }

  static int domain(String label) {
    for (int i = 0; i < LABELS.length; i++) if (LABELS[i].equals(label)) return i;
    throw new IllegalArgumentException("Foreign expert/teacher label");
  }

  private static boolean threshold(double value) {
    return Double.isFinite(value) && (value >= 0.5 && value <= 1 || value == DISABLED);
  }

  private static void validate(double[] values) {
    if (values == null || values.length != 5)
      throw new IllegalArgumentException("Invalid expert policy shape");
    for (double v : values)
      if (!threshold(v)) throw new IllegalArgumentException("Invalid expert threshold");
  }
}
