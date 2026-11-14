package com.omnidev.workspace.domain.neural;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class HybridSupportCheckpointSmoke {
  public static void main(String[] args) throws Exception {
    Path out = Paths.get(args[0]);
    List<ProductSupportModel.Article> docs =
        ProductSupportExperiment.loadArticles(Paths.get("build/product-support/documents.tsv"));
    HybridSupportRetriever model = TrainedHybridSupport.createCandidate(docs),
        index =
            new HybridSupportRetriever(
                docs, HybridSupportRetriever.Mode.LEGACY, null, 0, 0, 0, null);
    ProductSupportModel retained = TrainedHybridSupport.retainedDefault(docs),
        prior = TrainedProductSupport.create(docs);
    Map<String, String> expected = new HashMap<>();
    for (String line : Files.readAllLines(out.resolve("expected.tsv"), StandardCharsets.UTF_8)) {
      String id = line.split("\t", 2)[0];
      if (expected.put(id, line) != null) throw new AssertionError("Duplicate hybrid probe");
    }
    List<HybridSupportExperiment.Query> queries = new ArrayList<>();
    for (String split : new String[] {"train", "development", "calibration", "test"})
      queries.addAll(HybridSupportExperiment.old(split, index));
    List<String> v2 =
        Files.readAllLines(
            Paths.get("neural-core/fixtures/product-support-v2/fresh.tsv"), StandardCharsets.UTF_8);
    for (String line : v2.subList(1, v2.size())) {
      String[] r = line.split("\t", -1);
      queries.add(
          new HybridSupportExperiment.Query(
              r[0],
              "observed",
              r[3],
              r[5],
              HybridSupportExperiment.ids(r[4]),
              Collections.emptySet(),
              index));
    }
    queries.addAll(HybridSupportExperiment.fresh(index));
    Map<String, ProductSupportModel.Article> original = new HashMap<>();
    for (ProductSupportModel.Article a : docs) original.put(a.id, a);
    List<String> lost = new ArrayList<>();
    int references = 0;
    for (HybridSupportExperiment.Query q : queries) {
      HybridSupportRetriever.Result result = model.search(q.text, 10);
      if (!HybridSupportExperiment.probe(q.id, result).equals(expected.remove(q.id)))
        throw new AssertionError("Hybrid output drift " + q.id);
      ProductSupportModel.Result a = prior.search(q.text, 3), b = retained.search(q.text, 3);
      if (!RankedSupportExperiment.probe(q.id, "default", a)
          .equals(RankedSupportExperiment.probe(q.id, "default", b)))
        throw new AssertionError("Retained default drift");
      if (a.accepted
          && q.primary.contains(a.matches.get(0).article.topic)
          && !(result.accepted
              && !result.matches.isEmpty()
              && q.primary.contains(result.matches.get(0).topic.id))) lost.add(q.id);
      for (HybridSupportRetriever.Hit hit : result.matches)
        for (ProductSupportModel.Article ref : hit.topic.references) {
          ProductSupportModel.Article real = original.get(ref.id);
          if (real == null
              || !ref.topic.equals(hit.topic.id)
              || !ref.text.equals(real.text)
              || !ref.status.equals(real.status)
              || !ref.editions.equals(real.editions))
            throw new AssertionError("Invented or altered source reference");
          references++;
        }
    }
    if (queries.size() != 459 || !expected.isEmpty())
      throw new AssertionError("Missing hybrid probes");
    StringBuilder ids = new StringBuilder("[");
    for (String id : lost) {
      if (ids.length() > 1) ids.append(',');
      ids.append('"').append(id).append('"');
    }
    ids.append(']');
    String report =
        "{\"predictions\":459,\"retained_defaults\":459,\"source_reference_checks\":"
            + references
            + ",\"candidate_previously_accepted_correct_lost\":"
            + lost.size()
            + ",\"candidate_loss_ids\":"
            + ids
            + ",\"production_eligible\":false}";
    Files.write(out.resolve("parity-retention.json"), report.getBytes(StandardCharsets.UTF_8));
    System.out.println(report);
  }
}
