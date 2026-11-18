package com.omnidev.workspace.domain.engine;

import com.omnidev.workspace.domain.neural.BilingualTextEncoder;
import com.omnidev.workspace.domain.neural.RichTextDecisionModel;
import com.omnidev.workspace.domain.neural.RichTextEncoder;
import com.omnidev.workspace.domain.neural.TinyDecisionModel;
import com.omnidev.workspace.domain.neural.TinyNeuralRuntime;
import com.omnidev.workspace.domain.neural.bundled.TrainedPublicDecision;
import com.omnidev.workspace.domain.neural.bundled.TrainedPublicRefusal;
import com.omnidev.workspace.domain.neural.bundled.TrainedRichVocabulary;

final class BundledDecisionModels {
    private BundledDecisionModels() {}

    private static final RichTextEncoder ENCODER = TrainedRichVocabulary.encoder();
    private static final TinyNeuralRuntime REFUSAL = TrainedPublicRefusal.model();
    private static final TinyDecisionModel DOMAIN = new TinyDecisionModel(
            TrainedPublicDecision.model(), RichTextDecisionModel.labels(), TrainedPublicDecision.TEMPERATURE);

    static {
        if (!ENCODER.fingerprint().equals(TrainedPublicDecision.VOCABULARY_SHA256)
                || !ENCODER.fingerprint().equals(TrainedPublicRefusal.VOCABULARY_SHA256)) {
            throw new IllegalStateException("Bundled decision vocabulary mismatch");
        }
    }

    static boolean supportsInspection(String input) {
        if (input == null || input.trim().isEmpty() || input.length() > BilingualTextEncoder.MAX_CHARS) return false;
        float[] features;
        try {
            features = ENCODER.encode(input);
        } catch (IllegalArgumentException expandedBeyondLimit) {
            return false;
        }
        if (REFUSAL.infer(features)[0] >= TrainedPublicRefusal.CUTOFF) return false;
        TinyDecisionModel.Decision decision = DOMAIN.decide(features,
                TrainedPublicDecision.MINIMUM_PROBABILITY, TrainedPublicDecision.MINIMUM_MARGIN);
        return "repository".equals(decision.choice) || "build".equals(decision.choice)
                || "device".equals(decision.choice);
    }
}
