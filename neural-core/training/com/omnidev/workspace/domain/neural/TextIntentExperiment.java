package com.omnidev.workspace.domain.neural;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Reproducible authored-corpus experiment. No automatic production promotion. */
public final class TextIntentExperiment {
    private static final String[] LABELS = {"repository", "build", "device", "knowledge", "chat"};
    private static final List<String> SPLITS = Arrays.asList("train", "development", "calibration", "holdout");
    private static final List<String> LANGUAGES = Arrays.asList("ar", "en", "mixed");
    private static final class Row {
        final String split, group, language, text;
        final int label;
        final float[] features;
        Row(String[] cells) {
            split = cells[0]; group = cells[1]; language = cells[3]; text = cells[4];
            label = Arrays.asList(LABELS).indexOf(cells[2]);
            if (!SPLITS.contains(split) || !LANGUAGES.contains(language) || label < 0 || group.isEmpty()) {
                throw new IllegalArgumentException("Invalid corpus contract");
            }
            features = BilingualTextEncoder.encode(text);
        }
        OfflineDecisionTrainer.Example example() { return new OfflineDecisionTrainer.Example(group, features, label); }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected corpus path and checkpoint path or --verify");
        Path corpus = Paths.get(args[0]);
        List<Row> rows = load(corpus);
        Map<String, List<OfflineDecisionTrainer.Example>> splits = new LinkedHashMap<>();
        for (String split : SPLITS) {
            List<OfflineDecisionTrainer.Example> examples = new ArrayList<>();
            boolean[] labels = new boolean[LABELS.length];
            for (Row row : rows) if (row.split.equals(split)) { examples.add(row.example()); labels[row.label] = true; }
            for (boolean present : labels) if (!present) throw new IllegalArgumentException("Split missing a class");
            splits.put(split, examples);
        }
        OfflineDecisionTrainer.requireDisjointGroups(splits.get("train"), splits.get("development"),
                splits.get("calibration"), splits.get("holdout"));
        if ("--validate".equals(args[1])) { System.out.println("Corpus split contracts verified"); return; }
        OfflineDecisionTrainer.Model linear = OfflineDecisionTrainer.fit(splits.get("train"), LABELS.length, 0, 200, 42);
        OfflineDecisionTrainer.Model mlp = OfflineDecisionTrainer.fit(splits.get("train"), LABELS.length, 16, 200, 42);
        double linearLoss = OfflineDecisionTrainer.logLoss(linear, splits.get("development"), 1);
        double mlpLoss = OfflineDecisionTrainer.logLoss(mlp, splits.get("development"), 1);
        OfflineDecisionTrainer.Model selected = linearLoss <= mlpLoss ? linear : mlp;
        double temperature = OfflineDecisionTrainer.calibrate(selected, splits.get("calibration"));
        TextIntentModel model = new TextIntentModel(selected.runtime(), LABELS, temperature, BilingualTextEncoder.VERSION);
        float[][] centroids = centroids(rows);
        if ("--verify".equals(args[1])) {
            Class<?> checkpoint = Class.forName("TrainedTextIntentCheckpoint");
            if (!BilingualTextEncoder.VERSION.equals(checkpoint.getField("ENCODER_VERSION").get(null)) ||
                    !Arrays.equals(LABELS, (String[]) checkpoint.getMethod("labels").invoke(null))) {
                throw new AssertionError("Exported text schema mismatch");
            }
            TinyNeuralRuntime loaded = (TinyNeuralRuntime) checkpoint.getMethod("model").invoke(null);
            if (checkpoint.getField("TEMPERATURE").getDouble(null) != temperature) throw new AssertionError("Temperature mismatch");
            for (Row row : rows) if (!Arrays.equals(selected.runtime().infer(row.features), loaded.infer(row.features))) {
                throw new AssertionError("Text checkpoint prediction mismatch");
            }
        } else {
            String metadata = "\n  public static final String ENCODER_VERSION = \"" + BilingualTextEncoder.VERSION + "\";\n" +
                    "  public static String[] labels() { return new String[] {\"repository\",\"build\",\"device\",\"knowledge\",\"chat\"}; }\n";
            String source = selected.javaSource(temperature, "TrainedTextIntentCheckpoint");
            source = source.replace("public final class TrainedTextIntentCheckpoint {", "public final class TrainedTextIntentCheckpoint {" + metadata);
            Files.write(Paths.get(args[1]), source.getBytes(StandardCharsets.UTF_8));
        }
        // All training/selection/calibration decisions are fixed before evaluating holdout.
        System.out.printf(Locale.ROOT, "{\"authored_fixture_only\":true,\"production_eligible\":false," +
                "\"encoder\":\"%s\",\"corpus_sha256\":\"%s\",\"examples\":%d," +
                "\"split_sizes\":[%d,%d,%d,%d],\"selected\":\"%s\",\"parameters\":%d," +
                "\"linear_development_nll\":%.6f,\"mlp_development_nll\":%.6f,\"temperature\":%.2f,\"holdout\":{",
                BilingualTextEncoder.VERSION, sha256(Files.readAllBytes(corpus)), rows.size(),
                splits.get("train").size(), splits.get("development").size(), splits.get("calibration").size(),
                splits.get("holdout").size(), selected == linear ? "linear" : "mlp16", selected.runtime().parameterCount(),
                linearLoss, mlpLoss, temperature);
        List<String> subsets = Arrays.asList("all", "ar", "en", "mixed");
        for (int n = 0; n < subsets.size(); n++) {
            if (n > 0) System.out.print(',');
            evaluate(rows, subsets.get(n), model, centroids);
        }
        System.out.println("}}");
    }

