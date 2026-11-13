package com.omnidev.workspace.domain.neural;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import com.omnidev.workspace.domain.neural.BilingualRefusalCorpus.Row;

/** Frozen protocol: six bounded fits, language-balanced development selection, no test-driven retries. */
public final class BilingualRefusalExperiment {
    private static final String[] BINARY = {"supported", "refuse"};
    private static final double[] CUTOFFS = {0.5, 0.6, 0.7, 0.8, 0.9, 0.95, 0.975, 0.99, 0.999, 1};
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected corpus, checkpoint/--verify, report path");
        List<Row> all = BilingualRefusalCorpus.load(Paths.get(args[0]));
        List<Row> train = BilingualRefusalCorpus.split(all, "train"), dev = BilingualRefusalCorpus.split(all, "development"), cal = BilingualRefusalCorpus.split(all, "calibration");
        OfflineDecisionTrainer.requireDisjointGroups(BilingualRefusalCorpus.examples(train), BilingualRefusalCorpus.examples(dev),
                BilingualRefusalCorpus.examples(cal), BilingualRefusalCorpus.examples(BilingualRefusalCorpus.split(all, "test")),
                BilingualRefusalCorpus.examples(BilingualRefusalCorpus.split(all, "regression")));
        Class<?> original = Class.forName("TrainedTextIntentCheckpoint"), previous = Class.forName("TrainedRefusalGateCheckpoint");
        TinyNeuralRuntime domain = ((TinyNeuralRuntime) original.getMethod("model").invoke(null)).withSparseInput();
        String[] labels = (String[]) original.getMethod("labels").invoke(null);
        double domainTemperature = original.getField("TEMPERATURE").getDouble(null);
        TextIntentModel base = new TextIntentModel(domain, labels, domainTemperature, BilingualTextEncoder.VERSION);
        GatedTextIntentModel prior = gated(domain, labels, domainTemperature, (TinyNeuralRuntime) previous.getMethod("model").invoke(null),
                previous.getField("TEMPERATURE").getDouble(null), previous.getField("CUTOFF").getDouble(null));
        OfflineDecisionTrainer.Model selected = null; double bestLoss = Double.POSITIVE_INFINITY;
        int bestWidth = -1, bestEpoch = 0; long bestSeed = 0; StringBuilder candidates = new StringBuilder("[");
        List<OfflineDecisionTrainer.Example> balanced = BilingualRefusalCorpus.balanced(train);
        List<List<OfflineDecisionTrainer.Example>> validation = new ArrayList<>();
        for (List<Row> stratum : BilingualRefusalCorpus.strata(dev).values()) validation.add(BilingualRefusalCorpus.examples(stratum));
        for (int hidden : new int[] {0, 16, 32}) for (long seed : new long[] {42, 97}) {
            OfflineDecisionTrainer.TrainingResult fitted = OfflineDecisionTrainer.fitEarlyStopped(balanced, 2, hidden, 200, seed, validation);
            OfflineDecisionTrainer.Model model = fitted.model;
            double loss = BilingualRefusalCorpus.loss(model, dev, 1);
            if (candidates.length() > 1) candidates.append(',');
            candidates.append(String.format(Locale.ROOT, "{\"hidden\":%d,\"seed\":%d,\"development_macro_nll\":%.9f,\"parameters\":%d,\"best_epoch\":%d,\"epochs_run\":%d}",
                    hidden, seed, loss, model.runtime().parameterCount(), fitted.bestEpoch, fitted.epochsRun));
            System.err.printf(Locale.ROOT, "Finished training hidden=%d seed=%d best_epoch=%d development_macro_nll=%.6f%n", hidden, seed, fitted.bestEpoch, loss);
            if (loss < bestLoss) { selected = model; bestLoss = loss; bestWidth = hidden; bestSeed = seed; bestEpoch = fitted.bestEpoch; }
        }
        candidates.append(']');
        double temperature = 1, bestCal = Double.POSITIVE_INFINITY;
        // Split disjointness was checked before fitting. Only calibration selects temperature.
        for (double t : new double[] {0.5, 0.75, 1, 1.5, 2, 3, 5}) {
            double loss = BilingualRefusalCorpus.loss(selected, cal, t);
            if (loss < bestCal) { bestCal = loss; temperature = t; }
        }
        TinyDecisionModel binary = new TinyDecisionModel(selected.runtime().withSparseInput(), BINARY, temperature);
        double cutoff = cutoff(binary, base, dev);
        GatedTextIntentModel candidate = gated(domain, labels, domainTemperature, selected.runtime(), temperature, cutoff);
        GatedTextIntentModel cascade = new GatedTextIntentModel(domain, labels, domainTemperature, BilingualTextEncoder.VERSION,
                new GatedTextIntentModel.RefusalHead(((TinyNeuralRuntime) previous.getMethod("model").invoke(null)).withSparseInput(),
                        previous.getField("TEMPERATURE").getDouble(null), previous.getField("CUTOFF").getDouble(null)),
                new GatedTextIntentModel.RefusalHead(selected.runtime().withSparseInput(), temperature, cutoff));
        boolean cascadeFeasible = retainsKnownChoices(base, cascade, dev);
        // Complete selection/export before any test or regression scoring is emitted.
        String metadata = "\n public static final String ENCODER_VERSION = \"" + BilingualTextEncoder.VERSION + "\";\n" +
                " public static final String CORPUS_SHA256 = \"" + BilingualRefusalCorpus.SHA256 + "\";\n" +
                " public static final double CUTOFF = " + cutoff + ";\n public static String[] labels() { return new String[]{\"supported\",\"refuse\"}; }\n";
        if (args[1].equals("--verify")) {
            Class<?> saved = Class.forName("TrainedBilingualRefusalCheckpoint");
            TinyNeuralRuntime loaded = (TinyNeuralRuntime) saved.getMethod("model").invoke(null);
            if (!BilingualRefusalCorpus.SHA256.equals(saved.getField("CORPUS_SHA256").get(null)) ||
                    !BilingualTextEncoder.VERSION.equals(saved.getField("ENCODER_VERSION").get(null)) ||
                    !Arrays.equals(BINARY, (String[]) saved.getMethod("labels").invoke(null)) ||
                    temperature != saved.getField("TEMPERATURE").getDouble(null) || cutoff != saved.getField("CUTOFF").getDouble(null)) throw new AssertionError("Export metadata mismatch");
            for (Row row : all) if (!Arrays.equals(selected.runtime().infer(row.features), loaded.infer(row.features))) throw new AssertionError("Checkpoint logits mismatch");
        } else {
            String source = selected.javaSource(temperature, "TrainedBilingualRefusalCheckpoint")
                    .replace("public final class TrainedBilingualRefusalCheckpoint {", "public final class TrainedBilingualRefusalCheckpoint {" + metadata);
            Files.write(Paths.get(args[1]), source.getBytes(StandardCharsets.UTF_8));
        }
        StringBuilder report = new StringBuilder(String.format(Locale.ROOT,
                "{\"production_eligible\":false,\"authored_arabic_not_independent\":true,\"corpus_sha256\":\"%s\",\"train_unique\":%d,\"balanced_train\":%d,\"candidates\":%s," +
                "\"selected_hidden\":%d,\"selected_seed\":%d,\"selected_epoch\":%d,\"temperature\":%s,\"cutoff\":%s,\"gate_parameters\":%d,\"total_parameters\":%d,",
                BilingualRefusalCorpus.SHA256, train.size(), balanced.size(), candidates, bestWidth, bestSeed, bestEpoch, temperature, cutoff,
                selected.runtime().parameterCount(), domain.parameterCount() + selected.runtime().parameterCount()));
        report.append("\"cascade_development_feasible\":").append(cascadeFeasible).append(",\"cascade_test_status\":\"followup_on_now_observed_regressions\",\"evaluations\":{");
        evaluate(report, "development", dev, base, prior, candidate, cascade);
        for (String split : Arrays.asList("test", "regression")) {
            List<Row> subset = BilingualRefusalCorpus.split(all, split);
            report.append(','); evaluate(report, split, subset, base, prior, candidate, cascade);
            for (String language : Arrays.asList("ar", "en", "mixed")) {
                List<Row> group = new ArrayList<>(); for (Row row : subset) if (row.language.equals(language)) group.add(row);
                report.append(','); evaluate(report, split + "_" + language, group, base, prior, candidate, cascade);
            }
        }
        Set<String> kinds = new TreeSet<>(); for (Row row : all) if (row.split.equals("test") || row.split.equals("regression")) kinds.add(row.kind);
        for (String kind : kinds) {
            List<Row> rows = new ArrayList<>(); for (Row row : all) if (row.kind.equals(kind) && (row.split.equals("test") || row.split.equals("regression"))) rows.add(row);
            report.append(','); evaluate(report, kind, rows, base, prior, candidate, cascade);
        }
        report.append(','); evaluate(report, "old_challenge", BilingualRefusalCorpus.challenges(Paths.get("neural-core/fixtures/decision-challenges.tsv")), base, prior, candidate, cascade);
        report.append("}}\n"); Files.write(Paths.get(args[2]), report.toString().getBytes(StandardCharsets.UTF_8));
        System.out.print(report);
    }
    private static GatedTextIntentModel gated(TinyNeuralRuntime domain, String[] labels, double temp, TinyNeuralRuntime gate, double gateTemp, double cutoff) {
        return new GatedTextIntentModel(domain, labels, temp, gate.withSparseInput(), gateTemp, cutoff, BilingualTextEncoder.VERSION);
    }
    private static double cutoff(TinyDecisionModel gate, TextIntentModel base, List<Row> dev) {
        double chosen = Double.NaN, bestRecall = -1; int bestFalse = Integer.MAX_VALUE;
        for (double threshold : CUTOFFS) {
            Map<String, int[]> strata = new TreeMap<>(), known = new TreeMap<>();
            for (Row row : dev) {
                if (base.classify(row.text, 0.6, 0.15).status != TinyDecisionModel.Status.CHOICE) continue;
                boolean refused = gate.decide(row.features, 0, 0).probabilitiesCopy()[1] >= threshold;
                if (row.label == 0) { int[] n = known.computeIfAbsent(row.language, key -> new int[2]); n[0]++; if (refused) n[1]++; }
                else { int[] n = strata.computeIfAbsent(row.kind + ":" + row.language, key -> new int[2]); n[0]++; if (refused) n[1]++; }
            }
            boolean feasible = known.size() == 3; int falseRefusals = 0;
            for (int[] n : known.values()) { feasible &= n[1] <= (int) Math.floor(n[0] * 0.05); falseRefusals += n[1]; }
            double recall = 0; for (int[] n : strata.values()) recall += (double) n[1] / n[0]; recall /= strata.size();
            if (feasible && (recall > bestRecall || recall == bestRecall && falseRefusals <= bestFalse)) {
                chosen = threshold; bestRecall = recall; bestFalse = falseRefusals;
            }
        }
        if (!Double.isFinite(chosen)) throw new IllegalStateException("No development-feasible cutoff; no candidate promoted");
        return chosen;
    }
    private static boolean retainsKnownChoices(TextIntentModel base, GatedTextIntentModel cascade, List<Row> dev) {
        Map<String, int[]> counts = new TreeMap<>();
        for (Row row : dev) if (row.label == 0 && base.classify(row.text, 0.6, 0.15).status == TinyDecisionModel.Status.CHOICE) {
            int[] n = counts.computeIfAbsent(row.language, key -> new int[2]); n[0]++;
            if (cascade.classify(row.text, 0.6, 0.15).status == TinyDecisionModel.Status.ABSTAIN) n[1]++;
        }
        if (counts.size() != 3) return false;
        for (int[] n : counts.values()) if (n[1] > (int) Math.floor(n[0] * 0.05)) return false;
        return true;
    }
    private static void evaluate(StringBuilder out, String name, List<Row> rows, TextIntentModel base, GatedTextIntentModel prior, GatedTextIntentModel candidate, GatedTextIntentModel cascade) {
        int known = 0, required = 0, rawCorrect = 0; int[] accepted = new int[4], knownAccepted = new int[4], acceptedCorrect = new int[4], refused = new int[4];
        for (Row row : rows) {
            TinyDecisionModel.Decision[] d = {base.classify(row.text, 0.6, 0.15), prior.classify(row.text, 0.6, 0.15), candidate.classify(row.text, 0.6, 0.15), cascade.classify(row.text, 0.6, 0.15)};
            if (row.label == 0) known++; else required++;
            double[] probabilities = d[0].probabilitiesCopy(); int best = 0;
            for (int i = 1; i < probabilities.length; i++) if (probabilities[i] > probabilities[best]) best = i;
            if (Arrays.asList("repository", "build", "device", "knowledge", "chat").get(best).equals(row.expectedDomain)) rawCorrect++;
            for (int i = 0; i < 4; i++) {
                if (!Arrays.equals(d[0].probabilitiesCopy(), d[i].probabilitiesCopy()) || d[i].status == TinyDecisionModel.Status.CHOICE && !Objects.equals(d[0].choice, d[i].choice)) throw new AssertionError("Frozen domain changed");
                if (d[i].status == TinyDecisionModel.Status.CHOICE) { accepted[i]++; if (row.label == 0) knownAccepted[i]++;
                    if (Objects.equals(d[i].choice, row.expectedDomain)) acceptedCorrect[i]++; }
                else if (row.label == 1) refused[i]++;
            }
            if (d[1].status == TinyDecisionModel.Status.ABSTAIN && d[3].status != TinyDecisionModel.Status.ABSTAIN ||
                    d[2].status == TinyDecisionModel.Status.ABSTAIN && d[3].status != TinyDecisionModel.Status.ABSTAIN) throw new AssertionError("Cascade undid a refusal");
        }
        out.append('"').append(name).append("\":{\"known\":").append(known).append(",\"raw_domain_correct\":").append(rawCorrect).append(",\"required_refusals\":").append(required);
        String[] names = {"base", "prior_gate", "candidate", "cascade"};
        for (int i = 0; i < 4; i++) out.append(String.format(Locale.ROOT, ",\"%s\":{\"accepted\":%d,\"known_accepted\":%d,\"accepted_correct\":%d,\"accepted_errors\":%d,\"refused\":%d}",
                names[i], accepted[i], knownAccepted[i], acceptedCorrect[i], accepted[i] - acceptedCorrect[i], refused[i]));
        out.append('}');
    }
}
