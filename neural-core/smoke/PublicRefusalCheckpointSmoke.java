package com.omnidev.workspace.domain.neural;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import com.omnidev.workspace.domain.neural.PublicRefusalCorpus.Row;

/** Verify actual fitted/exported/committed probabilities, metadata, decisions and sparse inference. */
public final class PublicRefusalCheckpointSmoke {
    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("TrainedPublicRefusal"); RichTextEncoder encoder = PublicRefusalExperiment.encoder();
        String fingerprint = (String) type.getField("VOCABULARY_SHA256").get(null), corpus = (String) type.getField("CORPUS_SHA256").get(null);
        double cutoff = type.getField("CUTOFF").getDouble(null), temperature = type.getField("TEMPERATURE").getDouble(null);
        if (!fingerprint.equals(encoder.fingerprint()) || !corpus.equals(PublicRefusalCorpus.SHA256)) throw new AssertionError("Public checkpoint pairing mismatch");
        TinyNeuralRuntime dense = (TinyNeuralRuntime) type.getMethod("model").invoke(null), sparse = dense.withSparseInput();
        if (dense.parameterCount() != 1025) throw new AssertionError("Unbounded public checkpoint");
        RichIntentRefusalModel a = PublicRefusalExperiment.model(dense, cutoff, false), b = PublicRefusalExperiment.model(sparse, cutoff, true), prior = PublicRefusalExperiment.model(null, 1, true);
        List<Row> rows = PublicRefusalCorpus.old(BilingualRefusalCorpus.load(Paths.get("neural-core/fixtures/bilingual-refusal/corpus.tsv")));
        rows.addAll(PublicRefusalCorpus.load(Paths.get(args[0])));
        rows.addAll(PublicRefusalCorpus.old(BilingualRefusalCorpus.freshEnglish(Paths.get("neural-core/fixtures/rich-text/fresh-english.tsv"))));
        rows.addAll(PublicRefusalCorpus.old(BilingualRefusalCorpus.challenges(Paths.get("neural-core/fixtures/decision-challenges.tsv"))));
        List<String> expected = Files.readAllLines(Paths.get(args[1]), StandardCharsets.UTF_8);
        List<String> metadata = Arrays.asList("vocabulary_sha256\t" + fingerprint, "corpus_sha256\t" + corpus, "cutoff\t" + cutoff, "temperature\t" + temperature, "score\tstatus\tchoice");
        if (expected.size() != rows.size() + 5 || !expected.subList(0, 5).equals(metadata)) throw new AssertionError("Public export metadata/count mismatch");
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i); float[] x = encoder.encode(r.text);
            if (!Arrays.equals(dense.infer(x), sparse.infer(x))) throw new AssertionError("Public sparse probabilities mismatch");
            String[] ref = expected.get(i + 5).split("\t", -1); double score = Double.parseDouble(ref[0]);
            if (ref.length != 3 || !Double.isFinite(score) || Math.abs(dense.infer(x)[0] - score) > 2e-6) throw new AssertionError("Public trained/exported numerical drift");
            TinyDecisionModel.Decision d = a.classify(r.text, 0.6, 0.15), s = b.classify(r.text, 0.6, 0.15);
            if (!d.status.toString().equals(ref[1]) || !(d.choice == null ? "-" : d.choice).equals(ref[2]) || d.status != s.status ||
                    !Objects.equals(d.choice, s.choice) || !Arrays.equals(d.probabilitiesCopy(), s.probabilitiesCopy())) throw new AssertionError("Public typed/sparse decision drift");
            PublicRefusalExperiment.invariant(prior.classify(r.text, 0.6, 0.15), d);
        }
        System.out.println("Public refusal compiled metadata/numeric/decision/dense-sparse integrity passed on " + rows.size() + " inputs.");
    }
}
