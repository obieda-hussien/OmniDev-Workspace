package com.omnidev.workspace.domain.neural;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Host-only pinned public subset; parallel translations share a semantic group and split. */
public final class PublicRefusalCorpus {
    public static final String SHA256 = "f300371ef4656d78fc22449066250918d5f6270afafb53595232351b1d95b6e8";
    static final class Row {
        final String split, group, language, expected, kind, text;
        final int label;
        Row(String split, String group, String language, String expected, String kind, String text) {
            this.split = split; this.group = group; this.language = language; this.expected = expected; this.kind = kind; this.text = text;
            label = expected.equals("-") ? 1 : 0;
        }
    }
    static List<Row> load(Path path) throws Exception {
        if (Files.size(path) > 1_000_000) throw new IllegalArgumentException("Public subset size exceeded");
        byte[] raw = Files.readAllBytes(path); StringBuilder hash = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(raw)) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
        if (!SHA256.equals(hash.toString())) throw new IllegalArgumentException("Public subset hash changed");
        List<String> lines = Arrays.asList(new String(raw, StandardCharsets.UTF_8).split("\n"));
        if (!lines.get(0).equals("split\tgroup\tsource_partition\tsource_id\tlocale\tintent\texpected_domain\ttext")) throw new IllegalArgumentException("Public subset schema mismatch");
        List<Row> result = new ArrayList<>(); Map<String, String> groups = new HashMap<>(); Set<String> ids = new HashSet<>(), texts = new HashSet<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] c = line.split("\t", -1);
            if (c.length != 8 || result.size() >= 5000 || !Arrays.asList("train", "development", "calibration", "test").contains(c[0]) ||
                    !Arrays.asList("ar-SA", "en-US").contains(c[4]) || !c[1].equals("massive-" + c[3]) || !ids.add(c[1] + c[4]) ||
                    !Arrays.asList("-", "device", "chat").contains(c[6]) || c[7].length() > 512) throw new IllegalArgumentException("Invalid public row");
            String canonical = BilingualTextEncoder.normalize(c[7]).replaceAll("[^\\p{L}\\p{N}_]+", " ").trim();
            if (canonical.isEmpty() || !texts.add(canonical)) throw new IllegalArgumentException("Public text duplication");
            String identity = c[0] + ":" + c[5] + ":" + c[6], old = groups.putIfAbsent(c[1], identity);
            if (old != null && !old.equals(identity)) throw new IllegalArgumentException("Parallel group leakage");
            result.add(new Row(c[0], c[1], c[4].startsWith("ar") ? "ar" : "en", c[6],
                    c[5].equals("calendar_set") || c[5].equals("weather_query") ? "held_out_topic" : "massive", c[7]));
        }
        for (String group : groups.keySet()) if (!ids.contains(group + "ar-SA") || !ids.contains(group + "en-US")) throw new IllegalArgumentException("Incomplete parallel group");
        return result;
    }
    static List<Row> old(List<BilingualRefusalCorpus.Row> source) {
        List<Row> result = new ArrayList<>();
        for (BilingualRefusalCorpus.Row r : source) result.add(new Row(r.split, r.group, r.language, r.expectedDomain, "old_" + r.kind, r.text));
        return result;
    }
    static List<Row> split(List<Row> rows, String split) {
        List<Row> result = new ArrayList<>(); for (Row r : rows) if (r.split.equals(split)) result.add(r); return result;
    }
    private PublicRefusalCorpus() { }
}
