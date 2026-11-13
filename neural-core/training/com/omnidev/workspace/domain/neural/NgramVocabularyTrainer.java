package com.omnidev.workspace.domain.neural;

import java.util.*;
import com.omnidev.workspace.domain.neural.BilingualRefusalCorpus.Row;

/** Train-only, scenario-group document frequencies and bounded domain-discriminative vocabulary. */
public final class NgramVocabularyTrainer {
    private static final class Group {
        final Set<String> terms = new HashSet<>(); final int mask;
        Group(int mask) { this.mask = mask; }
    }
    public static RichTextEncoder fit(List<Row> training) {
        if (training == null || training.isEmpty() || training.size() > 10_000) throw new IllegalArgumentException("Invalid bounded vocabulary data");
        Map<String, Group> groups = new TreeMap<>();
        for (Row row : training) {
            if (!row.split.equals("train")) throw new IllegalArgumentException("Vocabulary may fit training rows only");
            int mask = 0; for (int i = 0; i < 5; i++) if (MultiIntentExperiment.present(row, i)) mask |= 1 << i;
            Group group = groups.get(row.group);
            if (group == null) { group = new Group(mask); groups.put(row.group, group); }
            if (group.mask != mask) throw new IllegalArgumentException("Conflicting group domain targets");
            group.terms.addAll(RichTextEncoder.terms(row.text));
        }
        Map<String, int[]> counts = new TreeMap<>(); int[] totals = new int[5];
        for (Group group : groups.values()) {
            for (int i = 0; i < 5; i++) if ((group.mask & 1 << i) != 0) totals[i]++;
            for (String term : group.terms) {
                int[] n = counts.computeIfAbsent(term, key -> new int[6]); n[0]++;
                for (int i = 0; i < 5; i++) if ((group.mask & 1 << i) != 0) n[i + 1]++;
            }
            if (counts.size() > 50_000) throw new IllegalArgumentException("Vocabulary candidate budget exceeded");
        }
        Map<String, Double> scores = new HashMap<>(); int size = groups.size();
        for (Map.Entry<String, int[]> entry : counts.entrySet()) if (entry.getValue()[0] >= 2) {
            int[] n = entry.getValue(); double best = 0;
            for (int i = 0; i < 5; i++) {
                double a = n[i + 1], b = n[0] - a, c = totals[i] - a, d = size - a - b - c;
                double divisor = (a + b) * (c + d) * (a + c) * (b + d);
                if (divisor > 0) best = Math.max(best, size * Math.pow(a * d - b * c, 2) / divisor);
            }
            scores.put(entry.getKey(), best);
        }
        List<String> ranked = new ArrayList<>(scores.keySet());
        ranked.sort(Comparator.<String>comparingDouble(scores::get).reversed().thenComparing(Comparator.naturalOrder()));
        Set<String> selected = new LinkedHashSet<>();
        // Reserve room for Arabic and non-Arabic terms before filling the remaining vocabulary.
        for (boolean arabic : new boolean[] {true, false}) {
            int added = 0;
            for (String term : ranked) if (hasArabic(term) == arabic && added < 192) { selected.add(term); added++; }
        }
        for (String term : ranked) { if (selected.size() >= 768) break; selected.add(term); }
        List<String> sorted = new ArrayList<>(selected); Collections.sort(sorted);
        float[] idf = new float[sorted.size()];
        for (int i = 0; i < idf.length; i++) idf[i] = (float) (1 + Math.log((size + 1.0) / (counts.get(sorted.get(i))[0] + 1.0)));
        return new RichTextEncoder(sorted.toArray(new String[0]), idf, RichTextEncoder.VERSION);
    }
    private static boolean hasArabic(String term) { for (int i = 0; i < term.length(); i++) if (term.charAt(i) >= '\u0600' && term.charAt(i) <= '\u06ff') return true; return false; }
    private NgramVocabularyTrainer() { }
}
