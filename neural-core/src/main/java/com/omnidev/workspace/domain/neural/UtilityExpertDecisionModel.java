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
  private final DomainPrototypeSupport prototypes;
  private final double[] prototypeCutoffs;

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
    this(
        teacher,
        encoder,
        network,
        temperature,
        reject,
        change,
        support,
        fingerprint,
        null,
        null,
        null);
  }

  public UtilityExpertDecisionModel(
      RichIntentRefusalModel teacher,
      RichTextEncoder encoder,
      TinyNeuralRuntime network,
      double temperature,
      double[] reject,
      double[] change,
      double[] support,
      String fingerprint,
      DomainPrototypeSupport prototypes,
      double[] prototypeCutoffs,
      String prototypeFingerprint) {
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
    if (prototypes == null) {
      if (prototypeCutoffs != null || prototypeFingerprint != null)
        throw new IllegalArgumentException("Incomplete prototype policy");
      this.prototypeCutoffs = null;
    } else {
      if (!fingerprint.equals(prototypes.vocabularyFingerprint())
          || !prototypes.fingerprint().equals(prototypeFingerprint)
          || prototypeCutoffs == null
          || prototypeCutoffs.length != 5)
        throw new IllegalArgumentException("Foreign prototype pairing");
      for (double cutoff : prototypeCutoffs)
        if (!Double.isFinite(cutoff) || cutoff < 0 || cutoff > 1 && cutoff != DISABLED)
          throw new IllegalArgumentException("Invalid prototype protection cutoff");
      this.prototypeCutoffs = prototypeCutoffs.clone();
    }
    this.prototypes = prototypes;
  }

  public String checkpointFingerprint() {
    CheckpointFingerprint hash =
        new CheckpointFingerprint("utility-policy-v1")
            .text(teacher.checkpointFingerprint())
            .text(encoder.fingerprint())
            .text(expert.checkpointFingerprint());
    for (int d = 0; d < 5; d++) {
      hash.number(reject[d]).number(change[d]).number(support[d]);
      if (prototypeCutoffs != null) hash.number(prototypeCutoffs[d]);
    }
    return hash.text(prototypes == null ? "none" : prototypes.fingerprint()).finish();
  }

  boolean hasPrototypeProtection() {
    return prototypes != null;
  }

  static final class Review {
    final TinyDecisionModel.Decision before, proposed, rawExpert;
    final float[] features;

    Review(
        TinyDecisionModel.Decision before,
        TinyDecisionModel.Decision proposed,
        TinyDecisionModel.Decision rawExpert,
        float[] features) {
      this.before = before;
      this.proposed = proposed;
      this.rawExpert = rawExpert;
      this.features = features;
    }
  }

  // Optional trust review shares the teacher encoding and never runs in the legacy classify path.
  Review review(String text, double probability, double margin) {
    if (prototypes != null)
      throw new IllegalArgumentException("Trust requires an unguarded frozen utility policy");
    RichIntentRefusalModel.Prepared p = teacher.prepare(text, probability, margin);
    TinyDecisionModel.Decision before = p.decision;
    validateTeacher(before);
    if (before.status == TinyDecisionModel.Status.ABSTAIN)
      return new Review(before, before, null, null);
    int domain = domain(before.choice);
    TinyDecisionModel.Decision raw = expert.decide(p.features, 0, 0);
    if (raw.status == TinyDecisionModel.Status.ABSTAIN)
      return new Review(before, before, null, null);
    float score = teacher.domainSupport(p.features)[domain];
    TinyDecisionModel.Decision proposed =
        choose(
            before,
            raw,
            reject[domain],
            change[domain],
            score,
            support[domain],
            probability,
            margin);
    return new Review(before, proposed, raw, ExpertTrustDecisionModel.features(before, raw, score));
  }

  public TinyDecisionModel.Decision classify(String text, double probability, double margin) {
    RichIntentRefusalModel.Prepared prepared = teacher.prepare(text, probability, margin);
    TinyDecisionModel.Decision before = prepared.decision;
    validateTeacher(before);
    if (before.status == TinyDecisionModel.Status.ABSTAIN) return before;
    int domain = domain(before.choice);
    if (reject[domain] == DISABLED && change[domain] == DISABLED) return before;
    return withFeatures(before, prepared.features, domain, probability, margin);
  }

  // Controlled host ablation of the same policy and weights; normal inference shares the teacher
  // encoding.
  TinyDecisionModel.Decision classifyUnshared(String text, double probability, double margin) {
    TinyDecisionModel.Decision before = teacher.classify(text, probability, margin);
    validateTeacher(before);
    if (before.status == TinyDecisionModel.Status.ABSTAIN) return before;
    int domain = domain(before.choice);
    if (reject[domain] == DISABLED && change[domain] == DISABLED) return before;
    return withFeatures(before, encoder.encode(text), domain, probability, margin);
  }

  private TinyDecisionModel.Decision withFeatures(
      TinyDecisionModel.Decision before,
      float[] features,
      int domain,
      double probability,
      double margin) {
    TinyDecisionModel.Decision candidate = expert.decide(features, 0, 0);
    double score =
        support[domain] == DISABLED
                || !"__reject__".equals(candidate.choice)
                || candidate.probability < reject[domain]
            ? 0
            : teacher.domainSupport(features)[domain];
    TinyDecisionModel.Decision proposed =
        choose(
            before,
            candidate,
            reject[domain],
            change[domain],
            score,
            support[domain],
            probability,
            margin);
    if (proposed == before || prototypes == null || prototypeCutoffs[domain] == DISABLED)
      return proposed;
    return prototypeGuard(
        before, proposed, prototypes.similarity(features, domain), prototypeCutoffs[domain]);
  }

  static TinyDecisionModel.Decision prototypeGuard(
      TinyDecisionModel.Decision before,
      TinyDecisionModel.Decision proposed,
      float similarity,
      double cutoff) {
    validateTeacher(before);
    if (proposed == null
        || !Float.isFinite(similarity)
        || similarity < 0
        || similarity > 1
        || !Double.isFinite(cutoff)
        || cutoff < 0
        || cutoff > 1 && cutoff != DISABLED)
      throw new IllegalArgumentException("Invalid prototype decision");
    return before.status == TinyDecisionModel.Status.ABSTAIN || similarity >= cutoff
        ? before
        : proposed;
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
    return choose(before, expert, reject, change, domainSupport, supportCutoff, .6, .15);
  }

  private static TinyDecisionModel.Decision choose(
      TinyDecisionModel.Decision before,
      TinyDecisionModel.Decision expert,
      double reject,
      double change,
      double domainSupport,
      double supportCutoff,
      double minimumProbability,
      double minimumMargin) {
    if (before == null
        || expert == null
        || !threshold(reject)
        || !threshold(change)
        || !threshold(supportCutoff)
        || !Double.isFinite(domainSupport)
        || domainSupport < 0
        || domainSupport > 1
        || !Double.isFinite(minimumProbability)
        || minimumProbability < 0
        || minimumProbability > 1
        || !Double.isFinite(minimumMargin)
        || minimumMargin < 0
        || minimumMargin > 1) throw new IllegalArgumentException("Invalid utility decision");
    validateTeacher(before);
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
    return expert.probability >= Math.max(change, minimumProbability)
            && expert.margin > Math.max(.15, minimumMargin)
        ? expert
        : before;
  }

  private static void validateTeacher(TinyDecisionModel.Decision decision) {
    if (decision == null || decision.probabilitiesCopy().length != 5)
      throw new IllegalArgumentException("Foreign teacher distribution");
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
