package com.omnidev.workspace.domain.neural;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Exercise the compiled model/vocabulary, prior integrity and sparse path on actual corpus observations. */
public final class RichTextCheckpointSmoke {
    public static void main(String[] args) throws Exception {
        Class<?> vocabulary = Class.forName("TrainedRichVocabulary"), presence = Class.forName("TrainedRichPresence");
        Class<?> original = Class.forName("TrainedTextIntentCheckpoint"), gate = Class.forName("TrainedRefusalGateCheckpoint");
        RichTextEncoder encoder = (RichTextEncoder) vocabulary.getMethod("encoder").invoke(null);
        if (!RichTextEncoder.VERSION.equals(vocabulary.getField("VERSION").get(null)) ||
                !BilingualRefusalCorpus.SHA256.equals(vocabulary.getField("CORPUS_SHA256").get(null)) ||
                !encoder.fingerprint().equals(presence.getField("VOCABULARY_SHA256").get(null))) throw new AssertionError("Rich checkpoint pairing mismatch");
        GatedTextIntentModel prior = new GatedTextIntentModel(((TinyNeuralRuntime) original.getMethod("model").invoke(null)).withSparseInput(),
                (String[]) original.getMethod("labels").invoke(null), original.getField("TEMPERATURE").getDouble(null),
                ((TinyNeuralRuntime) gate.getMethod("model").invoke(null)).withSparseInput(), gate.getField("TEMPERATURE").getDouble(null),
                gate.getField("CUTOFF").getDouble(null), BilingualTextEncoder.VERSION);
        TinyNeuralRuntime dense = (TinyNeuralRuntime) presence.getMethod("model").invoke(null), sparse = dense.withSparseInput();
        double threshold = vocabulary.getField("THRESHOLD").getDouble(null); boolean match = vocabulary.getField("REQUIRE_MATCH").getBoolean(null);
        RichIntentRefusalModel a = new RichIntentRefusalModel(prior, encoder, dense, threshold, match, RichTextEncoder.VERSION, encoder.fingerprint());
        RichIntentRefusalModel b = new RichIntentRefusalModel(prior, encoder, sparse, threshold, match, RichTextEncoder.VERSION, encoder.fingerprint());
        List<BilingualRefusalCorpus.Row> rows = new ArrayList<>(BilingualRefusalCorpus.load(Paths.get(args[0])));
        rows.addAll(BilingualRefusalCorpus.freshEnglish(Paths.get(args[1])));
        List<String> expected = Files.readAllLines(Paths.get(args[2]), StandardCharsets.UTF_8);
        List<String> numeric = Files.readAllLines(Paths.get(args[3]), StandardCharsets.UTF_8);
        if (expected.size() != rows.size() + 1 || !expected.get(0).equals("status\tchoice")) throw new AssertionError("Rich observation count mismatch");
        if (numeric.size() != rows.size() + 4 || !numeric.get(0).equals("vocabulary_sha256\t" + encoder.fingerprint()) ||
                !numeric.get(1).equals("threshold\t" + threshold) || !numeric.get(2).equals("require_match\t" + match) || !numeric.get(3).equals("id\tscores")) throw new AssertionError("Rich observation metadata mismatch");
        for (int i = 0; i < rows.size(); i++) {
            String text = rows.get(i).text; float[] x = encoder.encode(text);
            if (!Arrays.equals(dense.infer(x), sparse.infer(x))) throw new AssertionError("Rich sparse logits mismatch");
            String[] reference = numeric.get(i + 4).split("\t", -1); float[] scores = dense.infer(x);
            if (reference.length != 2 || !reference[0].equals(Integer.toString(i))) throw new AssertionError("Rich numeric order mismatch");
            String[] values = reference[1].split(",", -1); if (values.length != 5) throw new AssertionError("Rich numeric schema mismatch");
            for (int j = 0; j < 5; j++) {
                double value = Double.parseDouble(values[j]);
                if (!Double.isFinite(value) || Math.abs(value - scores[j]) > 2e-6) throw new AssertionError("Rich trained/exported probability drift");
            }
            TinyDecisionModel.Decision before = prior.classify(text, 0.6, 0.15), after = a.classify(text, 0.6, 0.15), sparseDecision = b.classify(text, 0.6, 0.15);
            if (!(after.status + "\t" + (after.choice == null ? "-" : after.choice)).equals(expected.get(i + 1))) throw new AssertionError("Rich compiled decision mismatch");
            if (after.status != sparseDecision.status || !Objects.equals(after.choice, sparseDecision.choice) || !Arrays.equals(after.probabilitiesCopy(), sparseDecision.probabilitiesCopy())) throw new AssertionError("Rich sparse decision mismatch");
            if (!Arrays.equals(before.probabilitiesCopy(), after.probabilitiesCopy()) || after.status == TinyDecisionModel.Status.CHOICE && !Objects.equals(before.choice, after.choice) ||
                    before.status == TinyDecisionModel.Status.ABSTAIN && after.status != TinyDecisionModel.Status.ABSTAIN) throw new AssertionError("Rich model changed original proposal");
        }
        // Test the actual vocabulary fitting boundary, not a parallel parser.
        try { NgramVocabularyTrainer.fit(BilingualRefusalCorpus.split(rows, "development")); throw new AssertionError("Development vocabulary fitting accepted"); }
        catch (IllegalArgumentException expectedFailure) { }
        System.out.println("Rich compiled vocabulary/model/refusal-only/dense-sparse parity passed on " + rows.size() + " observations.");
    }
}
