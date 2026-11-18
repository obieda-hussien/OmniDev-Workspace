package com.omnidev.workspace.domain.neural;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Reads query ID, named reference and query. Exports runtime proposals and both evidence checks.
 */
public final class QuantityEvidenceReplay {
  public static void main(String[] args) throws Exception {
    List<ProductSupportModel.Article> docs =
        ProductSupportExperiment.loadArticles(Paths.get("build/product-support/documents.tsv"));
    SupportContrastiveModel model =
        TrainedContrastiveSupport.createCandidate(
            docs, QueryMemoryExperiment.examples(Paths.get("build/query-memory-v5/exemplars.tsv")));
    Map<String, ProductSupportModel.Article> english = new HashMap<>();
    for (ProductSupportModel.Article d : docs) if (d.language.equals("en")) english.put(d.topic, d);
    try (BufferedWriter out = Files.newBufferedWriter(Paths.get(args[1]))) {
      for (String line : Files.readAllLines(Paths.get(args[0]))) {
        String[] row = line.split("\t", -1);
        if (row.length != 3) throw new AssertionError("Bad request");
        SupportContrastiveModel.Result r = model.search(row[2], 10);
        String topic = r.matches.isEmpty() ? "" : r.matches.get(0).topic.id;
        // Java match scores are FP32. The v12 policy compared their eight-decimal Python rounding.
        boolean accepted =
            r.accepted
                || (!r.proposalAccepted
                    && !topic.isEmpty()
                    && new java.math.BigDecimal(r.matches.get(0).score)
                            .setScale(8, java.math.RoundingMode.HALF_EVEN)
                            .doubleValue()
                        >= Double.parseDouble(args[2]));
        QuantityEvidence.Result gate = QuantityEvidence.check(row[2], english.get(topic));
        QuantityEvidence.Result named = QuantityEvidence.check(row[2], english.get(row[1]));
        out.write(
            String.join(
                "\t",
                row[0],
                topic,
                Boolean.toString(accepted),
                gate.decision.name(),
                named.decision.name(),
                named.sourceId,
                named.sentence,
                named.value));
        out.newLine();
      }
    }
  }
}
