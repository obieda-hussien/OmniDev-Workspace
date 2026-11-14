package com.omnidev.workspace.domain.neural;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Export parity, exact source quotes and a baseline-veto diagnostic over the now observed probes.
 */
public final class ProductSupportCheckpointSmoke {
  public static void main(String[] args) throws Exception {
    Path out = Paths.get(args[0]);
    List<ProductSupportModel.Article> articles =
        ProductSupportExperiment.loadArticles(out.resolve("documents.tsv"));
    ProductSupportModel model = TrainedProductSupport.createRaw(articles),
        baseline = TrainedProductSupport.baseline(articles),
        guarded = TrainedProductSupport.create(articles);
    Map<String, String[]> expected = new HashMap<>();
    for (String line : Files.readAllLines(out.resolve("expected.tsv"), StandardCharsets.UTF_8)) {
      String[] r = line.split("\t");
      if (expected.put(r[0], r) != null) throw new AssertionError("Duplicate parity probe");
    }
    int probes = 0, accepted = 0, correct = 0, wrong = 0, ood = 0, oodRefused = 0;
    for (String split : new String[] {"train", "development", "calibration", "test"})
      for (ProductSupportExperiment.Frame f :
          ProductSupportExperiment.frames(
              Paths.get("neural-core/fixtures/product-support/queries.tsv"), split, baseline)) {
        ProductSupportModel.Result result = model.search(f.query, 3),
            protectedResult = guarded.search(f.query, 3),
            before = baseline.search(f.query, 3);
        String[] reference = expected.remove(f.id);
        if (reference == null
            || result.accepted != Boolean.parseBoolean(reference[1])
            || result.matches.size() != reference.length - 2)
          throw new AssertionError("Changed result " + f.id);
        for (int i = 0; i < result.matches.size(); i++) {
          ProductSupportModel.Match m = result.matches.get(i);
          String[] p = reference[i + 2].split(":");
          String id = p[0] + ":" + p[1];
          int start = Integer.parseInt(p[2]), end = Integer.parseInt(p[3]);
          double score = Double.parseDouble(p[4]);
          if (!m.article.id.equals(id)
              || m.evidence.startOffset != start
              || m.evidence.endOffset != end
              || Math.abs(m.score - score) > 1e-10
              || !m.article.text.substring(start, end).equals(m.evidence.quote))
            throw new AssertionError("Altered source or score " + f.id);
        }
        if (protectedResult.accepted
            && (!before.accepted
                || !protectedResult
                    .matches
                    .get(0)
                    .article
                    .topic
                    .equals(before.matches.get(0).article.topic)))
          throw new AssertionError("Baseline veto bypass");
        if (split.equals("test")) {
          if (f.topic.equals("-")) {
            ood++;
            if (!protectedResult.accepted) oodRefused++;
          }
          if (protectedResult.accepted) {
            accepted++;
            if (protectedResult.matches.get(0).article.topic.equals(f.topic)) correct++;
            else wrong++;
          }
        }
        probes++;
      }
    if (probes != 234 || !expected.isEmpty()) throw new AssertionError("Missing parity rows");
    String report =
        "{\"evaluation_status\":\"post-hoc safety diagnostic on observed v1 test; not a fresh"
            + " held-out score\",\"probes\":"
            + probes
            + ",\"accepted\":"
            + accepted
            + ",\"accepted_correct\":"
            + correct
            + ",\"accepted_wrong\":"
            + wrong
            + ",\"ood\":"
            + ood
            + ",\"ood_refused\":"
            + oodRefused
            + ",\"production_eligible\":false}";
    Files.write(out.resolve("guard-diagnostic.json"), report.getBytes(StandardCharsets.UTF_8));
    System.out.println(report);
  }
}
