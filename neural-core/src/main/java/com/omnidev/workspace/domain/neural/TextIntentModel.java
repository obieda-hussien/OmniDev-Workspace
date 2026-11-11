package com.omnidev.workspace.domain.neural;

/** Experimental text-to-choice adapter. No catalog access, tool dispatch or permission decisions. */
public final class TextIntentModel {
    private final TinyDecisionModel decision;

    public TextIntentModel(TinyNeuralRuntime network, String[] labels, double temperature, String encoderVersion) {
        if (!BilingualTextEncoder.VERSION.equals(encoderVersion) || network == null ||
                network.inputSize() != BilingualTextEncoder.DIMENSIONS) {
            throw new IllegalArgumentException("Text checkpoint feature contract mismatch");
        }
        decision = new TinyDecisionModel(network, labels, temperature);
    }

    public TinyDecisionModel.Decision classify(String text, double minimumProbability, double minimumMargin) {
        float[] features = BilingualTextEncoder.encode(text);
        boolean signal = false;
        for (float value : features) if (value != 0) { signal = true; break; }
        // Even a highly biased checkpoint must abstain when no lexical features exist.
        TinyDecisionModel.Decision result = decision.decide(features, minimumProbability, minimumMargin);
        return signal ? result : decision.decide(features, 1, 1);
    }
}
