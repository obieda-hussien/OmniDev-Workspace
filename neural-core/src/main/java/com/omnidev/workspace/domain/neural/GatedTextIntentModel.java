package com.omnidev.workspace.domain.neural;

/** Frozen domain scores plus a separately trained refusal head. Neither head authorizes execution. */
public final class GatedTextIntentModel {
    private final TinyDecisionModel domain, refusal;
    private final double refusalThreshold;

    public GatedTextIntentModel(TinyNeuralRuntime domainNetwork, String[] labels, double domainTemperature,
                                TinyNeuralRuntime refusalNetwork, double refusalTemperature,
                                double refusalThreshold, String encoderVersion) {
        if (!BilingualTextEncoder.VERSION.equals(encoderVersion) || domainNetwork == null || refusalNetwork == null ||
                domainNetwork.inputSize() != BilingualTextEncoder.DIMENSIONS ||
                refusalNetwork.inputSize() != BilingualTextEncoder.DIMENSIONS || refusalNetwork.outputSize() != 2 ||
                !Double.isFinite(refusalThreshold) || refusalThreshold < 0.5 || refusalThreshold > 1) {
            throw new IllegalArgumentException("Invalid gated text checkpoint contract");
        }
        domain = new TinyDecisionModel(domainNetwork, labels, domainTemperature);
        refusal = new TinyDecisionModel(refusalNetwork, new String[] {"supported", "refuse"}, refusalTemperature);
        this.refusalThreshold = refusalThreshold;
    }

    public TinyDecisionModel.Decision classify(String text, double minimumProbability, double minimumMargin) {
        float[] features = BilingualTextEncoder.encode(text);
        boolean signal = false;
        for (float value : features) if (value != 0) { signal = true; break; }
        TinyDecisionModel.Decision original = domain.decide(features, minimumProbability, minimumMargin);
        if (!signal) return original.asAbstention();
        // Never upgrade the original head's refusal or change its label/probability distribution.
        if (original.status == TinyDecisionModel.Status.ABSTAIN) return original;
        double reject = refusal.decide(features, 0, 0).probabilitiesCopy()[1];
        return reject >= refusalThreshold ? original.asAbstention() : original;
    }
}
