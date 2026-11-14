package com.omnidev.workspace.domain.neural;

import com.omnidev.workspace.domain.neural.ProductSupportExperiment.Frame;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Compiled candidate parity plus exact retained-default and source-quote contracts. */
public final class RankedSupportCheckpointSmoke {
  public static void main(String[] args) throws Exception {
    Path out = Paths.get(args[0]);
    List<ProductSupportModel.Article> articles =
        ProductSupportExperiment.loadArticles(Paths.get("build/product-support/documents.tsv"));
    Map<String, ProductSupportModel> models = new LinkedHashMap<>();
    models.put("lexical", TrainedRankedSupport.baseline(articles));
    models.put("v1_raw", TrainedProductSupport.createRaw(articles));
    models.put("v1_guarded", TrainedProductSupport.create(articles));
    models.put("v2_raw", TrainedRankedSupport.createRaw(articles));
    models.put("v2_guarded", TrainedRankedSupport.createCandidateGuarded(articles));
    ProductSupportModel retained = TrainedRankedSupport.create(articles);
    Map<String, String> expected = new HashMap<>();
    for (String line : Files.readAllLines(out.resolve("expected.tsv"), StandardCharsets.UTF_8)) {
      String[] r = line.split("\t");
      if (expected.put(r[0] + "\t" + r[1], line) != null)
        throw new AssertionError("Duplicate exported prediction");
    }
    ProductSupportModel base = models.get("lexical");
    List<Frame> rows = new ArrayList<>();
    for (String split : new String[] {"train", "development", "calibration", "test"})
      rows.addAll(
          ProductSupportExperiment.frames(
              Paths.get("neural-core/fixtures/product-support/queries.tsv"), split, base));
    rows.addAll(
        ProductSupportExperiment.frames(
            Paths.get("neural-core/fixtures/product-support-v2/fresh.tsv"), "test", base));
    List<String> rawLoss = new ArrayList<>(), guardNewErrors = new ArrayList<>();
    int probes = 0;
    for (Frame f : rows) {
      String before =
          RankedSupportExperiment.probe(
              f.id, "default", models.get("v1_guarded").search(f.query, 3));
      if (!before.equals(
          RankedSupportExperiment.probe(f.id, "default", retained.search(f.query, 3))))
        throw new AssertionError("Retained default changed " + f.id);
      if (f.split.equals("test")) {
        ProductSupportModel.Result oldRaw = models.get("v1_raw").search(f.query, 3),
            newRaw = models.get("v2_raw").search(f.query, 3),
            oldGuard = models.get("v1_guarded").search(f.query, 3),
            newGuard = models.get("v2_guarded").search(f.query, 3);
        if (correct(f, oldRaw) && !correct(f, newRaw)) rawLoss.add(f.id);
        if (newGuard.accepted
            && !correct(f, newGuard)
            && !(oldGuard.accepted && !correct(f, oldGuard))) guardNewErrors.add(f.id);
      }
      for (Map.Entry<String, ProductSupportModel> entry : models.entrySet()) {
        ProductSupportModel.Result result = entry.getValue().search(f.query, 3);
        String actual = RankedSupportExperiment.probe(f.id, entry.getKey(), result),
            reference = expected.remove(f.id + "\t" + entry.getKey());
        if (!actual.equals(reference))
          throw new AssertionError("Compiled result drift " + f.id + " " + entry.getKey());
        for (ProductSupportModel.Match m : result.matches)
          if (!m.article.id.equals(m.evidence.documentId)
              || !m.article
                  .text
                  .substring(m.evidence.startOffset, m.evidence.endOffset)
                  .equals(m.evidence.quote))
            throw new AssertionError("Foreign or altered support quote");
        if (entry.getKey().equals("v2_guarded") && result.accepted) {
          ProductSupportModel.Result baseline = base.search(f.query, 3);
          if (!baseline.accepted
              || !baseline.matches.get(0).article.topic.equals(result.matches.get(0).article.topic))
            throw new AssertionError("Baseline guard bypass");
        }
        probes++;
      }
    }
    if (rows.size() != 351 || probes != 1755 || !expected.isEmpty())
      throw new AssertionError("Incomplete export validation");
    String retention =
        "{\"raw_previously_accepted_correct_lost\":"
            + rawLoss.size()
            + ",\"raw_loss_ids\":"
            + jsonArray(rawLoss)
            + ",\"candidate_guard_new_wrong_acceptances\":"
            + guardNewErrors.size()
            + ",\"candidate_guard_new_wrong_ids\":"
            + jsonArray(guardNewErrors)
            + ",\"retained_default_checks\":351,\"production_eligible\":false}";
    Files.write(out.resolve("retention.json"), retention.getBytes(StandardCharsets.UTF_8));
    System.out.println(
        "Ranked support compiled parity: "
            + probes
            + " predictions; 351 exact retained-default checks; unchanged source quotes");
  }

  private static boolean correct(Frame f, ProductSupportModel.Result r) {
    return !f.topic.equals("-")
        && r.accepted
        && !r.matches.isEmpty()
        && r.matches.get(0).article.topic.equals(f.topic);
  }

  private static String jsonArray(List<String> ids) {
    StringBuilder s = new StringBuilder("[");
    for (String id : ids) {
      if (s.length() > 1) s.append(',');
      s.append('"').append(id).append('"');
    }
    return s.append(']').toString();
  }
}
