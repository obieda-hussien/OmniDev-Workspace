package com.omnidev.workspace.domain.neural;

import static com.omnidev.workspace.domain.neural.SupportReliabilityExperiment.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class SupportReliabilityCheckpointSmoke {
  public static void main(String[] args) throws Exception {
    Path out = Paths.get(args[0]);
    List<ProductSupportModel.Article> docs =
        ProductSupportExperiment.loadArticles(Paths.get("build/product-support/documents.tsv"));
    List<SupportQueryMemory.Exemplar> examples =
        QueryMemoryExperiment.examples(Paths.get("build/query-memory-v5/exemplars.tsv"));
    SupportQueryMemory memory = TrainedQueryMemory.createCandidate(docs, examples);
    SupportReliabilityModel model = TrainedSupportReliability.createCandidate(docs, examples);
    ProductSupportModel prior = TrainedProductSupport.create(docs),
        retained = TrainedSupportReliability.retainedDefault(docs);
    List<HybridSupportExperiment.Query> labels = observed(QueryMemoryExperiment.base(docs));
    labels.addAll(fresh(QueryMemoryExperiment.base(docs)));
    Map<String, String> expected = new HashMap<>();
    for (String line : Files.readAllLines(out.resolve("expected.tsv"), StandardCharsets.UTF_8))
      if (expected.put(line.split("\t", 2)[0], line) != null)
        throw new AssertionError("Duplicate selector probe");
    Map<String, ProductSupportModel.Article> original = new HashMap<>();
    for (ProductSupportModel.Article a : docs) original.put(a.id, a);
    List<String> v5Lost = new ArrayList<>(),
        newWrong = new ArrayList<>(),
        retainedLost = new ArrayList<>();
    int refs = 0, topology = 0;
    for (HybridSupportExperiment.Query q : labels) {
      SupportReliabilityModel.Result result = model.search(q.text, 10);
      SupportQueryMemory.Result old = memory.search(q.text, 10);
      if (!probe(q.id, result).equals(expected.remove(q.id)))
        throw new AssertionError("Selector numeric/typed output drift: " + q.id);
      if (old.accepted != result.baselineAccepted || old.matches.size() != result.matches.size())
        throw new AssertionError("Selector baseline drift");
      for (int i = 0; i < old.matches.size(); i++) {
        SupportQueryMemory.Hit a = old.matches.get(i), b = result.matches.get(i);
        if (!a.topic.id.equals(b.topic.id)
            || Double.doubleToLongBits(a.score) != Double.doubleToLongBits(b.score)
            || Double.doubleToLongBits(a.coverage) != Double.doubleToLongBits(b.coverage))
          throw new AssertionError("Selector altered frozen ranking");
        topology++;
        for (ProductSupportModel.Article ref : b.topic.references) {
          ProductSupportModel.Article real = original.get(ref.id);
          if (real == null
              || !ref.topic.equals(b.topic.id)
              || !ref.question.equals(real.question)
              || !ref.text.equals(real.text)
              || !ref.status.equals(real.status)
              || !ref.editions.equals(real.editions))
            throw new AssertionError("Selector invented source");
          refs++;
        }
      }
      boolean correct =
          !result.matches.isEmpty() && q.primary.contains(result.matches.get(0).topic.id);
      if (old.accepted && correct && !result.accepted) v5Lost.add(q.id);
      if (result.accepted && !correct && !old.accepted) newWrong.add(q.id);
      ProductSupportModel.Result before = prior.search(q.text, 3),
          after = retained.search(q.text, 3);
      if (!RankedSupportExperiment.probe(q.id, "default", before)
          .equals(RankedSupportExperiment.probe(q.id, "default", after)))
        throw new AssertionError("Selector changed retained default");
      if (before.accepted
          && q.primary.contains(before.matches.get(0).article.topic)
          && !(result.accepted && correct)) retainedLost.add(q.id);
    }
    if (labels.size() != 1368 || !expected.isEmpty())
      throw new AssertionError("Missing selector probe");
    String report =
        "{\"predictions\":1368,\"retained_default_checks\":1368,\"unchanged_rank_hits\":"
            + topology
            + ",\"original_reference_checks\":"
            + refs
            + ",\"v5_previously_correct_lost_ids\":"
            + strings(v5Lost)
            + ",\"new_wrong_vs_v5_ids\":"
            + strings(newWrong)
            + ",\"retained_default_previously_correct_lost_ids\":"
            + strings(retainedLost)
            + ",\"production_eligible\":false}";
    Files.write(out.resolve("parity-retention.json"), report.getBytes(StandardCharsets.UTF_8));
    System.out.println(report);
  }

  static String strings(List<String> values) {
    StringBuilder out = new StringBuilder("[");
    for (String value : values) {
      if (out.length() > 1) out.append(',');
      out.append('"').append(value).append('"');
    }
    return out.append(']').toString();
  }
}
