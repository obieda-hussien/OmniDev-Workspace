package com.omnidev.workspace.domain.neural;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import com.omnidev.workspace.domain.neural.PublicRefusalCorpus.Row;

/** Separate follow-up proposal experiment; all test sets are already observed, never fresh validation. */
public final class PublicDecisionExperiment {
    private static volatile double sink;
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected public corpus and output directory");
        List<Row> all = PublicRefusalCorpus.old(BilingualRefusalCorpus.load(Paths.get("neural-core/fixtures/bilingual-refusal/corpus.tsv")));
        List<Row> publicRows = PublicRefusalCorpus.load(Paths.get(args[0])); all.addAll(publicRows);
        RichTextEncoder encoder = PublicRefusalExperiment.encoder();
        List<Row> train = PublicRefusalCorpus.split(all, "train"), dev = PublicRefusalCorpus.split(all, "development"), cal = PublicRefusalCorpus.split(all, "calibration");
        Map<Row, float[]> features = new IdentityHashMap<>();
        for (Row r : all) if (Arrays.asList("train", "development", "calibration").contains(r.split)) features.put(r, encoder.encode(r.text));
        Map<String, List<OfflineDecisionTrainer.Example>> training = examples(train, features), validation = examples(dev, features), calibration = examples(cal, features);
        OfflineDecisionTrainer.requireDisjointGroups(flatten(training), flatten(validation), flatten(calibration), identities(PublicRefusalCorpus.split(all, "test")), identities(PublicRefusalCorpus.split(all, "regression")));
        int maximum = 0; for (List<OfflineDecisionTrainer.Example> g : training.values()) maximum = Math.max(maximum, g.size());
        List<OfflineDecisionTrainer.Example> balanced = new ArrayList<>();
        for (List<OfflineDecisionTrainer.Example> g : training.values()) for (int i = 0; i < maximum; i++) balanced.add(g.get(i % g.size()));
        OfflineDecisionTrainer.TrainingResult best = null; long selectedSeed = 0; StringBuilder candidates = new StringBuilder("[");
        for (long seed : new long[] {42, 97}) {
            OfflineDecisionTrainer.TrainingResult fit = OfflineDecisionTrainer.fitEarlyStopped(balanced, 6, 0, 200, seed, new ArrayList<>(validation.values()));
            if (candidates.length() > 1) candidates.append(','); candidates.append(String.format(Locale.ROOT, "{\"seed\":%d,\"epoch\":%d,\"epochs_run\":%d,\"development_macro_nll\":%.9f}", seed, fit.bestEpoch, fit.epochsRun, fit.validationLoss));
            System.err.printf(Locale.ROOT, "Public decision seed=%d epoch=%d development_macro_nll=%.6f%n", seed, fit.bestEpoch, fit.validationLoss);
            if (best == null || fit.validationLoss < best.validationLoss) { best = fit; selectedSeed = seed; }
        }
        candidates.append(']'); double temperature = 1, bestCal = Double.POSITIVE_INFINITY;
        for (double t : new double[] {0.5, 0.75, 1, 1.5, 2, 3, 5}) {
            double nll = 0; for (List<OfflineDecisionTrainer.Example> g : calibration.values()) nll += OfflineDecisionTrainer.logLoss(best.model, g, t); nll /= calibration.size();
            if (nll < bestCal) { bestCal = nll; temperature = t; }
        }
        RichTextDecisionModel candidate = new RichTextDecisionModel(encoder, best.model.runtime().withSparseInput(), temperature, RichTextEncoder.VERSION, encoder.fingerprint());
        RichIntentRefusalModel prior = PublicRefusalExperiment.model(null, 1, true);
        double probability = 0.6, margin = 0.15, bestScore = -1; boolean feasible = false;
        for (double p : new double[] {0.5, 0.6, 0.7, 0.8, 0.9}) for (double m : new double[] {0, 0.1, 0.15, 0.25}) {
            boolean retains = true; Map<String, int[]> strata = new TreeMap<>();
            for (Row r : dev) {
                TinyDecisionModel.Decision before = prior.classify(r.text, 0.6, 0.15), after = candidate.classify(r.text, p, m);
                boolean correct = r.label == 1 ? after.status == TinyDecisionModel.Status.ABSTAIN : Objects.equals(r.expected, after.choice);
                if (r.label == 0 && Objects.equals(before.choice, r.expected) && !correct) retains = false;
                int[] n = strata.computeIfAbsent(r.language + ":" + r.label, k -> new int[2]); n[0]++; if (correct) n[1]++;
            }
            double score = 0; for (int[] n : strata.values()) score += (double) n[1] / n[0]; score /= strata.size();
            if (retains && score > bestScore) { feasible = true; bestScore = score; probability = p; margin = m; }
        }
        Path out = Paths.get(args[1]); Files.createDirectories(out);
        String metadata = "\npublic static final String VOCABULARY_SHA256=\"" + encoder.fingerprint() + "\";\n" +
                "public static final String CORPUS_SHA256=\"" + PublicRefusalCorpus.SHA256 + "\";\n" +
                "public static final double MINIMUM_PROBABILITY=" + probability + ";\npublic static final double MINIMUM_MARGIN=" + margin + ";\n";
        Files.write(out.resolve("TrainedPublicDecision.java"), best.model.javaSource(temperature, "TrainedPublicDecision").replace("public final class TrainedPublicDecision {", "public final class TrainedPublicDecision {" + metadata).getBytes(StandardCharsets.UTF_8));
        // All fitting/calibration/policy decisions are fixed. These are observed regression sets, not new independent tests.
        all.addAll(PublicRefusalCorpus.old(BilingualRefusalCorpus.freshEnglish(Paths.get("neural-core/fixtures/rich-text/fresh-english.tsv"))));
        all.addAll(PublicRefusalCorpus.old(BilingualRefusalCorpus.challenges(Paths.get("neural-core/fixtures/decision-challenges.tsv"))));
        List<String> expected = new ArrayList<>(Arrays.asList("vocabulary_sha256\t" + encoder.fingerprint(), "corpus_sha256\t" + PublicRefusalCorpus.SHA256,
                "temperature\t" + temperature, "probability\t" + probability, "margin\t" + margin, "status\tchoice\tprobabilities"));
        for (Row r : all) {
            TinyDecisionModel.Decision d = candidate.classify(r.text, probability, margin); StringBuilder line = new StringBuilder(d.status + "\t" + (d.choice == null ? "-" : d.choice) + "\t");
            double[] scores = d.probabilitiesCopy(); for (int i = 0; i < scores.length; i++) { if (i > 0) line.append(','); line.append(scores[i]); } expected.add(line.toString());
        }
        Files.write(out.resolve("expected.tsv"), expected, StandardCharsets.UTF_8);
        StringBuilder report = new StringBuilder("{\"production_eligible\":false,\"evaluation_status\":\"all_test_sets_already_observed_followup_regressions\",\"development_feasible\":")
                .append(feasible).append(",\"parameters\":6150,\"representation\":\"fixed_rich_text_v1\",\"candidates\":").append(candidates)
                .append(",\"selected_seed\":").append(selectedSeed).append(",\"selected_epoch\":").append(best.bestEpoch)
                .append(",\"train_rows\":").append(train.size()).append(",\"balanced_train_rows\":").append(balanced.size())
                .append(",\"temperature\":").append(temperature).append(",\"minimum_probability\":").append(probability).append(",\"minimum_margin\":").append(margin).append(",\"evaluations\":{");
        boolean first = true;
        for (String language : new String[] {"ar", "en"}) for (String kind : new String[] {"all", "held_out_topic"}) {
            List<Row> subset = new ArrayList<>(); for (Row r : publicRows) if (r.split.equals("test") && r.language.equals(language) && (kind.equals("all") || r.kind.equals(kind))) subset.add(r);
            if (!first) report.append(','); first = false; evaluate(report, "massive_" + language + "_" + kind, subset, prior, candidate, probability, margin);
        }
        for (String kind : new String[] {"all", "old_original", "old_composed_same", "old_composed_cross", "old_fresh_english", "old_old_challenge"}) {
            List<Row> subset = new ArrayList<>(); for (Row r : all) if (r.kind.startsWith("old_") && Arrays.asList("test", "regression").contains(r.split) && (kind.equals("all") || r.kind.equals(kind))) subset.add(r);
            report.append(','); evaluate(report, "regression_" + kind, subset, prior, candidate, probability, margin);
        }
        report.append("},\"host_timing\":"); benchmark(report, train, prior, candidate, probability, margin); report.append("}\n");
        Files.write(out.resolve("report.json"), report.toString().getBytes(StandardCharsets.UTF_8)); System.out.print(report);
    }
    private static Map<String, List<OfflineDecisionTrainer.Example>> examples(List<Row> rows, Map<Row, float[]> features) {
        List<String> labels = Arrays.asList(RichTextDecisionModel.labels()); Map<String, List<OfflineDecisionTrainer.Example>> result = new TreeMap<>();
        for (Row r : rows) { int label = r.label == 1 ? 5 : labels.indexOf(r.expected); if (label < 0) throw new IllegalArgumentException("Unknown training label");
            result.computeIfAbsent(r.language + ":" + label, k -> new ArrayList<>()).add(new OfflineDecisionTrainer.Example(r.group, features.get(r), label)); }
        if (result.size() != 18) throw new IllegalArgumentException("Missing decision language/class stratum"); return result;
    }
    private static List<OfflineDecisionTrainer.Example> flatten(Map<String, List<OfflineDecisionTrainer.Example>> groups) { List<OfflineDecisionTrainer.Example> result = new ArrayList<>(); for (List<OfflineDecisionTrainer.Example> g : groups.values()) result.addAll(g); return result; }
    private static List<OfflineDecisionTrainer.Example> identities(List<Row> rows) { List<OfflineDecisionTrainer.Example> result = new ArrayList<>(); for (Row r : rows) result.add(new OfflineDecisionTrainer.Example(r.group, new float[] {0}, r.label)); return result; }
    private static void evaluate(StringBuilder report, String name, List<Row> rows, RichIntentRefusalModel prior, RichTextDecisionModel candidate, double probability, double margin) {
        int known = 0; for (Row r : rows) if (r.label == 0) known++;
        report.append('"').append(name).append("\":{\"rows\":").append(rows.size()).append(",\"known\":").append(known).append(",\"required_refusals\":").append(rows.size() - known);
        for (int mode = 0; mode < 2; mode++) {
            int accepted = 0, correct = 0, refused = 0, lost = 0, rawCorrect = 0;
            for (Row r : rows) {
                TinyDecisionModel.Decision before = prior.classify(r.text, 0.6, 0.15), after = mode == 0 ? before : candidate.classify(r.text, probability, margin);
                if (after.status == TinyDecisionModel.Status.CHOICE) { accepted++; if (Objects.equals(after.choice, r.expected)) correct++; }
                else if (r.label == 1) refused++;
                if (r.label == 0 && Objects.equals(before.choice, r.expected) && !Objects.equals(after.choice, r.expected)) lost++;
                double[] scores = after.probabilitiesCopy(); int best = 0; for (int i = 1; i < scores.length; i++) if (scores[i] > scores[best]) best = i;
                if (r.label == 0 && RichTextDecisionModel.labels()[best].equals(r.expected)) rawCorrect++;
            }
            report.append(String.format(Locale.ROOT, ",\"%s\":{\"accepted\":%d,\"accepted_correct\":%d,\"accepted_errors\":%d,\"unsupported_refused\":%d,\"previous_correct_lost\":%d,\"raw_known_correct\":%d}", mode == 0 ? "rich_prior" : "public_decision", accepted, correct, accepted - correct, refused, lost, rawCorrect));
        }
        report.append('}');
    }
    private static void benchmark(StringBuilder report, List<Row> train, RichIntentRefusalModel prior, RichTextDecisionModel candidate, double probability, double margin) {
        List<Row> work = new ArrayList<>(); for (String language : new String[] {"ar", "en", "mixed"}) { int n = 0; for (Row r : train) if (r.language.equals(language) && n++ < 64) work.add(r); }
        for (int i = 0; i < 512; i++) { Row r = work.get(i % work.size()); consume(prior.classify(r.text, 0.6, 0.15)); consume(candidate.classify(r.text, probability, margin)); }
        long[][] times = new long[2][8]; for (int round = 0; round < 8; round++) for (int pos = 0; pos < 2; pos++) { int mode = (round + pos) % 2; long start = System.nanoTime(); for (Row r : work) consume(mode == 0 ? prior.classify(r.text, 0.6, 0.15) : candidate.classify(r.text, probability, margin)); times[mode][round] = (System.nanoTime() - start) / work.size(); }
        report.append("{\"physical_phone\":false,\"whole_text_encoding_included\":true,\"calls_per_batch\":").append(work.size()).append(",\"batches\":8");
        for (int i = 0; i < 2; i++) { report.append(",\"").append(i == 0 ? "rich_prior" : "public_decision").append("\":{\"batch_average_ns\":["); for (int j = 0; j < 8; j++) { if (j > 0) report.append(','); report.append(times[i][j]); } Arrays.sort(times[i]); report.append("],\"median_batch_average_ns\":").append((times[i][3] + times[i][4]) / 2).append('}'); } report.append('}');
    }
    private static void consume(TinyDecisionModel.Decision d) { double sum = 0; for (double p : d.probabilitiesCopy()) sum += p * p; sink = sum; }
    private PublicDecisionExperiment() { }
}
