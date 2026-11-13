package com.omnidev.workspace.domain.neural;

/** Frozen domain scores plus a separately trained refusal head. Neither head authorizes execution. */
public final class GatedTextIntentModel {
    private final TinyDecisionModel domain;
    private final RefusalHead[] refusalHeads;

    /** At most two immutable refusal heads; each may only veto a frozen original choice. */
    public static final class RefusalHead {
        private final TinyDecisionModel model;
        private final double threshold;
        public RefusalHead(TinyNeuralRuntime network, double temperature, double threshold) {
            if (network == null || network.inputSize() != BilingualTextEncoder.DIMENSIONS || network.outputSize() != 2 ||
                    !Double.isFinite(threshold) || threshold < 0.5 || threshold > 1) throw new IllegalArgumentException("Invalid refusal head");
            model = new TinyDecisionModel(network, new String[] {"supported", "refuse"}, temperature);
            this.threshold = threshold;
        }
    }

    public GatedTextIntentModel(TinyNeuralRuntime domainNetwork, String[] labels, double domainTemperature,
                                TinyNeuralRuntime refusalNetwork, double refusalTemperature,
                                double refusalThreshold, String encoderVersion) {
        this(domainNetwork, labels, domainTemperature, encoderVersion,
                new RefusalHead(refusalNetwork, refusalTemperature, refusalThreshold));
    }

    public GatedTextIntentModel(TinyNeuralRuntime domainNetwork, String[] labels, double domainTemperature,
                                String encoderVersion, RefusalHead... heads) {
        if (!BilingualTextEncoder.VERSION.equals(encoderVersion) || domainNetwork == null ||
                domainNetwork.inputSize() != BilingualTextEncoder.DIMENSIONS || heads == null || heads.length < 1 || heads.length > 2) {
            throw new IllegalArgumentException("Invalid gated text checkpoint contract");
        }
        refusalHeads = heads.clone();
        for (RefusalHead head : refusalHeads) if (head == null) throw new IllegalArgumentException("Null refusal head");
        domain = new TinyDecisionModel(domainNetwork, labels, domainTemperature);
    }

    public TinyDecisionModel.Decision classify(String text, double minimumProbability, double minimumMargin) {
        float[] features = BilingualTextEncoder.encode(text);
        boolean signal = false;
        for (float value : features) if (value != 0) { signal = true; break; }
        TinyDecisionModel.Decision original = domain.decide(features, minimumProbability, minimumMargin);
        if (!signal) return original.asAbstention();
        // Never upgrade the original head's refusal or change its label/probability distribution.
        if (original.status == TinyDecisionModel.Status.ABSTAIN) return original;
        for (RefusalHead head : refusalHeads) {
            if (head.model.decide(features, 0, 0).probabilitiesCopy()[1] >= head.threshold) return original.asAbstention();
        }
        return original;
    }
}
