package com.omnidev.workspace.domain.neural;

/** Explicit learned rejection remains a refusal, never a tool or an authorization. */
public final class RejectingTextIntentModel {
    public static final String REJECT = "__reject__";
    private final TextIntentModel model;

    public RejectingTextIntentModel(TinyNeuralRuntime network, String[] labels, double temperature, String version) {
        int count = 0;
        if (labels != null) for (String label : labels) if (REJECT.equals(label)) count++;
        if (count != 1) throw new IllegalArgumentException("Exactly one rejection label is required");
        model = new TextIntentModel(network, labels, temperature, version);
    }

    public TinyDecisionModel.Decision classify(String text, double minimumProbability, double minimumMargin) {
        TinyDecisionModel.Decision decision = model.classify(text, minimumProbability, minimumMargin);
        return REJECT.equals(decision.choice) ? decision.asAbstention() : decision;
    }
}
