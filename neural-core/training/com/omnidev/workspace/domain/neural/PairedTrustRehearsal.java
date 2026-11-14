package com.omnidev.workspace.domain.neural;

import com.omnidev.workspace.domain.neural.PublicRefusalCorpus.Row;
import java.util.*;

/**
 * Synthetic same-intent code-switch rehearsal from TRAIN translation pairs; source identity stays
 * intact.
 */
final class PairedTrustRehearsal {
  static List<Row> augment(List<Row> train) {
    if (train == null || train.isEmpty() || train.size() > 10000)
      throw new IllegalArgumentException("Invalid rehearsal bounds");
    Map<String, Map<String, Row>> pairs = new TreeMap<>();
    Set<String> seen = new HashSet<>();
    List<Row> out = new ArrayList<>(train);
    for (Row r : train) {
      if (!r.split.equals("train"))
        throw new IllegalArgumentException("Rehearsal requires TRAIN rows only");
      seen.add(BilingualTextEncoder.normalize(r.text));
      if (r.label == 0 && (r.language.equals("ar") || r.language.equals("en"))) {
        Map<String, Row> pair = pairs.computeIfAbsent(r.group, k -> new TreeMap<>());
        for (Row previous : pair.values())
          if (!previous.expected.equals(r.expected))
            throw new IllegalArgumentException("Conflicting paired intent");
        pair.putIfAbsent(r.language, r);
      }
    }
    for (Map<String, Row> pair : pairs.values())
      if (pair.containsKey("ar") && pair.containsKey("en")) {
        Row ar = pair.get("ar"), en = pair.get("en");
        for (String text : new String[] {ar.text + " / " + en.text, en.text + " / " + ar.text}) {
          if (text.length() > 512 || !seen.add(BilingualTextEncoder.normalize(text))) continue;
          if (out.size() == 10000)
            throw new IllegalArgumentException("Rehearsal output bounds exceeded");
          out.add(new Row("train", ar.group, "mixed", ar.expected, "trust_paired_rehearsal", text));
        }
      }
    return out;
  }

  private PairedTrustRehearsal() {}
}
