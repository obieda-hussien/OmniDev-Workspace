package com.omnidev.workspace.domain.neural;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import com.omnidev.workspace.domain.neural.PublicRefusalCorpus.Row;

/** Real six-class fitted/exported/committed checkpoint checks, including every numeric probability. */
public final class PublicDecisionCheckpointSmoke {
    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("TrainedPublicDecision"); RichTextEncoder encoder = PublicRefusalExperiment.encoder();
        String fingerprint = (String) type.getField("VOCABULARY_SHA256").get(null), corpus = (String) type.getField("CORPUS_SHA256").get(null);
        double temperature = type.getField("TEMPERATURE").getDouble(null), probability = type.getField("MINIMUM_PROBABILITY").getDouble(null), margin = type.getField("MINIMUM_MARGIN").getDouble(null);
        if (!fingerprint.equals(encoder.fingerprint()) || !corpus.equals(PublicRefusalCorpus.SHA256)) throw new AssertionError("Public decision pairing changed");
        TinyNeuralRuntime dense = (TinyNeuralRuntime) type.getMethod("model").invoke(null), sparse = dense.withSparseInput();
        if (dense.parameterCount() != 6150) throw new AssertionError("Public decision size changed");
        RichTextDecisionModel a = new RichTextDecisionModel(encoder, dense, temperature, RichTextEncoder.VERSION, fingerprint), b = new RichTextDecisionModel(encoder, sparse, temperature, RichTextEncoder.VERSION, fingerprint);
        List<Row> rows = PublicRefusalCorpus.old(BilingualRefusalCorpus.load(Paths.get("neural-core/fixtures/bilingual-refusal/corpus.tsv")));
        rows.addAll(PublicRefusalCorpus.load(Paths.get(args[0])));
        rows.addAll(PublicRefusalCorpus.old(BilingualRefusalCorpus.freshEnglish(Paths.get("neural-core/fixtures/rich-text/fresh-english.tsv"))));
        rows.addAll(PublicRefusalCorpus.old(BilingualRefusalCorpus.challenges(Paths.get("neural-core/fixtures/decision-challenges.tsv"))));
        List<String> expected = Files.readAllLines(Paths.get(args[1]), StandardCharsets.UTF_8);
        List<String> metadata = Arrays.asList("vocabulary_sha256\t" + fingerprint, "corpus_sha256\t" + corpus, "temperature\t" + temperature, "probability\t" + probability, "margin\t" + margin, "status\tchoice\tprobabilities");
        if (expected.size() != rows.size() + 6 || !expected.subList(0, 6).equals(metadata)) throw new AssertionError("Public decision metadata/count drift");
        for (int i = 0; i < rows.size(); i++) {
            String text = rows.get(i).text; float[] features = encoder.encode(text);
            if (!Arrays.equals(dense.infer(features), sparse.infer(features))) throw new AssertionError("Public decision sparse logits changed");
            TinyDecisionModel.Decision d = a.classify(text, probability, margin), s = b.classify(text, probability, margin);
            String[] ref = expected.get(i + 6).split("\t", -1);
            if (ref.length != 3 || !d.status.toString().equals(ref[0]) || !(d.choice == null ? "-" : d.choice).equals(ref[1]) || d.status != s.status ||
                    !Objects.equals(d.choice, s.choice) || !Arrays.equals(d.probabilitiesCopy(), s.probabilitiesCopy()) || "__reject__".equals(d.choice)) throw new AssertionError("Public decision typed/refusal drift");
            String[] values = ref[2].split(",", -1); if (values.length != 6) throw new AssertionError("Decision score schema changed");
            double[] scores = d.probabilitiesCopy(); for (int j = 0; j < 6; j++) { double score = Double.parseDouble(values[j]); if (!Double.isFinite(score) || Math.abs(scores[j] - score) > 2e-6) throw new AssertionError("Public decision numeric export drift"); }
        }
        Path damaged = Files.createTempFile("omni-public-tamper", ".tsv");
        try {
            Files.write(damaged, "changed source".getBytes(StandardCharsets.UTF_8));
            try { PublicRefusalCorpus.load(damaged); throw new AssertionError("Changed data accepted"); } catch (IllegalArgumentException expectedFailure) { }
        } finally { Files.deleteIfExists(damaged); }
        System.out.println("Public six-class metadata/numeric/decision/dense-sparse parity passed on " + rows.size() + " observations.");
    }
}
