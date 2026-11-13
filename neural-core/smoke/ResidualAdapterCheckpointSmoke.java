package com.omnidev.workspace.domain.neural;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import com.omnidev.workspace.domain.neural.PublicRefusalCorpus.Row;

/** Actual exported/committed residuals and decisions, not a substitute model or mocked trainer. */
public final class ResidualAdapterCheckpointSmoke {
    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("TrainedResidualAdapter"); RichTextEncoder encoder = PublicRefusalExperiment.encoder();
        String fingerprint = (String) type.getField("VOCABULARY_SHA256").get(null); double gain = type.getField("GAIN").getDouble(null);
        if (!fingerprint.equals(encoder.fingerprint())) throw new AssertionError("Residual vocabulary pairing changed");
        TinyNeuralRuntime dense = (TinyNeuralRuntime) type.getMethod("model").invoke(null), sparse = dense.withSparseInput();
        if (dense.parameterCount() != 5125) throw new AssertionError("Residual size changed");
        BoundedResidualDecisionModel a = ResidualAdapterExperiment.model(dense, gain, false), b = ResidualAdapterExperiment.model(sparse, gain, true);
        RichIntentRefusalModel teacher = ResidualAdapterExperiment.teacher(true);
        List<Row> rows = ResidualAdapterExperiment.rows(); rows.addAll(PublicRefusalCorpus.old(BilingualRefusalCorpus.freshEnglish(Paths.get("neural-core/fixtures/rich-text/fresh-english.tsv"))));
        rows.addAll(PublicRefusalCorpus.old(BilingualRefusalCorpus.challenges(Paths.get("neural-core/fixtures/decision-challenges.tsv")))); rows.addAll(ResidualAdapterExperiment.fresh());
        List<String> expected = Files.readAllLines(Paths.get(args[1]), StandardCharsets.UTF_8);
        if (expected.size() != rows.size() + 3 || !expected.subList(0, 3).equals(Arrays.asList("vocabulary_sha256\t" + fingerprint, "gain\t" + gain, "status\tchoice\tprobabilities\tresidual"))) throw new AssertionError("Residual metadata/count drift");
        for (int i = 0; i < rows.size(); i++) {
            String text = rows.get(i).text; float[] x = encoder.encode(text), delta = dense.infer(x);
            if (!Arrays.equals(delta, sparse.infer(x))) throw new AssertionError("Residual dense/sparse drift");
            String[] ref = expected.get(i + 3).split("\t", -1); if (ref.length != 4) throw new AssertionError("Residual observation schema changed");
            TinyDecisionModel.Decision d = a.classify(text, 0.6, 0.15), s = b.classify(text, 0.6, 0.15), old = teacher.classify(text, 0.6, 0.15);
            if (!d.status.name().equals(ref[0]) || !(d.choice == null ? "-" : d.choice).equals(ref[1]) || d.status != s.status || !Objects.equals(d.choice, s.choice) || !Arrays.equals(d.probabilitiesCopy(), s.probabilitiesCopy())) throw new AssertionError("Residual typed/sparse decision drift");
            ResidualAdapterExperiment.priorRefusal(old, d);
            String[] scores = ref[2].split(",", -1), values = ref[3].split(",", -1); if (scores.length != 5 || values.length != 5) throw new AssertionError("Residual numeric schema changed");
            for (int j = 0; j < 5; j++) { double probability = Double.parseDouble(scores[j]), correction = Double.parseDouble(values[j]);
                if (!Double.isFinite(probability) || !Double.isFinite(correction) || Math.abs(probability - d.probabilitiesCopy()[j]) > 2e-6 || Math.abs(correction - delta[j]) > 2e-6) throw new AssertionError("Residual exported numeric drift"); }
        }
        System.out.println("Residual exported/committed numeric/metadata/decision/dense-sparse integrity passed on " + rows.size() + " observations.");
    }
}
