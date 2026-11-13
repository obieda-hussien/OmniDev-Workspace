package com.omnidev.workspace.domain.neural;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Train explicit rejection using upstream partitions; test results never select parameters. */
public final class RejectTrainingExperiment {
    private static final String[] LABELS = {"repository", "build", "device", "knowledge", "chat", "__reject__"};
    private static volatile double sink;
    private static final class Row {
        final String split, id, text;
        final int label;
        final float[] features;
        Row(String split, String id, String label, String text) {
            this.split = split; this.id = id; this.text = text;
            this.label = Arrays.asList(LABELS).indexOf(label);
            if (this.label < 0 || id.isEmpty() || !Arrays.asList("train", "development", "calibration", "holdout", "test").contains(split)) {
                throw new IllegalArgumentException("Invalid rejection dataset contract");
            }
            features = BilingualTextEncoder.encode(text);
        }
        OfflineDecisionTrainer.Example example() { return new OfflineDecisionTrainer.Example(id, features, label); }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Expected original, external, output/--verify and challenge paths");
        List<Row> original = read(args[0], "split\tgroup\tlabel\tlanguage\ttext");
        List<Row> external = read(args[1], "split\tid\tlabel\tupstream_intent\ttext");
        List<Row> all = new ArrayList<>(original); all.addAll(external);
        Map<String, String> groups = new HashMap<>(); Set<String> texts = new HashSet<>();
        for (Row row : all) {
            String previous = groups.putIfAbsent(row.id, row.split + ":" + row.label);
            if (previous != null && !previous.equals(row.split + ":" + row.label)) throw new IllegalArgumentException("Group leakage");
            if (!texts.add(canonical(row.text))) throw new IllegalArgumentException("Normalized repeated text");
        }
        List<OfflineDecisionTrainer.Example> train = examples(all, "train"), dev = examples(all, "development"), cal = examples(all, "calibration");
        for (String split : Arrays.asList("train", "development", "calibration")) {
            boolean[] present = new boolean[LABELS.length];
            for (Row row : filter(all, split)) present[row.label] = true;
            for (boolean exists : present) if (!exists) throw new IllegalArgumentException("Missing fit/selection/calibration class");
        }
        OfflineDecisionTrainer.requireDisjointGroups(train, dev, cal, examples(all, "holdout"), examples(all, "test"));
        List<OfflineDecisionTrainer.Example> balanced = balance(all);
        OfflineDecisionTrainer.Model linear = OfflineDecisionTrainer.fit(balanced, LABELS.length, 0, 200, 42);
        OfflineDecisionTrainer.Model mlp = OfflineDecisionTrainer.fit(balanced, LABELS.length, 16, 200, 42);
        double linearNll = OfflineDecisionTrainer.logLoss(linear, dev, 1), mlpNll = OfflineDecisionTrainer.logLoss(mlp, dev, 1);
        OfflineDecisionTrainer.Model selected = linearNll <= mlpNll ? linear : mlp;
        double temperature = OfflineDecisionTrainer.calibrate(selected, cal);
        TinyNeuralRuntime dense = selected.runtime(), sparse = dense.withSparseInput();
        RejectingTextIntentModel candidate = new RejectingTextIntentModel(sparse, LABELS, temperature, BilingualTextEncoder.VERSION);
        Class<?> oldCheckpoint = Class.forName("TrainedTextIntentCheckpoint");
        TextIntentModel baseline = new TextIntentModel((TinyNeuralRuntime) oldCheckpoint.getMethod("model").invoke(null),
                (String[]) oldCheckpoint.getMethod("labels").invoke(null), oldCheckpoint.getField("TEMPERATURE").getDouble(null),
                (String) oldCheckpoint.getField("ENCODER_VERSION").get(null));
        for (Row row : all) if (!Arrays.equals(dense.infer(row.features), sparse.infer(row.features))) throw new AssertionError("Sparse parity");
        if (args[2].equals("--verify")) {
            Class<?> saved = Class.forName("TrainedRejectIntentCheckpoint");
            TinyNeuralRuntime loaded = (TinyNeuralRuntime) saved.getMethod("model").invoke(null);
            if (!Arrays.equals(LABELS, (String[]) saved.getMethod("labels").invoke(null)) ||
                    !BilingualTextEncoder.VERSION.equals(saved.getField("ENCODER_VERSION").get(null)) ||
                    temperature != saved.getField("TEMPERATURE").getDouble(null)) throw new AssertionError("Checkpoint schema parity");
            for (Row row : all) if (!Arrays.equals(dense.infer(row.features), loaded.infer(row.features))) throw new AssertionError("Checkpoint parity");
        } else {
            String source = selected.javaSource(temperature, "TrainedRejectIntentCheckpoint");
            String metadata = "\n public static final String ENCODER_VERSION = \"" + BilingualTextEncoder.VERSION + "\";\n" +
                    " public static String[] labels() { return new String[]{\"repository\",\"build\",\"device\",\"knowledge\",\"chat\",\"__reject__\"}; }\n";
            source = source.replace("public final class TrainedRejectIntentCheckpoint {", "public final class TrainedRejectIntentCheckpoint {" + metadata);
            Files.write(Paths.get(args[2]), source.getBytes(StandardCharsets.UTF_8));
        }
        // No changes to architecture, temperature or thresholds after this point.
        System.out.printf(Locale.ROOT, "{\"rejection_experiment\":true,\"production_eligible\":false,\"parameters\":%d," +
                "\"selected\":\"%s\",\"train_unique\":%d,\"train_balanced\":%d,\"development\":%d,\"calibration\":%d," +
                "\"linear_dev_nll\":%.6f,\"mlp_dev_nll\":%.6f,\"temperature\":%.2f,\"evaluations\":{",
                dense.parameterCount(), selected == linear ? "linear" : "mlp16", train.size(), balanced.size(), dev.size(), cal.size(), linearNll, mlpNll, temperature);
        evaluate("external_english_test", filter(external, "test"), baseline, candidate);
        System.out.print(','); evaluate("old_authored_regression", filter(original, "holdout"), baseline, candidate);
        List<Row> challenge = new ArrayList<>();
        List<String> lines = Files.readAllLines(Paths.get(args[3]), StandardCharsets.UTF_8);
        if (!lines.remove(0).equals("id\tkind\tlanguage\texpected\ttext")) throw new IllegalArgumentException("Invalid challenge header");
        for (String line : lines) {
            String[] c = line.split("\t", -1);
            if (c.length != 5) throw new IllegalArgumentException("Invalid challenge row");
            challenge.add(new Row("test", c[0], c[3].equals("abstain") ? "__reject__" : c[3], c[4]));
        }
        System.out.print(','); evaluate("old_challenge_regression_only", challenge, baseline, candidate);
        System.out.print("},\"host_kernel_benchmark\":");
        benchmark(dense, sparse, filter(original, "train"));
        System.out.println("}");
    }