    private static List<Row> load(Path path) throws Exception {
        if (Files.size(path) > 1_000_000) throw new IllegalArgumentException("Corpus exceeds experiment limit");
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).equals("split\tgroup\tlabel\tlanguage\ttext")) throw new IllegalArgumentException("Invalid corpus header");
        List<Row> rows = new ArrayList<>();
        Map<String, String> groupContracts = new HashMap<>(), textSplits = new HashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            String[] cells = lines.get(i).split("\t", -1);
            if (cells.length != 5) throw new IllegalArgumentException("Invalid corpus row");
            Row row = new Row(cells);
            String contract = row.split + ":" + row.label;
            String previous = groupContracts.putIfAbsent(row.group, contract);
            if (previous != null && !previous.equals(contract)) throw new IllegalArgumentException("Group crosses split or label");
            String normalized = BilingualTextEncoder.normalize(row.text).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
            if (normalized.isEmpty()) throw new IllegalArgumentException("Corpus contains empty text");
            previous = textSplits.putIfAbsent(normalized, row.split);
            if (previous != null) throw new IllegalArgumentException("Duplicate normalized text in corpus");
            rows.add(row);
        }
        return rows;
    }

    private static float[][] centroids(List<Row> rows) {
        float[][] values = new float[LABELS.length][BilingualTextEncoder.DIMENSIONS];
        for (Row row : rows) if (row.split.equals("train")) {
            for (int i = 0; i < row.features.length; i++) values[row.label][i] += row.features[i];
        }
        for (float[] vector : values) {
            double norm = 0;
            for (float x : vector) norm += x * x;
            if (norm > 0) for (int i = 0; i < vector.length; i++) vector[i] /= Math.sqrt(norm);
        }
        return values;
    }

    private static void evaluate(List<Row> rows, String language, TextIntentModel model, float[][] centroids) {
        int count = 0, correct = 0, baselineCorrect = 0, accepted = 0, acceptedCorrect = 0;
        int[][] confusion = new int[LABELS.length][LABELS.length];
        double nll = 0, brier = 0;
        for (Row row : rows) if (row.split.equals("holdout") && (language.equals("all") || language.equals(row.language))) {
            count++;
            TinyDecisionModel.Decision decision = model.classify(row.text, 0.60, 0.15);
            double[] probabilities = decision.probabilitiesCopy();
            int best = 0, baseline = 0;
            double bestSimilarity = -Double.MAX_VALUE;
            for (int c = 0; c < LABELS.length; c++) {
                if (probabilities[c] > probabilities[best]) best = c;
                double similarity = 0;
                for (int i = 0; i < row.features.length; i++) similarity += row.features[i] * centroids[c][i];
                if (similarity > bestSimilarity) { bestSimilarity = similarity; baseline = c; }
                double delta = probabilities[c] - (c == row.label ? 1 : 0); brier += delta * delta;
            }
            if (best == row.label) correct++;
            if (baseline == row.label) baselineCorrect++;
            confusion[row.label][best]++;
            nll -= Math.log(Math.max(1e-15, probabilities[row.label]));
            if (decision.status == TinyDecisionModel.Status.CHOICE) { accepted++; if (best == row.label) acceptedCorrect++; }
        }
        if (count == 0) throw new IllegalArgumentException("Empty language evaluation");
        double macroF1 = 0;
        for (int c = 0; c < LABELS.length; c++) {
            int actual = 0, predicted = 0;
            for (int k = 0; k < LABELS.length; k++) { actual += confusion[c][k]; predicted += confusion[k][c]; }
            if (actual + predicted > 0) macroF1 += 2.0 * confusion[c][c] / (actual + predicted);
        }
        System.out.printf(Locale.ROOT, "\"%s\":{\"count\":%d,\"accuracy\":%.6f,\"centroid_accuracy\":%.6f," +
                "\"macro_f1\":%.6f,\"nll\":%.6f,\"brier\":%.6f,\"accepted\":%d,\"accepted_correct\":%d}",
                language, count, (double) correct / count, (double) baselineCorrect / count,
                macroF1 / LABELS.length, nll / count, brier / count, accepted, acceptedCorrect);
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }
}
