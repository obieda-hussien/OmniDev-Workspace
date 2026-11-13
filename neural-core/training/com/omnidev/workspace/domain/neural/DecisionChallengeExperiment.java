package com.omnidev.workspace.domain.neural;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Evaluates a frozen compiled checkpoint; never trains or selects thresholds on challenge rows. */
public final class DecisionChallengeExperiment {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected challenge and original corpus paths");
        Class<?> checkpoint = Class.forName("TrainedTextIntentCheckpoint");
        String[] labels = (String[]) checkpoint.getMethod("labels").invoke(null);
        TextIntentModel model = new TextIntentModel((TinyNeuralRuntime) checkpoint.getMethod("model").invoke(null),
                labels, checkpoint.getField("TEMPERATURE").getDouble(null),
                (String) checkpoint.getField("ENCODER_VERSION").get(null));
        List<String> challenge = read(Paths.get(args[0]));
        List<String> original = read(Paths.get(args[1]));
        if (!original.get(0).equals("split\tgroup\tlabel\tlanguage\ttext")) throw new IllegalArgumentException("Invalid original header");
        if (!challenge.remove(0).equals("id\tkind\tlanguage\texpected\ttext")) throw new IllegalArgumentException("Invalid challenge header");
        Set<String> texts = new HashSet<>(), ids = new HashSet<>();
        for (int i = 1; i < original.size(); i++) {
            String[] cells = original.get(i).split("\t", -1);
            if (cells.length != 5) throw new IllegalArgumentException("Invalid original row");
            texts.add(canonical(cells[4]));
        }
        Map<String, DecisionRiskMetrics> metrics = new LinkedHashMap<>();
        for (String kind : Arrays.asList("all", "known", "ambiguous", "out_of_scope")) metrics.put(kind, new DecisionRiskMetrics(labels.length));
        List<String[]> rows = new ArrayList<>();
        for (String line : challenge) {
            String[] cells = line.split("\t", -1);
            if (cells.length != 5 || cells[0].trim().isEmpty() || !ids.add(cells[0]) ||
                    !Arrays.asList("ar", "en", "mixed").contains(cells[2]) || !metrics.containsKey(cells[1]) || cells[1].equals("all")) {
                throw new IllegalArgumentException("Invalid challenge row");
            }
            int expected = Arrays.asList(labels).indexOf(cells[3]);
            if ((cells[1].equals("known") && expected < 0) ||
                    (!cells[1].equals("known") && !cells[3].equals("abstain")) ||
                    canonical(cells[4]).isEmpty() || !texts.add(canonical(cells[4]))) {
                throw new IllegalArgumentException("Invalid expectation or repeated challenge text");
            }
            rows.add(cells);
        }
        // Reject invalid datasets before making predictions or printing partial results.
        for (String kind : Arrays.asList("known", "ambiguous", "out_of_scope")) {
            boolean present = false;
            for (String[] row : rows) if (row[1].equals(kind)) present = true;
            if (!present) throw new IllegalArgumentException("Missing challenge kind");
        }
        for (String[] cells : rows) {
            TinyDecisionModel.Decision decision = model.classify(cells[4], 0.60, 0.15);
            int expected = Arrays.asList(labels).indexOf(cells[3]);
            metrics.get("all").add(decision, expected);
            metrics.get(cells[1]).add(decision, expected);
        }
        StringBuilder hash = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Paths.get(args[0])))) {
            hash.append(String.format(Locale.ROOT, "%02x", b & 255));
        }
        System.out.print("{\"authored_challenge_only\":true,\"production_eligible\":false,\"sha256\":\"" + hash + "\",\"groups\":{");
        boolean first = true;
        for (Map.Entry<String, DecisionRiskMetrics> entry : metrics.entrySet()) {
            if (!first) System.out.print(','); first = false;
            DecisionRiskMetrics m = entry.getValue();
            System.out.printf(Locale.ROOT, "\"%s\":{\"count\":%d,\"accepted\":%d,\"accepted_errors\":%d," +
                    "\"known\":%d,\"correct\":%d,\"required_abstentions\":%d,\"refused\":%d," +
                    "\"coverage\":%s,\"selective_error\":%s,\"known_ece\":%s}", entry.getKey(), m.total(),
                    m.accepted(), m.acceptedErrors(), m.known(), m.correct(), m.requiredAbstentions(), m.refused(),
                    m.coverage(), m.selectiveError(), m.expectedCalibrationError());
        }
        System.out.println("}}");
    }

    private static List<String> read(Path path) throws Exception {
        if (Files.size(path) > 1_000_000) throw new IllegalArgumentException("Corpus too large");
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.size() < 2) throw new IllegalArgumentException("Empty corpus");
        return lines;
    }
    private static String canonical(String text) {
        return BilingualTextEncoder.normalize(text).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }
}