    private static void evaluate(String name, List<Row> rows, TextIntentModel baseline, RejectingTextIntentModel candidate) {
        DecisionRiskMetrics old = new DecisionRiskMetrics(5), updated = new DecisionRiskMetrics(6);
        for (Row row : rows) {
            int expected = row.label == 5 ? -1 : row.label;
            old.add(baseline.classify(row.text, 0.60, 0.15), expected);
            updated.add(candidate.classify(row.text, 0.60, 0.15), expected);
        }
        System.out.print("\"" + name + "\":{\"baseline\":"); print(old); System.out.print(",\"candidate\":"); print(updated); System.out.print('}');
    }
    private static void print(DecisionRiskMetrics m) {
        System.out.printf(Locale.ROOT, "{\"count\":%d,\"known\":%d,\"known_raw_correct\":%d,\"accepted\":%d," +
                "\"accepted_errors\":%d,\"required_refusals\":%d,\"refused\":%d,\"coverage\":%s,\"selective_error\":%s}",
                m.total(), m.known(), m.correct(), m.accepted(), m.acceptedErrors(), m.requiredAbstentions(), m.refused(), m.coverage(), m.selectiveError());
    }
    private static void benchmark(TinyNeuralRuntime dense, TinyNeuralRuntime sparse, List<Row> rows) {
        // Interleave order, warm both paths, measure batch means; not phone latency or end-to-end text time.
        for (int i = 0; i < 4000; i++) { sink += checksum(dense.infer(rows.get(i % rows.size()).features)); sink += checksum(sparse.infer(rows.get(i % rows.size()).features)); }
        long[] a = new long[12], b = new long[12];
        for (int round = 0; round < a.length; round++) {
            if (round % 2 == 0) { a[round] = timing(dense, rows); b[round] = timing(sparse, rows); }
            else { b[round] = timing(sparse, rows); a[round] = timing(dense, rows); }
        }
        Arrays.sort(a); Arrays.sort(b);
        System.out.printf(Locale.ROOT, "{\"phone_measurement\":false,\"encoding_included\":false," +
                "\"dense_batch_median_ns_per_inference\":%d,\"sparse_batch_median_ns_per_inference\":%d}", a[6], b[6]);
    }
    private static long timing(TinyNeuralRuntime model, List<Row> rows) {
        long start = System.nanoTime(); double sum = 0;
        for (int i = 0; i < 1000; i++) sum += checksum(model.infer(rows.get(i % rows.size()).features));
        long elapsed = System.nanoTime() - start; sink = sum; return elapsed / 1000;
    }
    private static double checksum(float[] values) {
        double result = 0; for (float value : values) result += value; return result;
    }
    private static List<Row> read(String file, String header) throws Exception {
        Path path = Paths.get(file);
        if (Files.size(path) > 1_000_000) throw new IllegalArgumentException("Bounded dataset required");
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.remove(0).equals(header)) throw new IllegalArgumentException("Invalid dataset header");
        List<Row> rows = new ArrayList<>();
        for (String line : lines) { String[] c = line.split("\t", -1); if (c.length != 5) throw new IllegalArgumentException("Invalid row"); rows.add(new Row(c[0], c[1], c[2], c[4])); }
        return rows;
    }
    private static List<Row> filter(List<Row> rows, String split) {
        List<Row> result = new ArrayList<>(); for (Row row : rows) if (row.split.equals(split)) result.add(row); return result;
    }
    private static List<OfflineDecisionTrainer.Example> examples(List<Row> rows, String split) {
        List<OfflineDecisionTrainer.Example> result = new ArrayList<>(); for (Row row : filter(rows, split)) result.add(row.example()); return result;
    }
    private static List<OfflineDecisionTrainer.Example> balance(List<Row> rows) {
        List<List<Row>> classes = new ArrayList<>(); for (int c = 0; c < LABELS.length; c++) classes.add(new ArrayList<>());
        int maximum = 0;
        for (Row row : filter(rows, "train")) classes.get(row.label).add(row);
        for (List<Row> group : classes) { if (group.isEmpty()) throw new IllegalArgumentException("Missing training class"); maximum = Math.max(maximum, group.size()); }
        List<OfflineDecisionTrainer.Example> result = new ArrayList<>();
        for (List<Row> group : classes) for (int i = 0; i < maximum; i++) result.add(group.get(i % group.size()).example());
        return result;
    }
    private static String canonical(String text) { return BilingualTextEncoder.normalize(text).replaceAll("[^\\p{L}\\p{N}]+", " ").trim(); }
}
