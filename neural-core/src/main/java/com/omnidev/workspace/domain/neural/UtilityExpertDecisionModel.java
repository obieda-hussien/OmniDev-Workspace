package com.omnidev.workspace.domain.neural;

/** Domain-specific expert trust regions. Proposals never authorize execution or upgrade teacher refusals. */
public final class UtilityExpertDecisionModel {
    public static final double DISABLED = 1.1;
    private static final String[] LABELS = MultiIntentRefusalModel.labels();
    private final RichIntentRefusalModel teacher;
    private final RichTextEncoder encoder;
    private final TinyDecisionModel expert;
    private final double[] reject, change;

    public UtilityExpertDecisionModel(RichIntentRefusalModel teacher, RichTextEncoder encoder, TinyNeuralRuntime network, double temperature,
                                     double[] reject, double[] change, String fingerprint) {
        if (teacher == null || encoder == null || network == null || network.inputSize() != 1024 || network.outputSize() != 6 || network.parameterCount() > 20_000 ||
                !encoder.fingerprint().equals(fingerprint)) throw new IllegalArgumentException("Invalid utility expert schema");
        validate(reject); validate(change); this.reject = reject.clone(); this.change = change.clone();
        this.teacher = teacher; this.encoder = encoder;
        this.expert = new TinyDecisionModel(network, RichTextDecisionModel.labels(), temperature);
    }
    public TinyDecisionModel.Decision classify(String text, double probability, double margin) {
        TinyDecisionModel.Decision before = teacher.classify(text, probability, margin);
        if (before.status == TinyDecisionModel.Status.ABSTAIN) return before;
        int domain = domain(before.choice);
        if (reject[domain] == DISABLED && change[domain] == DISABLED) return before;
        return choose(before, expert.decide(encoder.encode(text), 0, 0), reject[domain], change[domain]);
    }
    static TinyDecisionModel.Decision choose(TinyDecisionModel.Decision before, TinyDecisionModel.Decision expert, double reject, double change) {
        if (before == null || expert == null || !threshold(reject) || !threshold(change)) throw new IllegalArgumentException("Invalid utility decision");
        if (before.status == TinyDecisionModel.Status.ABSTAIN) return before;
        domain(before.choice);
        if (expert.probabilitiesCopy().length != 6) throw new IllegalArgumentException("Foreign expert distribution");
        if (expert.status == TinyDecisionModel.Status.ABSTAIN) return before;
        if ("__reject__".equals(expert.choice)) return expert.probability >= reject ? before.asAbstention() : before;
        domain(expert.choice);
        if (expert.choice.equals(before.choice)) return before;
        return expert.probability >= change && expert.margin > 0.15 ? expert : before;
    }
    static int domain(String label) { for (int i = 0; i < LABELS.length; i++) if (LABELS[i].equals(label)) return i; throw new IllegalArgumentException("Foreign expert/teacher label"); }
    private static boolean threshold(double value) { return Double.isFinite(value) && (value >= 0.5 && value <= 1 || value == DISABLED); }
    private static void validate(double[] values) { if (values == null || values.length != 5) throw new IllegalArgumentException("Invalid expert policy shape"); for (double v : values) if (!threshold(v)) throw new IllegalArgumentException("Invalid expert threshold"); }
}
