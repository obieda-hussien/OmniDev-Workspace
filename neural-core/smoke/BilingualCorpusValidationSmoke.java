package com.omnidev.workspace.domain.neural;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Corrupt the actual experiment loader before fitting, rather than testing a stand-in parser. */
public final class BilingualCorpusValidationSmoke {
    public static void main(String[] args) throws Exception {
        Path path = Paths.get(args[0]);
        List<BilingualRefusalCorpus.Row> rows = BilingualRefusalCorpus.load(path);
        if (rows.size() != 1551 || BilingualRefusalCorpus.balanced(BilingualRefusalCorpus.split(rows, "train")).isEmpty()) throw new AssertionError("Incomplete real corpus");
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        rejects(changed(lines, 1, 0, "invented"));
        rejects(changed(lines, 1, 3, "missing_domain"));
        rejects(changed(lines, 1, 8, ""));
        rejects(changed(lines, 1, 8, String.join("", Collections.nCopies(2049, "a"))));
        int composed = find(lines, "composed_cross"), publicTest = find(lines, "unseen_topic");
        rejects(changed(lines, composed, 6, "original:missing-parent"));
        rejects(changed(lines, composed, 5, "composed_same"));
        rejects(changed(lines, publicTest, 0, "train"));
        List<String> duplicate = new ArrayList<>(lines); String[] copy = lines.get(1).split("\t", -1);
        copy[1] = "different-id"; copy[8] = copy[8].toUpperCase(Locale.ROOT); duplicate.add(String.join("\t", copy)); rejects(duplicate);
        // Keep every paraphrase together; moving just one sibling must be rejected.
        rejects(changed(lines, 2, 0, "calibration"));
        System.out.println("Bilingual corpus: 1551 real rows and 9 invalid split/domain/composition/text mutations verified.");
    }
    private static int find(List<String> lines, String kind) {
        for (int i = 1; i < lines.size(); i++) if (lines.get(i).split("\t", -1)[5].equals(kind)) return i;
        throw new AssertionError("Missing fixture kind");
    }
    private static List<String> changed(List<String> original, int row, int cell, String value) {
        List<String> copy = new ArrayList<>(original); String[] cells = copy.get(row).split("\t", -1);
        cells[cell] = value; copy.set(row, String.join("\t", cells)); return copy;
    }
    private static void rejects(List<String> lines) {
        try { BilingualRefusalCorpus.parse(lines); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid training corpus accepted");
    }
}
