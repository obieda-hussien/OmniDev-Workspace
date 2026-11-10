package com.omnidev.workspace.domain.neural;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Shadow-only proposal ranker, not an authorization or execution engine.
 * Callers MUST pass only registry-validated, policy-eligible tool candidates.
 * Returns precisely those same candidate objects in score order.
 */
public final class NeuralToolRanker {
    private NeuralToolRanker() { }

    public static final int FEATURE_COUNT = 6;
    public static final int MAX_CANDIDATES = 128;

    public static final class Candidate {
        public final String registeredName;
        private final float[] features;
        /**
         * Features: objective match, active-step relevance, verification relevance,
         * recent verified success, latency desirability, bias. All normalized 0..1.
         */
        public Candidate(String registeredName, float[] features) {
            if (registeredName == null || registeredName.isEmpty() ||
                    registeredName.length() > 160 || features == null ||
                    features.length != FEATURE_COUNT) {
                throw new IllegalArgumentException("Invalid candidate");
            }
            for (float x : features) {
                if (!Float.isFinite(x) || x < 0f || x > 1f) {
                    throw new IllegalArgumentException("Feature out of range");
                }
            }
            this.registeredName = registeredName;
            this.features = features.clone();
        }
    }

    public static final class Ranked {
        public final Candidate candidate;
        public final double confidence;
        private Ranked(Candidate candidate, double confidence) {
            this.candidate = candidate;
            this.confidence = confidence;
        }
    }

    public static List<Ranked> proposals(List<Candidate> eligible, TinyNeuralRuntime model) {
        if (eligible == null || eligible.size() > MAX_CANDIDATES || model == null ||
                model.inputSize() != FEATURE_COUNT || model.outputSize() != 1) {
            throw new IllegalArgumentException("Invalid tool ranking request");
        }
        Set<String> names = new HashSet<>();
        List<Ranked> ranked = new ArrayList<>();
        for (Candidate c : eligible) {
            if (c == null || !names.add(c.registeredName)) {
                throw new IllegalArgumentException("Duplicate or missing candidate");
            }
            double logit = model.infer(c.features)[0];
            double confidence = logit >= 0 ? 1.0 / (1.0 + Math.exp(-logit)) :
                    Math.exp(logit) / (1.0 + Math.exp(logit));
            ranked.add(new Ranked(c, confidence));
        }
        ranked.sort(Comparator.comparingDouble((Ranked r) -> r.confidence).reversed()
                .thenComparing(r -> r.candidate.registeredName));
        return Collections.unmodifiableList(ranked);
    }
}
