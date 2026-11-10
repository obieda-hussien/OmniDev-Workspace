package com.omnidev.workspace.domain.neural;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Optional offline experiment. Rescores existing evidence ONLY: never introduces
 * a document, creates a quote, changes offsets or grants a capability.
 * No model weights are bundled and no call to this class is made by the live agent.
 */
public final class NeuralEvidenceReranker {
    private static final Pattern TOKENS = Pattern.compile("[\\p{L}\\p{N}_]+");
    private NeuralEvidenceReranker() { }

    public static final class Ranked {
        public final OmniRamLite.Hit evidence;
        public final double score;
        private Ranked(OmniRamLite.Hit evidence, double score) {
            this.evidence = evidence;
            this.score = score;
        }
    }

    /** Five features: lexical score, query overlap, relative quote length, offset, bias. */
    public static List<Ranked> rank(String query, List<OmniRamLite.Hit> hits,
                                    TinyNeuralRuntime model, double influence) {
        if (query == null || query.length() > 2048 || hits == null || hits.size() > 1024 ||
                model == null || model.inputSize() != 5 || model.outputSize() != 1 ||
                !Double.isFinite(influence) || influence < 0.0 || influence > 0.5) {
            throw new IllegalArgumentException("Invalid reranking request");
        }
        Set<String> terms = terms(query);
        List<Ranked> scored = new ArrayList<>();
        for (OmniRamLite.Hit h : hits) {
            if (h == null || h.documentId == null || h.quote == null ||
                    h.startOffset < 0 || h.endOffset < h.startOffset ||
                    !Double.isFinite(h.score) || h.score < 0) {
                throw new IllegalArgumentException("Untrusted evidence");
            }
            Set<String> found = terms(h.quote);
            int matched = 0;
            for (String t : terms) if (found.contains(t)) matched++;
            float[] features = new float[] {
                    (float) Math.min(1.0, Math.log1p(h.score) / 5.0),
                    terms.isEmpty() ? 0f : (float) matched / terms.size(),
                    (float) Math.min(1.0, h.quote.length() / 900.0),
                    (float) Math.min(1.0, h.startOffset / 100000.0),
                    1f
            };
            double prediction = model.infer(features)[0];
            double sigmoid = prediction >= 0 ? 1.0 / (1.0 + Math.exp(-prediction)) :
                    Math.exp(prediction) / (1.0 + Math.exp(prediction));
            scored.add(new Ranked(h, h.score + influence * (sigmoid - 0.5)));
        }
        scored.sort(Comparator.comparingDouble((Ranked h) -> h.score).reversed()
                .thenComparing(h -> h.evidence.documentId)
                .thenComparingInt(h -> h.evidence.startOffset));
        return Collections.unmodifiableList(scored);
    }

    private static Set<String> terms(String value) {
        Set<String> result = new HashSet<>();
        Matcher m = TOKENS.matcher(value.toLowerCase(Locale.ROOT));
        while (m.find()) if (m.group().length() > 1) result.add(m.group());
        return result;
    }
}