package com.omnidev.workspace.domain.neural;

import com.omnidev.workspace.domain.neural.PublicRefusalCorpus.Row;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Authored scenario groups, not verified tool executions or independently annotated traffic. */
final class ContractIntentCorpus {
  static List<Row> load(String split) throws Exception {
    Path path = Paths.get("neural-core/fixtures/contract-intents/corpus.tsv");
    if (Files.size(path) > 200000) throw new IllegalArgumentException("Contract corpus bounds");
    byte[] bytes = Files.readAllBytes(path);
    StringBuilder hash = new StringBuilder();
    for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
      hash.append(String.format(Locale.ROOT, "%02x", b & 255));
    if (!hash.toString().equals("68d487ed99348588c64d7f6c86eea9460e41c7de468482e550e8d90dc99a5f2e"))
      throw new IllegalArgumentException("Frozen contract corpus changed");
    List<String> lines = Arrays.asList(new String(bytes, StandardCharsets.UTF_8).split("\n"));
    if (lines.size() != 217
        || !lines.get(0).equals("split\tgroup\tlanguage\texpected_domain\tanchor\ttext"))
      throw new IllegalArgumentException("Contract corpus schema");
    List<Row> rows = new ArrayList<>();
    Map<String, String> groups = new HashMap<>();
    Map<String, Integer> counts = new HashMap<>();
    Set<String> texts = new HashSet<>();
    for (String line : lines.subList(1, lines.size())) {
      String[] c = line.split("\t", -1);
      if (c.length != 6
          || !Arrays.asList("train", "development", "calibration", "test").contains(c[0])
          || !Arrays.asList("ar", "en", "mixed").contains(c[2])
          || !Arrays.asList("-", "repository", "build", "device", "knowledge", "chat")
              .contains(c[3])
          || c[1].length() > 160
          || !c[1].startsWith("contract-")
          || c[5].length() > 512) throw new IllegalArgumentException("Contract row bounds");
      String identity = c[0] + ":" + c[3] + ":" + c[4], old = groups.putIfAbsent(c[1], identity);
      if (old != null && !old.equals(identity))
        throw new IllegalArgumentException("Contract variant leakage");
      if (!texts.add(
          BilingualTextEncoder.normalize(c[5]).replaceAll("[^\\p{L}\\p{N}_]+", " ").trim()))
        throw new IllegalArgumentException("Contract duplicate text");
      counts.merge(c[0] + ":" + c[3] + ":" + c[2], 1, Integer::sum);
      if (split.equals("all") || split.equals(c[0]))
        rows.add(new Row(c[0], c[1], c[2], c[3], "contract_authored", c[5]));
    }
    for (String part : new String[] {"train", "development", "calibration", "test"})
      for (String label : new String[] {"-", "repository", "build", "device", "knowledge", "chat"})
        for (String lang : new String[] {"ar", "en", "mixed"})
          if (counts.getOrDefault(part + ":" + label + ":" + lang, 0)
              != (part.equals("train") ? 6 : 2))
            throw new IllegalArgumentException("Contract strata missing");
    if (rows.isEmpty()) throw new IllegalArgumentException("Unknown contract partition");
    return rows;
  }

  private ContractIntentCorpus() {}
}
