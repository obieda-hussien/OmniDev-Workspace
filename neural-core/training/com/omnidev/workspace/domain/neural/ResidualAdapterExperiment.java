package com.omnidev.workspace.domain.neural;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import com.omnidev.workspace.domain.neural.PublicRefusalCorpus.Row;
import com.omnidev.workspace.domain.neural.ResidualAdapterTrainer.Example;
import com.omnidev.workspace.domain.neural.ResidualAdapterTrainer.Model;

/** Frozen teacher, bounded correction, train/calibration/development separation, then reserved-test scoring. */
public final class ResidualAdapterExperiment {
    private static volatile double sink;
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected output directory");
        List<Row> all = rows(); RichTextEncoder encoder = PublicRefusalExperiment.encoder(); RichIntentRefusalModel teacher = teacher(true);
        List<Row> trainRows = PublicRefusalCorpus.split(all, "train"), devRows = PublicRefusalCorpus.split(all, "development"), calRows = PublicRefusalCorpus.split(all, "calibration");
        List<Example> train = examples(trainRows, encoder, teacher), dev = examples(devRows, encoder, teacher), cal = examples(calRows, encoder, teacher);
        requireDisjoint(all);
        Map<String, List<Example>> strata = new TreeMap<>(); for (Example e : train) strata.computeIfAbsent(e.language + ":" + e.label, k -> new ArrayList<>()).add(e);
        if (strata.size() != 15) throw new IllegalArgumentException("Missing residual language/domain stratum");
        int maximum = 0; for (List<Example> g : strata.values()) maximum = Math.max(maximum, g.size());
        List<Example> balanced = new ArrayList<>(); for (List<Example> g : strata.values()) for (int i = 0; i < maximum; i++) balanced.add(g.get(i % g.size()));
        Model best = null; double anchorChoice = 0; long seedChoice = 0; StringBuilder candidates = new StringBuilder("[");
        for (double anchor : new double[] {1, 4, 16}) for (long seed : new long[] {42, 97}) {
            Model model = ResidualAdapterTrainer.fit(balanced, dev, anchor, seed);
            if (Math.abs(ResidualAdapterTrainer.loss(model, dev, 1) - model.loss) > 1e-9) throw new AssertionError("Residual best snapshot no longer matches its validation loss");
            System.err.printf(Locale.ROOT, "Residual anchor=%s seed=%d epoch=%d development_macro_nll=%.6f%n", anchor, seed, model.epoch, model.loss);
            if (candidates.length() > 1) candidates.append(','); candidates.append(String.format(Locale.ROOT, "{\"anchor\":%s,\"seed\":%d,\"best_epoch\":%d,\"epochs_run\":%d,\"development_macro_nll\":%.9f}", anchor, seed, model.epoch, model.epochsRun, model.loss));
            if (best == null || model.loss < best.loss) { best = model; anchorChoice = anchor; seedChoice = seed; }
        }
        candidates.append(']'); double maximumGain = 0.125, bestCal = Double.POSITIVE_INFINITY;
        for (double gain : new double[] {0.125, 0.25, 0.5, 1}) { double loss = ResidualAdapterTrainer.loss(best, cal, gain); if (loss < bestCal) { bestCal = loss; maximumGain = gain; } }
        double gainChoice = 0, bestScore = -1; StringBuilder policyGrid = new StringBuilder("[");
        for (double gain : new double[] {0, 0.125, 0.25, 0.5, 1}) if (gain <= maximumGain) {
            BoundedResidualDecisionModel candidate = model(best.runtime().withSparseInput(), gain, true); boolean retained = true; int correctCount = 0, lostCount = 0; Map<String, int[]> counts = new TreeMap<>();
            for (Row r : devRows) if (r.label == 0) {
                TinyDecisionModel.Decision before = teacher.classify(r.text, 0.6, 0.15), after = candidate.classify(r.text, 0.6, 0.15);
                boolean correct = Objects.equals(after.choice, r.expected);
                if (Objects.equals(before.choice, r.expected) && !correct) { retained = false; lostCount++; }
                if (correct) correctCount++;
                int[] n = counts.computeIfAbsent(r.language, k -> new int[2]); n[0]++; if (correct) n[1]++;
            }
            double score = 0; for (int[] n : counts.values()) score += (double) n[1] / n[0]; score /= counts.size();
            if (policyGrid.length() > 1) policyGrid.append(','); policyGrid.append(String.format(Locale.ROOT, "{\"gain\":%s,\"correct\":%d,\"previous_correct_lost\":%d,\"language_macro_correct\":%.9f}", gain, correctCount, lostCount, score));
            if (retained && score > bestScore) { gainChoice = gain; bestScore = score; }
        }
        policyGrid.append(']');
        if (bestScore < 0) throw new IllegalStateException("No feasible residual policy, including zero correction");
        Path output = Paths.get(args[0]); Files.createDirectories(output);
        Files.write(output.resolve("TrainedResidualAdapter.java"), best.javaSource(gainChoice, encoder.fingerprint()).getBytes(StandardCharsets.UTF_8));
        // Checkpoint and policy are fixed before reading or scoring the reserved fresh utterances.
        List<Row> fresh = fresh(); all.addAll(PublicRefusalCorpus.old(BilingualRefusalCorpus.freshEnglish(Paths.get("neural-core/fixtures/rich-text/fresh-english.tsv"))));
        all.addAll(PublicRefusalCorpus.old(BilingualRefusalCorpus.challenges(Paths.get("neural-core/fixtures/decision-challenges.tsv")))); all.addAll(fresh);
        BoundedResidualDecisionModel chosen = model(best.runtime().withSparseInput(), gainChoice, true);
        List<String> numeric = new ArrayList<>(Arrays.asList("vocabulary_sha256\t" + encoder.fingerprint(), "gain\t" + gainChoice, "status\tchoice\tprobabilities\tresidual"));
        TinyNeuralRuntime runtime = best.runtime();
        for (Row r : all) {
            TinyDecisionModel.Decision d = chosen.classify(r.text, 0.6, 0.15); priorRefusal(teacher.classify(r.text, 0.6, 0.15), d);
            StringBuilder line = new StringBuilder(d.status + "\t" + (d.choice == null ? "-" : d.choice) + "\t");
            double[] probabilities = d.probabilitiesCopy(); for (int i = 0; i < 5; i++) { if (i > 0) line.append(','); line.append(probabilities[i]); } line.append('\t');
            float[] delta = runtime.infer(encoder.encode(r.text)); for (int i = 0; i < 5; i++) { if (i > 0) line.append(','); line.append(delta[i]); } numeric.add(line.toString());
        }
        Files.write(output.resolve("expected.tsv"), numeric, StandardCharsets.UTF_8);
        StringBuilder report = new StringBuilder("{\"production_eligible\":false,\"fresh_supported_rows\":0,\"fresh_limits\":\"160 unused utterances from four existing unsupported topics; no new known-domain positives\",\"candidates\":")
                .append(candidates).append(",\"selected_anchor\":").append(anchorChoice).append(",\"selected_seed\":").append(seedChoice).append(",\"selected_epoch\":").append(best.epoch)
                .append(",\"calibration_maximum_gain\":").append(maximumGain).append(",\"development_policy_grid\":").append(policyGrid).append(",\"gain\":").append(gainChoice).append(",\"extra_parameters\":5125,\"total_weight_parameters\":19618,\"train_known_rows\":").append(train.size())
                .append(",\"balanced_train_known_rows\":").append(balanced.size()).append(",\"evaluations\":{");
        for (String language : new String[] {"ar", "en"}) {
            if (language.equals("en")) report.append(','); List<Row> subset = new ArrayList<>(); for (Row r : all) if (r.split.equals("test") && r.language.equals(language) && (r.kind.equals("massive") || r.kind.equals("held_out_topic"))) subset.add(r);
            evaluate(report, "massive_" + language, subset, teacher, chosen);
        }
        for (String kind : new String[] {"all", "old_original", "old_composed_same", "old_composed_cross", "old_fresh_english", "old_old_challenge"}) {
            List<Row> subset = new ArrayList<>(); for (Row r : all) if (r.kind.startsWith("old_") && Arrays.asList("test", "regression").contains(r.split) && (kind.equals("all") || r.kind.equals(kind))) subset.add(r);
            report.append(','); evaluate(report, "regression_" + kind, subset, teacher, chosen);
        }
        report.append(','); evaluate(report, "fresh_unsupported", fresh, teacher, chosen); report.append("},\"host_timing\":"); benchmark(report, trainRows, teacher, chosen); report.append("}\n");
        Files.write(output.resolve("report.json"), report.toString().getBytes(StandardCharsets.UTF_8)); System.out.print(report);
    }
    static RichIntentRefusalModel teacher(boolean sparse) throws Exception {
        Class<?> type = Class.forName("TrainedPublicRefusal"); TinyNeuralRuntime gate = (TinyNeuralRuntime) type.getMethod("model").invoke(null);
        return PublicRefusalExperiment.model(sparse ? gate.withSparseInput() : gate, type.getField("CUTOFF").getDouble(null), sparse);
    }
    static BoundedResidualDecisionModel model(TinyNeuralRuntime residual, double gain, boolean sparse) throws Exception { RichTextEncoder encoder = PublicRefusalExperiment.encoder(); return new BoundedResidualDecisionModel(teacher(sparse), encoder, residual, gain, encoder.fingerprint()); }
    static List<Row> rows() throws Exception { List<Row> rows = PublicRefusalCorpus.old(BilingualRefusalCorpus.load(Paths.get("neural-core/fixtures/bilingual-refusal/corpus.tsv"))); rows.addAll(PublicRefusalCorpus.load(Paths.get("neural-core/fixtures/massive-refusal/corpus.tsv"))); return rows; }
    static List<Row> fresh() throws Exception {
        Path path = Paths.get("neural-core/fixtures/residual-adapter/fresh.tsv"); if (Files.size(path) > 100_000) throw new IllegalArgumentException("Fresh residual data bounds exceeded");
        byte[] bytes = Files.readAllBytes(path); StringBuilder hash = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
        if (!hash.toString().equals("05add51a7760a1ad743f3830de7597522f32314f33764a7ea19d7451a2a8fffa")) throw new IllegalArgumentException("Fresh residual data hash changed");
        List<String> lines = Arrays.asList(new String(bytes, StandardCharsets.UTF_8).split("\n"));
        if (!lines.get(0).equals("source_id\tsource_partition\tlocale\tintent\texpected_domain\ttext")) throw new IllegalArgumentException("Fresh residual schema changed");
        List<Row> rows = new ArrayList<>(); for (String line : lines.subList(1, lines.size())) { String[] c = line.split("\t", -1); if (c.length != 6 || !c[1].equals("test")) throw new IllegalArgumentException("Fresh residual partition changed"); rows.add(new Row("test", "massive-" + c[0], c[2].startsWith("ar") ? "ar" : "en", c[4], "fresh", c[5])); } return rows;
    }
    private static List<Example> examples(List<Row> rows, RichTextEncoder encoder, RichIntentRefusalModel teacher) {
        List<Example> result = new ArrayList<>(); List<String> labels = Arrays.asList(MultiIntentRefusalModel.labels());
        for (Row r : rows) if (r.label == 0) result.add(new Example(r.group, r.language, labels.indexOf(r.expected), r.kind.startsWith("old_"), encoder.encode(r.text), teacher.classify(r.text, 0.6, 0.15).probabilitiesCopy())); return result;
    }
    private static void requireDisjoint(List<Row> rows) { Map<String, String> groups = new HashMap<>(); for (Row r : rows) { String old = groups.putIfAbsent(r.group, r.split); if (old != null && !old.equals(r.split)) throw new IllegalArgumentException("Residual split leakage"); } }
    static void priorRefusal(TinyDecisionModel.Decision before, TinyDecisionModel.Decision after) { if (before.status == TinyDecisionModel.Status.ABSTAIN && (after.status != before.status || !Arrays.equals(before.probabilitiesCopy(), after.probabilitiesCopy()))) throw new AssertionError("Residual upgraded a prior refusal"); }
    private static void evaluate(StringBuilder report, String name, List<Row> rows, RichIntentRefusalModel teacher, BoundedResidualDecisionModel model) {
        int known = 0; for (Row r : rows) if (r.label == 0) known++;
        report.append('"').append(name).append("\":{\"rows\":").append(rows.size()).append(",\"known\":").append(known).append(",\"required_refusals\":").append(rows.size() - known);
        for (int mode = 0; mode < 2; mode++) {
            int accepted = 0, correct = 0, refused = 0, lost = 0, changedUnsupported = 0;
            for (Row r : rows) { TinyDecisionModel.Decision before = teacher.classify(r.text, 0.6, 0.15), after = mode == 0 ? before : model.classify(r.text, 0.6, 0.15); priorRefusal(before, after);
                if (after.status == TinyDecisionModel.Status.CHOICE) { accepted++; if (Objects.equals(after.choice, r.expected)) correct++; } else if (r.label == 1) refused++;
                if (r.label == 0 && Objects.equals(before.choice, r.expected) && !Objects.equals(after.choice, r.expected)) lost++;
                if (r.label == 1 && after.status == TinyDecisionModel.Status.CHOICE && !Objects.equals(before.choice, after.choice)) changedUnsupported++;
            }
            report.append(String.format(Locale.ROOT, ",\"%s\":{\"accepted\":%d,\"accepted_correct\":%d,\"accepted_errors\":%d,\"unsupported_refused\":%d,\"previous_correct_lost\":%d,\"unsupported_choices_changed\":%d}", mode == 0 ? "teacher" : "residual", accepted, correct, accepted - correct, refused, lost, changedUnsupported));
        } report.append('}');
    }
    private static void benchmark(StringBuilder report, List<Row> train, RichIntentRefusalModel teacher, BoundedResidualDecisionModel model) {
        List<Row> work = new ArrayList<>(); for (String language : new String[] {"ar", "en", "mixed"}) { int n = 0; for (Row r : train) if (r.language.equals(language) && n++ < 64) work.add(r); }
        for (int i = 0; i < 512; i++) { Row r = work.get(i % work.size()); consume(teacher.classify(r.text, 0.6, 0.15)); consume(model.classify(r.text, 0.6, 0.15)); }
        long[][] times = new long[2][8]; for (int round = 0; round < 8; round++) for (int pos = 0; pos < 2; pos++) { int mode = (round + pos) % 2; long start = System.nanoTime(); for (Row r : work) consume(mode == 0 ? teacher.classify(r.text, 0.6, 0.15) : model.classify(r.text, 0.6, 0.15)); times[mode][round] = (System.nanoTime() - start) / work.size(); }
        report.append("{\"physical_phone\":false,\"encoding_included\":true,\"calls_per_batch\":").append(work.size()).append(",\"batches\":8");
        for (int i = 0; i < 2; i++) { report.append(",\"").append(i == 0 ? "teacher" : "residual").append("\":{\"batch_average_ns\":["); for (int j = 0; j < 8; j++) { if (j > 0) report.append(','); report.append(times[i][j]); } Arrays.sort(times[i]); report.append("],\"median_batch_average_ns\":").append((times[i][3] + times[i][4]) / 2).append('}'); } report.append('}');
    }
    private static void consume(TinyDecisionModel.Decision d) { double sum = 0; for (double p : d.probabilitiesCopy()) sum += p * p; sink = sum; }
    private ResidualAdapterExperiment() { }
}
