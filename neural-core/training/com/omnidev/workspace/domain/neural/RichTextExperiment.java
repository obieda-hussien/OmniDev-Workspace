package com.omnidev.workspace.domain.neural;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import com.omnidev.workspace.domain.neural.BilingualRefusalCorpus.Row;

/** Train-only fitted representation, fused inference, zero development choice loss, then fresh evaluation. */
public final class RichTextExperiment {
    private static volatile double sink;
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected corpus, fresh evaluation and output directory");
        List<Row> rows = BilingualRefusalCorpus.load(Paths.get(args[0]));
        List<Row> train = BilingualRefusalCorpus.split(rows, "train"), dev = BilingualRefusalCorpus.split(rows, "development"), cal = BilingualRefusalCorpus.split(rows, "calibration");
        RichTextEncoder encoder = NgramVocabularyTrainer.fit(train);
        Map<Row, float[]> encoded = new IdentityHashMap<>();
        for (Row row : rows) if (Arrays.asList("train", "development", "calibration").contains(row.split)) encoded.put(row, encoder.encode(row.text));
        OfflineDecisionTrainer.Model[] heads = new OfflineDecisionTrainer.Model[5]; double[] temperatures = new double[5];
        StringBuilder details = new StringBuilder("[");
        for (int domain = 0; domain < 5; domain++) {
            Map<String, List<OfflineDecisionTrainer.Example>> fitting = MultiIntentExperiment.examples(train, domain, encoded);
            Map<String, List<OfflineDecisionTrainer.Example>> validation = MultiIntentExperiment.examples(dev, domain, encoded);
            Map<String, List<OfflineDecisionTrainer.Example>> calibration = MultiIntentExperiment.examples(cal, domain, encoded);
            OfflineDecisionTrainer.requireDisjointGroups(MultiIntentExperiment.flatten(fitting), MultiIntentExperiment.flatten(validation), MultiIntentExperiment.flatten(calibration));
            OfflineDecisionTrainer.TrainingResult best = null; long seedChoice = 0;
            for (long seed : new long[] {42, 97}) {
                OfflineDecisionTrainer.TrainingResult candidate = OfflineDecisionTrainer.fitEarlyStopped(MultiIntentExperiment.balance(fitting), 2, 0, 200, seed, new ArrayList<>(validation.values()));
                System.err.printf(Locale.ROOT, "Rich domain=%d seed=%d epoch=%d development_macro_nll=%.6f%n", domain, seed, candidate.bestEpoch, candidate.validationLoss);
                if (best == null || candidate.validationLoss < best.validationLoss) { best = candidate; seedChoice = seed; }
            }
            heads[domain] = best.model; double bestCal = Double.POSITIVE_INFINITY; temperatures[domain] = 1;
            for (double t : new double[] {0.5, 0.75, 1, 1.5, 2, 3, 5}) {
                double loss = 0; for (List<OfflineDecisionTrainer.Example> stratum : calibration.values()) loss += OfflineDecisionTrainer.logLoss(best.model, stratum, t);
                loss /= calibration.size(); if (loss < bestCal) { bestCal = loss; temperatures[domain] = t; }
            }
            if (domain > 0) details.append(',');
            details.append(String.format(Locale.ROOT, "{\"domain\":%d,\"seed\":%d,\"epoch\":%d,\"development_macro_nll\":%.9f,\"temperature\":%s}", domain, seedChoice, best.bestEpoch, best.validationLoss, temperatures[domain]));
        }
        details.append(']');
        LinearPresenceFusion.Result fusion = LinearPresenceFusion.fuse(heads, temperatures);
        TinyNeuralRuntime fused = fusion.runtime().withSparseInput();
        TinyDecisionModel[] independent = new TinyDecisionModel[5];
        for (int i = 0; i < 5; i++) independent[i] = new TinyDecisionModel(heads[i].runtime().withSparseInput(), new String[] {"absent", "present"}, temperatures[i]);
        double maxError = verifyFusion(encoded.values(), fused, independent);
        Class<?> old = Class.forName("TrainedTextIntentCheckpoint"), gate = Class.forName("TrainedRefusalGateCheckpoint"), previous = Class.forName("TrainedMultiIntentManifest");
        GatedTextIntentModel prior = new GatedTextIntentModel(((TinyNeuralRuntime) old.getMethod("model").invoke(null)).withSparseInput(), (String[]) old.getMethod("labels").invoke(null),
                old.getField("TEMPERATURE").getDouble(null), ((TinyNeuralRuntime) gate.getMethod("model").invoke(null)).withSparseInput(),
                gate.getField("TEMPERATURE").getDouble(null), gate.getField("CUTOFF").getDouble(null), BilingualTextEncoder.VERSION);
        MultiIntentRefusalModel previousPresence = new MultiIntentRefusalModel(prior, (TinyNeuralRuntime[]) previous.getMethod("models").invoke(null),
                (double[]) previous.getMethod("temperatures").invoke(null), previous.getField("THRESHOLD").getDouble(null), previous.getField("REQUIRE_MATCH").getBoolean(null), BilingualTextEncoder.VERSION);
        double threshold = Double.NaN, bestRecall = -1; boolean requireMatch = false;
        for (boolean match : new boolean[] {false, true}) for (double t : new double[] {0.25, 0.4, 0.5, 0.6, 0.75, 0.9, 0.95, 0.99, 1}) {
            RichIntentRefusalModel model = new RichIntentRefusalModel(prior, encoder, fused, t, match, RichTextEncoder.VERSION, encoder.fingerprint());
            double recall = development(dev, prior, model);
            if (Double.isFinite(recall) && recall > bestRecall) { threshold = t; requireMatch = match; bestRecall = recall; }
        }
        if (!Double.isFinite(threshold)) throw new IllegalStateException("No zero-development-loss configuration; do not promote a candidate");
        RichIntentRefusalModel selected = new RichIntentRefusalModel(prior, encoder, fused, threshold, requireMatch, RichTextEncoder.VERSION, encoder.fingerprint());
        Path output = Paths.get(args[2]); Files.createDirectories(output);
        Files.write(output.resolve("TrainedRichPresence.java"), fusion.javaSource(encoder.fingerprint()).getBytes(StandardCharsets.UTF_8));
        exportVocabulary(output.resolve("TrainedRichVocabulary.java"), encoder, threshold, requireMatch);
        // Only now load the additional test file or compute any observed regression score.
        List<Row> fresh = BilingualRefusalCorpus.freshEnglish(Paths.get(args[1]));
        requireFresh(rows, fresh);
        StringBuilder report = new StringBuilder("{\"production_eligible\":false,\"old_evaluation_status\":\"observed_regressions\",\"fresh_evaluation\":\"new_english_utterances_from_existing_topics\",")
                .append("\"encoder\":\"").append(RichTextEncoder.VERSION).append("\",\"vocabulary_size\":").append(encoder.vocabularySize())
                .append(",\"input_dimensions\":1024,\"fused_parameters\":5125,\"unfused_parameters\":10250,\"heads\":").append(details)
                .append(",\"threshold\":").append(threshold).append(",\"require_match\":").append(requireMatch)
                .append(",\"maximum_fusion_probability_difference\":").append(maxError).append(",\"evaluations\":{");
        Map<String, Function<String, TinyDecisionModel.Decision>> models = new LinkedHashMap<>();
        models.put("prior", text -> prior.classify(text, 0.6, 0.15));
        models.put("previous_presence", text -> previousPresence.classify(text, 0.6, 0.15));
        models.put("rich_fused", text -> selected.classify(text, 0.6, 0.15));
        evaluate(report, "development", dev, models);
        report.append(','); evaluate(report, "fresh_english", fresh, models);
        for (String split : new String[] {"test", "regression"}) { report.append(','); evaluate(report, split, BilingualRefusalCorpus.split(rows, split), models); }
        for (String kind : new String[] {"original", "composed_cross", "composed_same", "unseen_topic", "prior_clinc", "additional_clinc"}) {
            List<Row> subset = new ArrayList<>(); for (Row row : rows) if ((row.split.equals("test") || row.split.equals("regression")) && row.kind.equals(kind)) subset.add(row);
            report.append(','); evaluate(report, kind, subset, models);
        }
        report.append(','); evaluate(report, "old_challenge", BilingualRefusalCorpus.challenges(Paths.get("neural-core/fixtures/decision-challenges.tsv")), models);
        List<float[]> probes = new ArrayList<>();
        for (Row row : rows) probes.add(encoder.encode(row.text)); for (Row row : fresh) probes.add(encoder.encode(row.text));
        maxError = verifyFusion(probes, fused, independent);
        List<String> numeric = new ArrayList<>();
        numeric.add("vocabulary_sha256\t" + encoder.fingerprint()); numeric.add("threshold\t" + threshold); numeric.add("require_match\t" + requireMatch); numeric.add("id\tscores");
        for (int i = 0; i < probes.size(); i++) {
            float[] scores = fused.infer(probes.get(i)); StringBuilder line = new StringBuilder(i + "\t");
            for (int j = 0; j < 5; j++) { if (j > 0) line.append(','); line.append(scores[j]); }
            numeric.add(line.toString());
        }
        Files.write(output.resolve("expected-scores.tsv"), numeric, StandardCharsets.UTF_8);
        List<String> observations = new ArrayList<>(); observations.add("status\tchoice");
        for (Row row : rows) observations.add(observation(selected.classify(row.text, 0.6, 0.15)));
        for (Row row : fresh) observations.add(observation(selected.classify(row.text, 0.6, 0.15)));
        Files.write(output.resolve("expected.tsv"), observations, StandardCharsets.UTF_8);
        final double cutoff = threshold; final boolean strict = requireMatch;
        Function<String, TinyDecisionModel.Decision> unfused = text -> {
            String normalized = BilingualTextEncoder.normalize(text);
            float[] base = BilingualTextEncoder.encodeNormalized(normalized); TinyDecisionModel.Decision before = prior.classifyFeatures(base, 0.6, 0.15);
            if (before.status == TinyDecisionModel.Status.ABSTAIN) return before;
            float[] features = encoder.extendNormalized(normalized, base), probabilities = new float[5];
            for (int i = 0; i < 5; i++) probabilities[i] = (float) independent[i].decide(features, 0, 0).probabilitiesCopy()[1];
            return RichIntentRefusalModel.refine(before, probabilities, cutoff, strict);
        };
        for (Row row : rows) if (!observation(unfused.apply(row.text)).equals(observation(selected.classify(row.text, 0.6, 0.15)))) throw new AssertionError("Fusion changed a typed decision");
        for (Row row : fresh) if (!observation(unfused.apply(row.text)).equals(observation(selected.classify(row.text, 0.6, 0.15)))) throw new AssertionError("Fusion changed fresh decision");
        report.append("},\"all_observation_fusion_max_difference\":").append(maxError).append(",\"host_benchmark\":");
        benchmark(report, train, models, unfused);
        report.append("}\n"); Files.write(output.resolve("report.json"), report.toString().getBytes(StandardCharsets.UTF_8)); System.out.print(report);
    }
    private static void requireFresh(List<Row> old, List<Row> fresh) {
        Set<String> seen = new HashSet<>();
        for (Row row : old) seen.add(BilingualTextEncoder.normalize(row.text).replaceAll("[^\\p{L}\\p{N}_]+", " ").trim());
        for (Row row : fresh) if (!seen.add(BilingualTextEncoder.normalize(row.text).replaceAll("[^\\p{L}\\p{N}_]+", " ").trim())) throw new IllegalArgumentException("Fresh text contamination");
    }
    private static String observation(TinyDecisionModel.Decision d) { return d.status + "\t" + (d.choice == null ? "-" : d.choice); }
    private static double verifyFusion(Collection<float[]> features, TinyNeuralRuntime fused, TinyDecisionModel[] heads) {
        double maximum = 0;
        for (float[] x : features) { float[] actual = fused.infer(x);
            for (int i = 0; i < 5; i++) { double expected = heads[i].decide(x, 0, 0).probabilitiesCopy()[1];
                maximum = Math.max(maximum, Math.abs(expected - actual[i])); if (Math.abs(expected - actual[i]) > 2e-6) throw new AssertionError("Fusion probability drift"); }
        }
        return maximum;
    }
    private static double development(List<Row> rows, GatedTextIntentModel prior, RichIntentRefusalModel candidate) {
        Map<String, int[]> strata = new TreeMap<>();
        for (Row row : rows) {
            TinyDecisionModel.Decision before = prior.classify(row.text, 0.6, 0.15), after = candidate.classify(row.text, 0.6, 0.15);
            if (row.label == 0 && before.status == TinyDecisionModel.Status.CHOICE && after.status != TinyDecisionModel.Status.CHOICE) return Double.NaN;
            if (row.label == 1) { int[] n = strata.computeIfAbsent(row.kind + ":" + row.language, key -> new int[2]); n[0]++; if (after.status == TinyDecisionModel.Status.ABSTAIN) n[1]++; }
        }
        double recall = 0; for (int[] n : strata.values()) recall += (double) n[1] / n[0]; return recall / strata.size();
    }
    private static void evaluate(StringBuilder out, String name, List<Row> rows, Map<String, Function<String, TinyDecisionModel.Decision>> models) {
        int known = 0; for (Row row : rows) if (row.label == 0) known++;
        out.append('"').append(name).append("\":{\"known\":").append(known).append(",\"required_refusals\":").append(rows.size() - known);
        for (Map.Entry<String, Function<String, TinyDecisionModel.Decision>> model : models.entrySet()) {
            int accepted = 0, correct = 0, knownAccepted = 0, refused = 0;
            for (Row row : rows) {
                TinyDecisionModel.Decision d = model.getValue().apply(row.text), before = models.get("prior").apply(row.text);
                if (!Arrays.equals(d.probabilitiesCopy(), before.probabilitiesCopy()) || d.status == TinyDecisionModel.Status.CHOICE && !Objects.equals(d.choice, before.choice)) throw new AssertionError("Changed prior proposal");
                if (d.status == TinyDecisionModel.Status.CHOICE) { accepted++; if (row.label == 0) knownAccepted++; if (Objects.equals(d.choice, row.expectedDomain)) correct++; }
                else if (row.label == 1) refused++;
            }
            out.append(String.format(Locale.ROOT, ",\"%s\":{\"accepted\":%d,\"accepted_correct\":%d,\"accepted_errors\":%d,\"known_accepted\":%d,\"refused\":%d}", model.getKey(), accepted, correct, accepted - correct, knownAccepted, refused));
        }
        out.append('}');
    }
    private static void exportVocabulary(Path path, RichTextEncoder encoder, double threshold, boolean match) throws Exception {
        StringBuilder out = new StringBuilder("import com.omnidev.workspace.domain.neural.*;\npublic final class TrainedRichVocabulary {\n")
                .append("public static final String VERSION=\"").append(RichTextEncoder.VERSION).append("\";\n")
                .append("public static final String CORPUS_SHA256=\"").append(BilingualRefusalCorpus.SHA256).append("\";\n")
                .append("public static final double THRESHOLD=").append(threshold).append(";\npublic static final boolean REQUIRE_MATCH=").append(match).append(";\n")
                .append("public static RichTextEncoder encoder(){return new RichTextEncoder(words(),idf(),VERSION);}\nprivate static String[] words(){return new String[]{");
        String[] words = encoder.vocabularyCopy();
        for (int i = 0; i < words.length; i++) { if (i > 0) out.append(','); out.append('"').append(words[i]).append('"'); }
        out.append("};}\n"); OfflineDecisionTrainer.Model.appendArrayFactory(out, "idf", encoder.idfCopy());
        Files.write(path, out.append("}\n").toString().getBytes(StandardCharsets.UTF_8));
    }
    private static void benchmark(StringBuilder out, List<Row> train, Map<String, Function<String, TinyDecisionModel.Decision>> models, Function<String, TinyDecisionModel.Decision> unfused) {
        List<Function<String, TinyDecisionModel.Decision>> modes = new ArrayList<>(models.values()); modes.add(unfused);
        List<Row> work = new ArrayList<>();
        for (String language : new String[] {"ar", "en", "mixed"}) { int count = 0; for (Row row : train) if (row.language.equals(language) && count++ < 64) work.add(row); }
        for (Function<String, TinyDecisionModel.Decision> mode : modes) for (int i = 0; i < 512; i++) consume(mode.apply(work.get(i % work.size()).text));
        long[][] samples = new long[4][8];
        for (int round = 0; round < 8; round++) for (int position = 0; position < 4; position++) {
            int mode = (round + position) % 4; long start = System.nanoTime();
            for (Row row : work) consume(modes.get(mode).apply(row.text));
            samples[mode][round] = (System.nanoTime() - start) / work.size();
        }
        String[] names = {"prior", "previous_presence", "rich_fused", "rich_unfused"};
        out.append("{\"phone_measurement\":false,\"encoding_included\":true,\"calls_per_batch\":").append(work.size()).append(",\"language_counts\":{\"ar\":64,\"en\":64,\"mixed\":64},\"batches\":8");
        for (int i = 0; i < 4; i++) { out.append(",\"").append(names[i]).append("\":{\"batch_average_ns\":[");
            for (int j = 0; j < 8; j++) { if (j > 0) out.append(','); out.append(samples[i][j]); }
            Arrays.sort(samples[i]); out.append("],\"median_batch_average_ns\":").append((samples[i][3] + samples[i][4]) / 2).append('}'); }
        out.append('}');
    }
    private static void consume(TinyDecisionModel.Decision d) { double sum = 0; for (double p : d.probabilitiesCopy()) sum += p * p; sink = sum; }
}
