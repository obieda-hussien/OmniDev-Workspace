package com.omnidev.workspace.domain.neural;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import com.omnidev.workspace.domain.neural.PublicRefusalCorpus.Row;

/** Two bounded fits on public parallel data; export first, then inspect previously unseen test scores. */
public final class PublicRefusalExperiment {
    private static volatile double sink;
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected public corpus and output directory");
        List<Row> old = PublicRefusalCorpus.old(BilingualRefusalCorpus.load(Paths.get("neural-core/fixtures/bilingual-refusal/corpus.tsv")));
        List<Row> publicRows = PublicRefusalCorpus.load(Paths.get(args[0])), combined = new ArrayList<>(old); combined.addAll(publicRows);
        RichTextEncoder encoder = encoder();
        List<Row> train = PublicRefusalCorpus.split(combined, "train"), dev = PublicRefusalCorpus.split(combined, "development"), cal = PublicRefusalCorpus.split(combined, "calibration");
        Map<Row, float[]> encoded = new IdentityHashMap<>();
        for (Row r : combined) if (Arrays.asList("train", "development", "calibration").contains(r.split)) encoded.put(r, encoder.encode(r.text));
        Map<String, List<OfflineDecisionTrainer.Example>> fitting = examples(train, encoded), validation = examples(dev, encoded), calibration = examples(cal, encoded);
        OfflineDecisionTrainer.requireDisjointGroups(flatten(fitting), flatten(validation), flatten(calibration),
                groupExamples(PublicRefusalCorpus.split(combined, "test")), groupExamples(PublicRefusalCorpus.split(combined, "regression")));
        int maximum = 0; for (List<OfflineDecisionTrainer.Example> g : fitting.values()) maximum = Math.max(maximum, g.size());
        List<OfflineDecisionTrainer.Example> balanced = new ArrayList<>();
        for (List<OfflineDecisionTrainer.Example> g : fitting.values()) for (int i = 0; i < maximum; i++) balanced.add(g.get(i % g.size()));
        OfflineDecisionTrainer.TrainingResult best = null; long bestSeed = 0; StringBuilder candidates = new StringBuilder("[");
        for (long seed : new long[] {42, 97}) {
            OfflineDecisionTrainer.TrainingResult fit = OfflineDecisionTrainer.fitEarlyStopped(balanced, 2, 0, 200, seed, new ArrayList<>(validation.values()));
            if (candidates.length() > 1) candidates.append(',');
            candidates.append(String.format(Locale.ROOT, "{\"seed\":%d,\"best_epoch\":%d,\"epochs_run\":%d,\"development_macro_nll\":%.9f}", seed, fit.bestEpoch, fit.epochsRun, fit.validationLoss));
            System.err.printf(Locale.ROOT, "Public refusal seed=%d epoch=%d development_macro_nll=%.6f%n", seed, fit.bestEpoch, fit.validationLoss);
            if (best == null || fit.validationLoss < best.validationLoss) { best = fit; bestSeed = seed; }
        }
        candidates.append(']'); double temperature = 1, calLoss = Double.POSITIVE_INFINITY;
        for (double t : new double[] {0.5, 0.75, 1, 1.5, 2, 3, 5}) {
            double loss = 0; for (List<OfflineDecisionTrainer.Example> g : calibration.values()) loss += OfflineDecisionTrainer.logLoss(best.model, g, t);
            loss /= calibration.size(); if (loss < calLoss) { calLoss = loss; temperature = t; }
        }
        TinyNeuralRuntime.Dense logOdds = best.model.binaryLinearLayer(temperature);
        TinyNeuralRuntime fused = new TinyNeuralRuntime(new TinyNeuralRuntime.Dense(1024, 1, logOdds.weightsCopy(), logOdds.biasCopy(), TinyNeuralRuntime.Activation.SIGMOID)).withSparseInput();
        RichIntentRefusalModel prior = model(null, 1, true); double cutoff = Double.NaN, bestRecall = -1;
        for (double threshold : new double[] {0.5, 0.6, 0.7, 0.8, 0.9, 0.95, 0.975, 0.99, 0.999, 1}) {
            Map<String, int[]> refusals = new TreeMap<>(); boolean feasible = true;
            for (Row r : dev) {
                TinyDecisionModel.Decision d = prior.classify(r.text, 0.6, 0.15);
                boolean refused = d.status == TinyDecisionModel.Status.ABSTAIN || fused.infer(encoded.get(r))[0] >= threshold;
                if (r.label == 0 && d.status == TinyDecisionModel.Status.CHOICE && refused) feasible = false;
                if (r.label == 1) { int[] n = refusals.computeIfAbsent((r.kind.startsWith("old_") ? "old" : "massive") + ":" + r.language, k -> new int[2]); n[0]++; if (refused) n[1]++; }
            }
            double recall = 0; for (int[] n : refusals.values()) recall += (double) n[1] / n[0]; recall /= refusals.size();
            if (feasible && recall >= bestRecall) { cutoff = threshold; bestRecall = recall; }
        }
        if (!Double.isFinite(cutoff)) throw new IllegalStateException("No zero-development-known-loss policy; no promotion");
        Path out = Paths.get(args[1]); Files.createDirectories(out);
        export(out.resolve("TrainedPublicRefusal.java"), logOdds, cutoff, temperature, encoder.fingerprint());
        // Selection and export are now complete. Test observations must not drive further fits or policy changes.
        List<Row> evaluation = new ArrayList<>(combined);
        evaluation.addAll(PublicRefusalCorpus.old(BilingualRefusalCorpus.freshEnglish(Paths.get("neural-core/fixtures/rich-text/fresh-english.tsv"))));
        evaluation.addAll(PublicRefusalCorpus.old(BilingualRefusalCorpus.challenges(Paths.get("neural-core/fixtures/decision-challenges.tsv"))));
        TinyDecisionModel binary = new TinyDecisionModel(best.model.runtime().withSparseInput(), new String[] {"supported", "refuse"}, temperature);
        double maximumDifference = 0;
        RichIntentRefusalModel selected = model(fused, cutoff, true);
        List<String> expected = new ArrayList<>(Arrays.asList("vocabulary_sha256\t" + encoder.fingerprint(), "corpus_sha256\t" + PublicRefusalCorpus.SHA256,
                "cutoff\t" + cutoff, "temperature\t" + temperature, "score\tstatus\tchoice"));
        for (Row r : evaluation) {
            float[] features = encoder.encode(r.text); float score = fused.infer(features)[0];
            maximumDifference = Math.max(maximumDifference, Math.abs(score - binary.decide(features, 0, 0).probabilitiesCopy()[1]));
            TinyDecisionModel.Decision d = selected.classify(r.text, 0.6, 0.15); invariant(prior.classify(r.text, 0.6, 0.15), d);
            expected.add(score + "\t" + d.status + "\t" + (d.choice == null ? "-" : d.choice));
        }
        if (maximumDifference > 2e-6) throw new AssertionError("Folded sigmoid parity changed");
        Files.write(out.resolve("expected.tsv"), expected, StandardCharsets.UTF_8);
        StringBuilder report = new StringBuilder("{\"production_eligible\":false,\"fresh_test\":\"public_parallel_ar-SA_en-US_two_supported_domains\",\"old_tests\":\"observed_regressions\",")
                .append("\"corpus_sha256\":\"").append(PublicRefusalCorpus.SHA256).append("\",\"candidates\":").append(candidates)
                .append(",\"selected_seed\":").append(bestSeed).append(",\"selected_epoch\":").append(best.bestEpoch)
                .append(",\"train_rows\":").append(train.size()).append(",\"balanced_train_rows\":").append(balanced.size())
                .append(",\"temperature\":").append(temperature).append(",\"cutoff\":").append(cutoff)
                .append(",\"extra_parameters\":1025,\"total_weight_parameters\":14493,\"vocabulary_sha256\":\"").append(encoder.fingerprint())
                .append("\",\"maximum_fold_probability_difference\":").append(maximumDifference).append(",\"evaluations\":{");
        boolean first = true;
        for (String language : new String[] {"ar", "en"}) for (String kind : new String[] {"all", "held_out_topic"}) {
            List<Row> subset = new ArrayList<>(); for (Row r : publicRows) if (r.split.equals("test") && r.language.equals(language) && (kind.equals("all") || r.kind.equals(kind))) subset.add(r);
            if (!first) report.append(','); first = false; evaluate(report, "massive_" + language + "_" + kind, subset, prior, selected);
        }
        for (String kind : new String[] {"all", "old_original", "old_composed_same", "old_composed_cross", "old_fresh_english", "old_old_challenge"}) {
            List<Row> subset = new ArrayList<>(); for (Row r : evaluation) if (r.kind.startsWith("old_") && Arrays.asList("test", "regression").contains(r.split) && (kind.equals("all") || r.kind.equals(kind))) subset.add(r);
            report.append(','); evaluate(report, "regression_" + kind, subset, prior, selected);
        }
        report.append("},\"host_timing\":"); benchmark(report, train, prior, selected); report.append("}\n");
        Files.write(out.resolve("report.json"), report.toString().getBytes(StandardCharsets.UTF_8)); System.out.print(report);
    }
    static RichTextEncoder encoder() throws Exception { return (RichTextEncoder) Class.forName("TrainedRichVocabulary").getMethod("encoder").invoke(null); }
    static RichIntentRefusalModel model(TinyNeuralRuntime refusal, double cutoff, boolean sparse) throws Exception {
        Class<?> domain = Class.forName("TrainedTextIntentCheckpoint"), gate = Class.forName("TrainedRefusalGateCheckpoint"), vocabulary = Class.forName("TrainedRichVocabulary"), presence = Class.forName("TrainedRichPresence");
        GatedTextIntentModel prior = new GatedTextIntentModel(runtime(domain, sparse), (String[]) domain.getMethod("labels").invoke(null), domain.getField("TEMPERATURE").getDouble(null),
                runtime(gate, sparse), gate.getField("TEMPERATURE").getDouble(null), gate.getField("CUTOFF").getDouble(null), BilingualTextEncoder.VERSION);
        return new RichIntentRefusalModel(prior, encoder(), runtime(presence, sparse), vocabulary.getField("THRESHOLD").getDouble(null),
                vocabulary.getField("REQUIRE_MATCH").getBoolean(null), RichTextEncoder.VERSION, (String) presence.getField("VOCABULARY_SHA256").get(null), refusal, cutoff);
    }
    private static TinyNeuralRuntime runtime(Class<?> type, boolean sparse) throws Exception { TinyNeuralRuntime r = (TinyNeuralRuntime) type.getMethod("model").invoke(null); return sparse ? r.withSparseInput() : r; }
    private static Map<String, List<OfflineDecisionTrainer.Example>> examples(List<Row> rows, Map<Row, float[]> features) {
        Map<String, List<OfflineDecisionTrainer.Example>> result = new TreeMap<>();
        for (Row r : rows) result.computeIfAbsent(r.language + ":" + r.label, k -> new ArrayList<>()).add(new OfflineDecisionTrainer.Example(r.group, features.get(r), r.label));
        if (result.size() != 6) throw new IllegalArgumentException("Missing public/old language-outcome stratum"); return result;
    }
    private static List<OfflineDecisionTrainer.Example> flatten(Map<String, List<OfflineDecisionTrainer.Example>> groups) {
        List<OfflineDecisionTrainer.Example> result = new ArrayList<>(); for (List<OfflineDecisionTrainer.Example> g : groups.values()) result.addAll(g); return result;
    }
    private static List<OfflineDecisionTrainer.Example> groupExamples(List<Row> rows) {
        List<OfflineDecisionTrainer.Example> result = new ArrayList<>(); for (Row r : rows) result.add(new OfflineDecisionTrainer.Example(r.group, new float[] {0}, r.label)); return result;
    }
    static void invariant(TinyDecisionModel.Decision before, TinyDecisionModel.Decision after) {
        if (!Arrays.equals(before.probabilitiesCopy(), after.probabilitiesCopy()) || before.status == TinyDecisionModel.Status.ABSTAIN && after.status != before.status ||
                after.status == TinyDecisionModel.Status.CHOICE && !Objects.equals(before.choice, after.choice)) throw new AssertionError("Refusal integrity changed");
    }
    private static void export(Path path, TinyNeuralRuntime.Dense layer, double cutoff, double temperature, String fingerprint) throws Exception {
        StringBuilder source = new StringBuilder("import com.omnidev.workspace.domain.neural.*;\npublic final class TrainedPublicRefusal {\n")
                .append("public static final String CORPUS_SHA256=\"").append(PublicRefusalCorpus.SHA256).append("\";\n")
                .append("public static final String VOCABULARY_SHA256=\"").append(fingerprint).append("\";\n")
                .append("public static final double CUTOFF=").append(cutoff).append(";\npublic static final double TEMPERATURE=").append(temperature).append(";\n")
                .append("public static TinyNeuralRuntime model(){return new TinyNeuralRuntime(new TinyNeuralRuntime.Dense(1024,1,weights(),bias(),TinyNeuralRuntime.Activation.SIGMOID));}\n");
        OfflineDecisionTrainer.Model.appendArrayFactory(source, "weights", layer.weightsCopy()); OfflineDecisionTrainer.Model.appendArrayFactory(source, "bias", layer.biasCopy());
        Files.write(path, source.append("}\n").toString().getBytes(StandardCharsets.UTF_8));
    }
    private static void evaluate(StringBuilder report, String name, List<Row> rows, RichIntentRefusalModel prior, RichIntentRefusalModel model) {
        int known = 0; for (Row r : rows) if (r.label == 0) known++;
        report.append('"').append(name).append("\":{\"rows\":").append(rows.size()).append(",\"known\":").append(known).append(",\"required_refusals\":").append(rows.size() - known);
        for (int i = 0; i < 2; i++) {
            int accepted = 0, correct = 0, knownAccepted = 0, refused = 0, lost = 0, correctLost = 0;
            for (Row r : rows) {
                TinyDecisionModel.Decision before = prior.classify(r.text, 0.6, 0.15), after = (i == 0 ? prior : model).classify(r.text, 0.6, 0.15); invariant(before, after);
                if (after.status == TinyDecisionModel.Status.CHOICE) { accepted++; if (r.label == 0) knownAccepted++; if (Objects.equals(after.choice, r.expected)) correct++; }
                else if (r.label == 1) refused++;
                if (r.label == 0 && before.status == TinyDecisionModel.Status.CHOICE && after.status == TinyDecisionModel.Status.ABSTAIN) { lost++; if (Objects.equals(before.choice, r.expected)) correctLost++; }
            }
            report.append(String.format(Locale.ROOT, ",\"%s\":{\"accepted\":%d,\"accepted_correct\":%d,\"accepted_errors\":%d,\"known_accepted\":%d,\"refused\":%d,\"known_lost\":%d,\"correct_known_lost\":%d}", i == 0 ? "rich_prior" : "public_gate", accepted, correct, accepted - correct, knownAccepted, refused, lost, correctLost));
        }
        report.append('}');
    }
    private static void benchmark(StringBuilder report, List<Row> train, RichIntentRefusalModel prior, RichIntentRefusalModel model) {
        List<Row> work = new ArrayList<>(); for (String language : new String[] {"ar", "en", "mixed"}) { int n = 0; for (Row r : train) if (r.language.equals(language) && n++ < 64) work.add(r); }
        RichIntentRefusalModel[] modes = {prior, model}; for (RichIntentRefusalModel mode : modes) for (int i = 0; i < 512; i++) consume(mode.classify(work.get(i % work.size()).text, 0.6, 0.15));
        long[][] times = new long[2][8]; for (int round = 0; round < 8; round++) for (int pos = 0; pos < 2; pos++) { int mode = (round + pos) % 2; long start = System.nanoTime(); for (Row r : work) consume(modes[mode].classify(r.text, 0.6, 0.15)); times[mode][round] = (System.nanoTime() - start) / work.size(); }
        report.append("{\"physical_phone\":false,\"whole_text_encoding_included\":true,\"calls_per_batch\":").append(work.size()).append(",\"batches\":8");
        for (int i = 0; i < 2; i++) { report.append(",\"").append(i == 0 ? "rich_prior" : "public_gate").append("\":{\"batch_average_ns\":["); for (int j = 0; j < 8; j++) { if (j > 0) report.append(','); report.append(times[i][j]); } Arrays.sort(times[i]); report.append("],\"median_batch_average_ns\":").append((times[i][3] + times[i][4]) / 2).append('}'); } report.append('}');
    }
    private static void consume(TinyDecisionModel.Decision d) { double sum = 0; for (double p : d.probabilitiesCopy()) sum += p * p; sink = sum; }
    private PublicRefusalExperiment() { }
}
