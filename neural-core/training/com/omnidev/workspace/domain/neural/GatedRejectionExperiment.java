package com.omnidev.workspace.domain.neural;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import com.omnidev.workspace.domain.neural.RejectTrainingExperiment.Row;

/** Counterfactual head isolation experiment; previously observed tests are regressions, not unseen data. */
public final class GatedRejectionExperiment {
    private static volatile double sink;

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Expected original, external, output/--verify, challenge");
        List<Row> original = RejectTrainingExperiment.read(args[0], "split\tgroup\tlabel\tlanguage\ttext");
        List<Row> external = RejectTrainingExperiment.read(args[1], "split\tid\tlabel\tupstream_intent\ttext");
        List<Row> all = new ArrayList<>(original); all.addAll(external);
        validate(all);
        List<OfflineDecisionTrainer.Example> train = examples(all, "train"), dev = examples(all, "development"), cal = examples(all, "calibration");
        OfflineDecisionTrainer.requireDisjointGroups(train, dev, cal, examples(all, "test"), examples(all, "holdout"));
        List<OfflineDecisionTrainer.Example> balanced = balanced(all);
        OfflineDecisionTrainer.Model linear = OfflineDecisionTrainer.fit(balanced, 2, 0, 200, 42);
        OfflineDecisionTrainer.Model mlp = OfflineDecisionTrainer.fit(balanced, 2, 16, 200, 42);
        double a = OfflineDecisionTrainer.logLoss(linear, dev, 1), b = OfflineDecisionTrainer.logLoss(mlp, dev, 1);
        OfflineDecisionTrainer.Model gate = a <= b ? linear : mlp;
        double temperature = OfflineDecisionTrainer.calibrate(gate, cal);
        TinyNeuralRuntime gateNetwork = gate.runtime().withSparseInput();
        TinyDecisionModel gateDecision = new TinyDecisionModel(gateNetwork, new String[] {"supported", "refuse"}, temperature);
        double cutoff = selectCutoff(gateDecision, RejectTrainingExperiment.filter(all, "development"));
        Class<?> frozen = Class.forName("TrainedTextIntentCheckpoint");
        TinyNeuralRuntime domain = ((TinyNeuralRuntime) frozen.getMethod("model").invoke(null)).withSparseInput();
        String[] labels = (String[]) frozen.getMethod("labels").invoke(null);
        String version = (String) frozen.getField("ENCODER_VERSION").get(null);
        double domainTemperature = frozen.getField("TEMPERATURE").getDouble(null);
        TextIntentModel baseline = new TextIntentModel(domain, labels, domainTemperature, version);
        GatedTextIntentModel candidate = new GatedTextIntentModel(domain, labels, domainTemperature, gateNetwork, temperature, cutoff, version);
        if (args[2].equals("--verify")) {
            Class<?> saved = Class.forName("TrainedRefusalGateCheckpoint");
            TinyNeuralRuntime reloaded = (TinyNeuralRuntime) saved.getMethod("model").invoke(null);
            if (!version.equals(saved.getField("ENCODER_VERSION").get(null)) ||
                    temperature != saved.getField("TEMPERATURE").getDouble(null) || cutoff != saved.getField("CUTOFF").getDouble(null) ||
                    !Arrays.equals(new String[] {"supported", "refuse"}, (String[]) saved.getMethod("labels").invoke(null))) {
                throw new AssertionError("Refusal checkpoint metadata mismatch");
            }
            for (Row row : all) if (!Arrays.equals(gateNetwork.infer(row.features), reloaded.infer(row.features))) throw new AssertionError("Refusal checkpoint parity");
        } else {
            String metadata = "\n public static final String ENCODER_VERSION = \"" + version + "\";\n public static final double CUTOFF = " + cutoff + ";\n" +
                    " public static String[] labels() { return new String[]{\"supported\",\"refuse\"}; }\n";
            String source = gate.javaSource(temperature, "TrainedRefusalGateCheckpoint")
                    .replace("public final class TrainedRefusalGateCheckpoint {", "public final class TrainedRefusalGateCheckpoint {" + metadata);
            Files.write(Paths.get(args[2]), source.getBytes(StandardCharsets.UTF_8));
        }
        // Tests below have already been observed in earlier versions; never select settings from them.
        System.out.printf(Locale.ROOT, "{\"isolated_refusal_gate\":true,\"production_eligible\":false," +
                "\"test_status\":\"previously_observed_regressions\",\"selected\":\"%s\",\"gate_parameters\":%d,\"total_parameters\":%d," +
                "\"temperature\":%s,\"cutoff\":%s,\"development_known_false_refusal_budget\":0,\"evaluations\":{",
                gate == linear ? "linear" : "mlp16", gateNetwork.parameterCount(), gateNetwork.parameterCount() + domain.parameterCount(), temperature, cutoff);
        evaluate("external_english", RejectTrainingExperiment.filter(external, "test"), baseline, candidate);
        System.out.print(','); evaluate("old_authored", RejectTrainingExperiment.filter(original, "holdout"), baseline, candidate);
        List<Row> challenge = new ArrayList<>();
        List<String> lines = Files.readAllLines(Paths.get(args[3]), StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.remove(0).equals("id\tkind\tlanguage\texpected\ttext")) throw new IllegalArgumentException("Invalid challenge header");
        for (String line : lines) { String[] c = line.split("\t", -1); if (c.length != 5) throw new IllegalArgumentException("Invalid challenge row");
            challenge.add(new Row("test", c[0], c[3].equals("abstain") ? "__reject__" : c[3], c[4])); }
        System.out.print(','); evaluate("old_challenge", challenge, baseline, candidate);
        System.out.print("},\"host_whole_text_benchmark\":");
        benchmark(original, baseline, candidate, gateDecision, cutoff);
        System.out.println("}");
    }

    private static double selectCutoff(TinyDecisionModel gate, List<Row> development) {
        double cutoff = 1; int best = -1;
        for (double threshold : new double[] {0.5, 0.6, 0.7, 0.8, 0.9, 0.95, 0.99, 1}) {
            int falseRefusals = 0, trueRefusals = 0;
            for (Row row : development) if (gate.decide(row.features, 0, 0).probabilitiesCopy()[1] >= threshold) {
                if (row.label == 5) trueRefusals++; else falseRefusals++;
            }
            if (falseRefusals == 0 && trueRefusals >= best) { best = trueRefusals; cutoff = threshold; }
        }
        if (best < 0) throw new IllegalStateException("No development-feasible refusal threshold");
        return cutoff;
    }

    private static void evaluate(String name, List<Row> rows, TextIntentModel baseline, GatedTextIntentModel candidate) {
        DecisionRiskMetrics old = new DecisionRiskMetrics(5), updated = new DecisionRiskMetrics(5);
        int oldKnownAccepted = 0, newKnownAccepted = 0;
        for (Row row : rows) {
            TinyDecisionModel.Decision before = baseline.classify(row.text, 0.60, 0.15), after = candidate.classify(row.text, 0.60, 0.15);
            if (!Arrays.equals(before.probabilitiesCopy(), after.probabilitiesCopy()) ||
                    after.status == TinyDecisionModel.Status.CHOICE && !Objects.equals(before.choice, after.choice)) {
                throw new AssertionError("Refusal gate changed frozen domain predictions");
            }
            int expected = row.label == 5 ? -1 : row.label;
            old.add(before, expected); updated.add(after, expected);
            if (expected >= 0) { if (before.status == TinyDecisionModel.Status.CHOICE) oldKnownAccepted++;
                if (after.status == TinyDecisionModel.Status.CHOICE) newKnownAccepted++; }
        }
        System.out.print("\"" + name + "\":{\"baseline\":"); print(old, oldKnownAccepted);
        System.out.print(",\"gated\":"); print(updated, newKnownAccepted); System.out.print('}');
    }
    private static void print(DecisionRiskMetrics m, int knownAccepted) {
        System.out.printf(Locale.ROOT, "{\"count\":%d,\"known\":%d,\"raw_correct\":%d,\"known_accepted\":%d," +
                "\"accepted_correct\":%d,\"accepted\":%d,\"accepted_errors\":%d,\"required_refusals\":%d,\"refused\":%d}",
                m.total(), m.known(), m.correct(), knownAccepted, m.accepted() - m.acceptedErrors(), m.accepted(), m.acceptedErrors(), m.requiredAbstentions(), m.refused());
    }
    private static void benchmark(List<Row> rows, TextIntentModel baseline, GatedTextIntentModel candidate, TinyDecisionModel gate, double cutoff) {
        List<Row> training = RejectTrainingExperiment.filter(rows, "train");
        for (int i = 0; i < 1000; i++) { String text = training.get(i % training.size()).text;
            consume(candidate.classify(text, 0.6, 0.15)); consume(naive(text, baseline, gate, cutoff)); consume(baseline.classify(text, 0.6, 0.15)); }
        long[] a = new long[9], b = new long[9], c = new long[9];
        for (int i = 0; i < a.length; i++) {
            for (int position = 0; position < 3; position++) {
                int mode = (i + position) % 3;
                long value = time(training, baseline, candidate, gate, cutoff, mode);
                if (mode == 0) c[i] = value; else if (mode == 1) a[i] = value; else b[i] = value;
            }
        }
        Arrays.sort(a); Arrays.sort(b); Arrays.sort(c);
        System.out.printf(Locale.ROOT, "{\"phone_measurement\":false,\"encoding_included\":true," +
                "\"original_single_head_batch_median_ns\":%d,\"naive_two_encoding_batch_median_ns\":%d," +
                "\"shared_encoding_batch_median_ns\":%d}", c[4], a[4], b[4]);
    }
    private static long time(List<Row> rows, TextIntentModel baseline, GatedTextIntentModel candidate, TinyDecisionModel gate, double cutoff, int mode) {
        long start = System.nanoTime();
        for (int i = 0; i < 300; i++) { String text = rows.get(i % rows.size()).text;
            consume(mode == 0 ? baseline.classify(text, 0.6, 0.15) : mode == 2 ? candidate.classify(text, 0.6, 0.15) : naive(text, baseline, gate, cutoff)); }
        return (System.nanoTime() - start) / 300;
    }
    private static TinyDecisionModel.Decision naive(String text, TextIntentModel baseline, TinyDecisionModel gate, double cutoff) {
        TinyDecisionModel.Decision original = baseline.classify(text, 0.6, 0.15);
        if (original.status == TinyDecisionModel.Status.ABSTAIN) return original;
        return gate.decide(BilingualTextEncoder.encode(text), 0, 0).probabilitiesCopy()[1] >= cutoff ? original.asAbstention() : original;
    }
    private static void consume(TinyDecisionModel.Decision decision) { double sum = 0; for (double p : decision.probabilitiesCopy()) sum += p * p; sink = sum; }
    private static List<OfflineDecisionTrainer.Example> examples(List<Row> rows, String split) {
        List<OfflineDecisionTrainer.Example> result = new ArrayList<>();
        for (Row row : RejectTrainingExperiment.filter(rows, split)) result.add(new OfflineDecisionTrainer.Example(row.id, row.features, row.label == 5 ? 1 : 0));
        return result;
    }
    private static List<OfflineDecisionTrainer.Example> balanced(List<Row> rows) {
        List<Row> yes = new ArrayList<>(), no = new ArrayList<>();
        for (Row row : RejectTrainingExperiment.filter(rows, "train")) (row.label == 5 ? no : yes).add(row);
        if (yes.isEmpty() || no.isEmpty()) throw new IllegalArgumentException("Missing binary class");
        List<OfflineDecisionTrainer.Example> result = new ArrayList<>(); int n = Math.max(yes.size(), no.size());
        for (List<Row> group : Arrays.asList(yes, no)) for (int i = 0; i < n; i++) {
            Row row = group.get(i % group.size()); result.add(new OfflineDecisionTrainer.Example(row.id, row.features, row.label == 5 ? 1 : 0)); }
        return result;
    }
    private static void validate(List<Row> rows) {
        Map<String, String> ids = new HashMap<>(); Set<String> texts = new HashSet<>();
        for (Row row : rows) {
            String previous = ids.putIfAbsent(row.id, row.split + ":" + row.label);
            if (previous != null && !previous.equals(row.split + ":" + row.label)) throw new IllegalArgumentException("Group leakage");
            if (!texts.add(BilingualTextEncoder.normalize(row.text).replaceAll("[^\\p{L}\\p{N}]+", " ").trim())) throw new IllegalArgumentException("Repeated text");
        }
        for (String split : Arrays.asList("train", "development", "calibration")) {
            boolean yes = false, no = false;
            for (Row row : RejectTrainingExperiment.filter(rows, split)) { if (row.label == 5) no = true; else yes = true; }
            if (!yes || !no) throw new IllegalArgumentException("Missing binary selection/calibration class");
        }
    }
}
