package com.omnidev.workspace.domain.history;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** Selects whole history excerpts within a character budget, retaining their chronological order. */
public final class ContextRetention {
  private ContextRetention() {}

  static int score(String line) {
    String normalized = Normalizer.normalize(line, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا')
        .replace('ى', 'ي').replace('ة', 'ه')
        .replaceAll("[\\p{Mn}ـ]", "");
    return ContextRetentionWeights.scores(new HashSet<>(Arrays.asList(
        normalized.split("[^\\p{L}\\p{N}_]+"))))[0];
  }

  public static String select(List<String> lines, int maxChars) {
    int size = lines.size();
    List<Integer> order = new ArrayList<>(size);
    int[] scores = new int[size];
    for (int i = 0; i < size; i++) {
      order.add(i);
      scores[i] = score(lines.get(i));
    }
    order.sort(Comparator.<Integer>comparingInt(i -> scores[i]).reversed()
        .thenComparing(Comparator.reverseOrder()));

    boolean[] chosen = new boolean[size];
    int remaining = maxChars;
    for (int i : order) {
      int cost = lines.get(i).length() + 1;
      if (cost > remaining) continue;
      chosen[i] = true;
      remaining -= cost;
    }
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < size; i++) {
      if (!chosen[i]) continue;
      if (result.length() > 0) result.append('\n');
      result.append(lines.get(i));
    }
    return result.toString();
  }
}
