package com.omnidev.workspace.domain.neural;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import com.omnidev.workspace.domain.neural.BilingualRefusalCorpus.Row;

/** Follow-up on already observed data: independent label presence, not another single reject class. */
public final class MultiIntentExperiment {
    private static final String[] LABELS = MultiIntentRefusalModel.labels();
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected corpus and artifact directory");
        List<Row> rows = BilingualRefusalCorpus.load(Paths.get(args[0]));
        List<Row> train = BilingualRefusalCorpus.split(rows, "train"), dev = BilingualRefusalCorpus.split(rows, "development"), cal = BilingualRefusalCorpus.split(rows, "calibration");
        Path output = Paths.get(args[1]); Files.createDirectories(output);
        TinyNeuralRuntime[] heads = new TinyNeuralRuntime[5]; double[] temperatures = new double[5];
        StringBuilder details = new StringBuilder("[");
        for (int domain = 0; domain < 5; domain++) {
            Map<String, List<OfflineDecisionTrainer.Example>> fitting = examples(train, domain), validation = examples(dev, domain), calibration = examples(cal, domain);
            List<OfflineDecisionTrainer.Example> balanced = balance(fitting);
            OfflineDecisionTrainer.requireDisjointGroups(flatten(fitting), flatten(validation), flatten(calibration),
                    flatten(examples(BilingualRefusalCorpus.split(rows, "test"), domain)), flatten(examples(BilingualRefusalCorpus.split(rows, "regression"), domain)));
            OfflineDecisionTrainer.TrainingResult best = null; long chosenSeed = 0;
            for (long seed : new long[] {42, 97}) {
                OfflineDecisionTrainer.TrainingResult result = OfflineDecisionTrainer.fitEarlyStopped(balanced, 2, 0, 200, seed, new ArrayList<>(validation.values()));
                System.err.printf(Locale.ROOT, "Presence domain=%s seed=%d epoch=%d development_macro_nll=%.6f%n", LABELS[domain], seed, result.bestEpoch, result.validationLoss);
                if (best == null || result.validationLoss < best.validationLoss) { best = result; chosenSeed = seed; }
            }
            double temperature = 1, bestCal = Double.POSITIVE_INFINITY;
            for (double t : new double[] {0.5, 0.75, 1, 1.5, 2, 3, 5}) {
                double loss = 0; for (List<OfflineDecisionTrainer.Example> stratum : calibration.values()) loss += OfflineDecisionTrainer.logLoss(best.model, stratum, t);
                loss /= calibration.size(); if (loss < bestCal) { bestCal = loss; temperature = t; }
            }
            heads[domain] = best.model.runtime().withSparseInput(); temperatures[domain] = temperature;
            Files.write(output.resolve("TrainedMultiIntent" + domain + ".java"), best.model.javaSource(temperature, "TrainedMultiIntent" + domain).getBytes(StandardCharsets.UTF_8));
            if (domain > 0) details.append(',');
            details.append(String.format(Locale.ROOT, "{\"label\":\"%s\",\"seed\":%d,\"epoch\":%d,\"development_macro_nll\":%.9f,\"temperature\":%s}",
                    LABELS[domain], chosenSeed, best.bestEpoch, best.validationLoss, temperature));
        }
        details.append(']');
        Class<?> old = Class.forName("TrainedTextIntentCheckpoint"), gate = Class.forName("TrainedRefusalGateCheckpoint");
        TinyNeuralRuntime domain = ((TinyNeuralRuntime) old.getMethod("model").invoke(null)).withSparseInput();
        GatedTextIntentModel prior = new GatedTextIntentModel(domain, (String[]) old.getMethod("labels").invoke(null), old.getField("TEMPERATURE").getDouble(null),
                ((TinyNeuralRuntime) gate.getMethod("model").invoke(null)).withSparseInput(), gate.getField("TEMPERATURE").getDouble(null), gate.getField("CUTOFF").getDouble(null), BilingualTextEncoder.VERSION);
        double threshold = Double.NaN, bestScore = -1; boolean strict = false; int bestKnownLoss = Integer.MAX_VALUE;
        for (boolean match : new boolean[] {false, true}) for (double t : new double[] {0.25, 0.4, 0.5, 0.6, 0.75, 0.9, 0.99, 1}) {
            MultiIntentRefusalModel model = new MultiIntentRefusalModel(prior, heads, temperatures, t, match, BilingualTextEncoder.VERSION);
            double[] score = development(dev, prior, model);
            if (Double.isFinite(score[0]) && (score[0] > bestScore || score[0] == bestScore && score[1] < bestKnownLoss)) {
                threshold = t; strict = match; bestScore = score[0]; bestKnownLoss = (int) score[1];
            }
        }
        if (!Double.isFinite(threshold)) throw new IllegalStateException("No coverage-feasible multi-intent configuration");
        MultiIntentRefusalModel selected = new MultiIntentRefusalModel(prior, heads, temperatures, threshold, strict, BilingualTextEncoder.VERSION);
        StringBuilder manifest = new StringBuilder("import com.omnidev.workspace.domain.neural.TinyNeuralRuntime;\npublic final class TrainedMultiIntentManifest {\n")
                .append("public static final String ENCODER_VERSION = \"").append(BilingualTextEncoder.VERSION).append("\";\n")
                .append("public static final String CORPUS_SHA256 = \"").append(BilingualRefusalCorpus.SHA256).append("\";\n")
                .append("public static String[] labels() {return new String[]{\"repository\",\"build\",\"device\",\"knowledge\",\"chat\"};}\n")
                .append("public static final double THRESHOLD = ").append(threshold).append(";\npublic static final boolean REQUIRE_MATCH = ").append(strict).append(";\n")
                .append("public static TinyNeuralRuntime[] models() {return new TinyNeuralRuntime[]{");
        for (int i = 0; i < 5; i++) { if (i > 0) manifest.append(','); manifest.append("TrainedMultiIntent").append(i).append(".model()"); }
        manifest.append("};}\n public static double[] temperatures() {return new double[]{");
        for (int i = 0; i < 5; i++) { if (i > 0) manifest.append(','); manifest.append(temperatures[i]); }
        manifest.append("};}\n}\n"); Files.write(output.resolve("TrainedMultiIntentManifest.java"), manifest.toString().getBytes(StandardCharsets.UTF_8));
        // All outputs are now fixed; these test observations were already viewed in the prior experiment.
        StringBuilder report = new StringBuilder("{\"production_eligible\":false,\"test_status\":\"already_observed_regressions\",\"extra_parameters\":2570,\"heads\":")
                .append(details).append(",\"threshold\":").append(threshold).append(",\"require_match\":").append(strict).append(",\"evaluations\":{");
        evaluate(report, "development", dev, prior, selected);
        for (String split : new String[] {"test", "regression"}) {
            report.append(','); evaluate(report, split, BilingualRefusalCorpus.split(rows, split), prior, selected);
        }
        for (String kind : new String[] {"original", "composed_cross", "composed_same", "unseen_topic", "prior_clinc", "additional_clinc"}) {
            List<Row> subset = new ArrayList<>(); for (Row row : rows) if ((row.split.equals("test") || row.split.equals("regression")) && row.kind.equals(kind)) subset.add(row);
            report.append(','); evaluate(report, kind, subset, prior, selected);
        }
        report.append(','); evaluate(report, "old_challenge", BilingualRefusalCorpus.challenges(Paths.get("neural-core/fixtures/decision-challenges.tsv")), prior, selected);
        report.append("}}\n"); Files.write(output.resolve("report.json"), report.toString().getBytes(StandardCharsets.UTF_8)); System.out.print(report);
        // Store typed decisions from the in-memory trained model for compiled export verification.
        List<String> expected = new ArrayList<>(); expected.add("status\tchoice");
        for (Row row : rows) { TinyDecisionModel.Decision d = selected.classify(row.text, 0.6, 0.15); expected.add(d.status + "\t" + (d.choice == null ? "-" : d.choice)); }
        Files.write(output.resolve("expected.tsv"), expected, StandardCharsets.UTF_8);
    }
    private static boolean present(Row row, int domain) {
        if (row.kind.equals("composed_cross")) return parent(row.parentA).equals(LABELS[domain]) || parent(row.parentB).equals(LABELS[domain]);
        return row.expectedDomain.equals(LABELS[domain]);
    }
    private static String parent(String group) {
        String prefix = group.substring("original:".length()).split("-")[0];
        if (prefix.equals("repo")) return "repository"; if (prefix.equals("know")) return "knowledge"; return prefix;
    }
    private static Map<String, List<OfflineDecisionTrainer.Example>> examples(List<Row> rows, int domain) {
        Map<String, List<OfflineDecisionTrainer.Example>> result = new TreeMap<>();
        for (Row row : rows) { int label = present(row, domain) ? 1 : 0;
            result.computeIfAbsent(row.language + ":" + label, key -> new ArrayList<>()).add(new OfflineDecisionTrainer.Example(row.group, row.features, label)); }
        return result;
    }
    private static List<OfflineDecisionTrainer.Example> flatten(Map<String, List<OfflineDecisionTrainer.Example>> groups) {
        List<OfflineDecisionTrainer.Example> result = new ArrayList<>(); for (List<OfflineDecisionTrainer.Example> g : groups.values()) result.addAll(g); return result;
    }
    private static List<OfflineDecisionTrainer.Example> balance(Map<String, List<OfflineDecisionTrainer.Example>> groups) {
        if (groups.size() != 6) throw new IllegalArgumentException("Missing presence/language stratum");
        int max = 0; for (List<OfflineDecisionTrainer.Example> g : groups.values()) max = Math.max(max, g.size());
        List<OfflineDecisionTrainer.Example> result = new ArrayList<>();
        for (List<OfflineDecisionTrainer.Example> g : groups.values()) for (int i = 0; i < max; i++) result.add(g.get(i % g.size())); return result;
    }
    private static double[] development(List<Row> rows, GatedTextIntentModel base, MultiIntentRefusalModel model) {
        Map<String, int[]> known = new TreeMap<>(), rejected = new TreeMap<>(); int falseRefusals = 0;
        for (Row row : rows) {
            TinyDecisionModel.Decision a = base.classify(row.text, 0.6, 0.15), b = model.classify(row.text, 0.6, 0.15);
            if (row.label == 0 && a.status == TinyDecisionModel.Status.CHOICE) {
                int[] n = known.computeIfAbsent(row.language, key -> new int[2]); n[0]++; if (b.status == TinyDecisionModel.Status.ABSTAIN) { n[1]++; falseRefusals++; }
            } else if (row.label == 1) { int[] n = rejected.computeIfAbsent(row.kind + ":" + row.language, key -> new int[2]); n[0]++; if (b.status == TinyDecisionModel.Status.ABSTAIN) n[1]++; }
        }
        if (known.size() != 3) return new double[] {Double.NaN, 0};
        for (int[] n : known.values()) if (n[1] > (int) Math.floor(n[0] * 0.05)) return new double[] {Double.NaN, 0};
        double recall = 0; for (int[] n : rejected.values()) recall += (double) n[1] / n[0]; return new double[] {recall / rejected.size(), falseRefusals};
    }
    private static void evaluate(StringBuilder out, String name, List<Row> rows, GatedTextIntentModel prior, MultiIntentRefusalModel model) {
        int known = 0, required = 0; int[] accepted = new int[2], correct = new int[2], knownAccepted = new int[2], refused = new int[2];
        for (Row row : rows) {
            if (row.label == 0) known++; else required++;
            TinyDecisionModel.Decision[] d = {prior.classify(row.text, 0.6, 0.15), model.classify(row.text, 0.6, 0.15)};
            if (!Arrays.equals(d[0].probabilitiesCopy(), d[1].probabilitiesCopy()) || d[1].status == TinyDecisionModel.Status.CHOICE && !Objects.equals(d[0].choice, d[1].choice)) throw new AssertionError("Presence heads changed prior proposal");
            for (int i = 0; i < 2; i++) if (d[i].status == TinyDecisionModel.Status.CHOICE) { accepted[i]++; if (row.label == 0) knownAccepted[i]++; if (Objects.equals(d[i].choice, row.expectedDomain)) correct[i]++; }
            else if (row.label == 1) refused[i]++;
        }
        out.append('"').append(name).append("\":{\"known\":").append(known).append(",\"required_refusals\":").append(required);
        String[] names = {"prior", "multi_intent"};
        for (int i = 0; i < 2; i++) out.append(String.format(Locale.ROOT, ",\"%s\":{\"accepted\":%d,\"accepted_correct\":%d,\"accepted_errors\":%d,\"known_accepted\":%d,\"refused\":%d}", names[i], accepted[i], correct[i], accepted[i] - correct[i], knownAccepted[i], refused[i]));
        out.append('}');
    }
}
