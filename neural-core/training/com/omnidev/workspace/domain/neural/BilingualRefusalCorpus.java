package com.omnidev.workspace.domain.neural;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Desktop-only corpus contract; cross-domain composition never crosses its parents' split. */
public final class BilingualRefusalCorpus {
    public static final String SHA256 = "cfd303a0225c1d137c8b1744c298e133a553e9c41d5011e38fca61f1f000e57e";
    public static final class Row {
        public final String split, group, expectedDomain, language, kind, text, parentA, parentB;
        public final int label;
        final float[] features;
        private Row(String[] c) {
            split = c[0]; group = c[1]; label = c[2].equals("supported") ? 0 : c[2].equals("refuse") ? 1 : -1;
            expectedDomain = c[3]; language = c[4]; kind = c[5]; parentA = c[6]; parentB = c[7]; text = c[8];
            features = BilingualTextEncoder.encode(text);
        }
        OfflineDecisionTrainer.Example example() { return new OfflineDecisionTrainer.Example(group, features, label); }
    }
    public static List<Row> load(Path path) throws Exception {
        if (Files.size(path) > 2_000_000) throw new IllegalArgumentException("Bounded corpus exceeded");
        byte[] raw = Files.readAllBytes(path);
        StringBuilder hash = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(raw)) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
        if (!SHA256.equals(hash.toString())) throw new IllegalArgumentException("Frozen corpus hash changed");
        return parse(Arrays.asList(new String(raw, StandardCharsets.UTF_8).split("\n")));
    }
    // Package visibility lets the host smoke test corrupt the actual parser contract.
    static List<Row> parse(List<String> lines) {
        if (lines.isEmpty() || !lines.get(0).equals("split\tgroup\toutcome\texpected_domain\tlanguage\tkind\tparent_a\tparent_b\ttext")) throw new IllegalArgumentException("Invalid corpus header");
        List<Row> rows = new ArrayList<>(); Map<String, String> groups = new HashMap<>(); Set<String> texts = new HashSet<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] c = line.split("\t", -1);
            if (c.length != 9 || rows.size() >= 10_000) throw new IllegalArgumentException("Invalid bounded corpus row");
            Row row = new Row(c);
            if (!Arrays.asList("train", "development", "calibration", "test", "regression").contains(row.split) ||
                    !Arrays.asList("ar", "en", "mixed").contains(row.language) || row.label < 0 || row.group.isEmpty() || row.group.length() > 160 ||
                    !Arrays.asList("original", "prior_clinc", "additional_clinc", "unseen_topic", "authored_unsupported", "authored_vague", "composed_same", "composed_cross").contains(row.kind)) {
                throw new IllegalArgumentException("Invalid corpus contract");
            }
            if (row.label == 0 && !Arrays.asList("repository", "build", "device", "knowledge", "chat").contains(row.expectedDomain) ||
                    row.label == 1 && !row.expectedDomain.equals("-")) throw new IllegalArgumentException("Expected domain mismatch");
            String old = groups.putIfAbsent(row.group, row.split + ":" + row.label);
            if (old != null && !old.equals(row.split + ":" + row.label)) throw new IllegalArgumentException("Group leakage");
            String canonical = BilingualTextEncoder.normalize(row.text).replaceAll("[^\\p{L}\\p{N}_]+", " ").trim();
            if (canonical.isEmpty() || !texts.add(canonical)) throw new IllegalArgumentException("Duplicate or empty text");
            if (row.group.startsWith("clinc-")) {
                String partition = row.group.split("-")[1];
                if (partition.equals("test") && !Arrays.asList("test", "regression").contains(row.split) ||
                        partition.equals("train") && !row.split.equals("train") ||
                        partition.equals("val") && !Arrays.asList("development", "calibration").contains(row.split)) {
                    throw new IllegalArgumentException("Upstream partition leakage");
                }
            }
            if (row.kind.equals("unseen_topic") && !row.split.equals("test")) throw new IllegalArgumentException("Unseen topic leakage");
            if (row.kind.startsWith("composed_")) {
                for (String parent : new String[] {row.parentA, row.parentB}) {
                    if (!Objects.equals(groups.get(parent), row.split + ":0")) throw new IllegalArgumentException("Composition parent leakage");
                }
                if (row.kind.equals("composed_same") != (row.label == 0)) throw new IllegalArgumentException("Composition label mismatch");
                // Parent group names are original domain-scenario IDs; require the declared domain relation.
                String domainA = row.parentA.split("-")[0], domainB = row.parentB.split("-")[0];
                if (domainA.equals(domainB) != row.kind.equals("composed_same")) throw new IllegalArgumentException("Composition domain mismatch");
            }
            rows.add(row);
        }
        for (String split : Arrays.asList("train", "development", "calibration")) {
            Set<String> strata = new HashSet<>();
            for (Row row : rows) if (row.split.equals(split)) strata.add(row.language + ":" + row.label);
            if (strata.size() != 6) throw new IllegalArgumentException("Missing outcome/language stratum");
        }
        return Collections.unmodifiableList(rows);
    }
    static List<Row> split(List<Row> rows, String split) {
        List<Row> result = new ArrayList<>(); for (Row row : rows) if (row.split.equals(split)) result.add(row); return result;
    }
    static List<Row> challenges(Path path) throws Exception {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).equals("id\tkind\tlanguage\texpected\ttext")) throw new IllegalArgumentException("Invalid challenge header");
        List<Row> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] c = line.split("\t", -1);
            if (c.length != 5 || rows.size() >= 1000) throw new IllegalArgumentException("Invalid challenge row");
            rows.add(new Row(new String[] {"regression", c[0], c[3].equals("abstain") ? "refuse" : "supported",
                    c[3].equals("abstain") ? "-" : c[3], c[2], "old_challenge", "-", "-", c[4]}));
        }
        return rows;
    }
    static List<OfflineDecisionTrainer.Example> examples(List<Row> rows) {
        List<OfflineDecisionTrainer.Example> result = new ArrayList<>(); for (Row row : rows) result.add(row.example()); return result;
    }
    static List<OfflineDecisionTrainer.Example> balanced(List<Row> rows) {
        Map<String, List<Row>> strata = strata(rows); int maximum = 0;
        for (List<Row> group : strata.values()) maximum = Math.max(maximum, group.size());
        if (strata.size() != 6 || maximum > 2000) throw new IllegalArgumentException("Invalid training balance");
        List<OfflineDecisionTrainer.Example> result = new ArrayList<>();
        for (List<Row> group : strata.values()) for (int i = 0; i < maximum; i++) result.add(group.get(i % group.size()).example());
        return result;
    }
    static Map<String, List<Row>> strata(List<Row> rows) {
        Map<String, List<Row>> result = new TreeMap<>();
        for (Row row : rows) result.computeIfAbsent(row.language + ":" + row.label, key -> new ArrayList<>()).add(row);
        return result;
    }
    static double loss(OfflineDecisionTrainer.Model model, List<Row> rows, double temperature) {
        double sum = 0; Map<String, List<Row>> strata = strata(rows);
        if (strata.size() != 6) throw new IllegalArgumentException("Missing evaluation stratum");
        for (List<Row> group : strata.values()) sum += OfflineDecisionTrainer.logLoss(model, examples(group), temperature);
        return sum / strata.size();
    }
    private BilingualRefusalCorpus() { }
}
