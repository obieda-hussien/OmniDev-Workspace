package com.omnidev.workspace.domain.neural;

import static com.omnidev.workspace.domain.neural.SubwordSupportExperiment.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class SubwordSupportCheckpointSmoke {
  public static void main(String[] args) throws Exception {
    Path out = Paths.get(args[0]);
    List<ProductSupportModel.Article> docs =
        ProductSupportExperiment.loadArticles(Paths.get("build/product-support/documents.tsv"));
    List<SupportQueryMemory.Exemplar> examples =
        QueryMemoryExperiment.examples(Paths.get("build/query-memory-v5/exemplars.tsv"));
    SupportQueryMemory memory = TrainedQueryMemory.createCandidate(docs, examples);
    SupportSubwordModel model = TrainedSubwordSupport.createCandidate(docs, examples);
    SupportSubwordModel originalArm = TrainedSubwordSupport.createMatchedOriginal(docs, examples),
        augmentedArm = TrainedSubwordSupport.createMatchedAugmented(docs, examples);
    ProductSupportModel prior = TrainedProductSupport.create(docs),
        retained = TrainedSubwordSupport.retainedDefault(docs);
    List<HybridSupportExperiment.Query> labels = oldQueries(QueryMemoryExperiment.base(docs));
    labels.addAll(fresh(QueryMemoryExperiment.base(docs)));
    Map<String, String> expected = new HashMap<>();
    for (String line : Files.readAllLines(out.resolve("expected.tsv"), StandardCharsets.UTF_8))
      if (expected.put(line.split("\t", 2)[0], line) != null)
        throw new AssertionError("Duplicate subword probe");
    Map<String, ProductSupportModel.Article> original = new HashMap<>();
    Map<String, String> expectedArms = new HashMap<>();
    for (String line : Files.readAllLines(out.resolve("arm-expected.tsv"), StandardCharsets.UTF_8))
      if (expectedArms.put(line.split("\t", 2)[0], line) != null)
        throw new AssertionError("Duplicate arm probe");
    for (ProductSupportModel.Article a : docs) original.put(a.id, a);
    List<String> v5Lost = new ArrayList<>(),
        newWrong = new ArrayList<>(),
        retainedLost = new ArrayList<>();
    int refs = 0, topology = 0;
    for (HybridSupportExperiment.Query q : labels) {
      SupportSubwordModel.Result result = model.search(q.text, 10);
      SupportQueryMemory.Result old = memory.search(q.text, 10);
      SupportSubwordModel.Result originalResult = originalArm.search(q.text, 10),
          augmentedResult = augmentedArm.search(q.text, 10);
      if (!armProbe(q.id, originalResult, augmentedResult).equals(expectedArms.remove(q.id)))
        throw new AssertionError("Compiled matched-arm numeric drift");
      for (SupportSubwordModel.Result arm :
          new SupportSubwordModel.Result[] {originalResult, augmentedResult}) {
        if (arm.matches.size() != old.matches.size())
          throw new AssertionError("Matched arm changed rank count");
        for (int i = 0; i < old.matches.size(); i++)
          if (!arm.matches.get(i).topic.id.equals(old.matches.get(i).topic.id)
              || arm.matches.get(i).score != old.matches.get(i).score
              || arm.matches.get(i).coverage != old.matches.get(i).coverage)
            throw new AssertionError("Matched arm changed frozen rank");
      }
      if (!probe(q.id, result).equals(expected.remove(q.id)))
        throw new AssertionError("Subword numeric/typed output drift: " + q.id);
      if (old.accepted != result.baselineAccepted || old.matches.size() != result.matches.size())
        throw new AssertionError("Subword baseline drift");
      for (int i = 0; i < old.matches.size(); i++) {
        SupportQueryMemory.Hit a = old.matches.get(i), b = result.matches.get(i);
        if (!a.topic.id.equals(b.topic.id)
            || Double.doubleToLongBits(a.score) != Double.doubleToLongBits(b.score)
            || Double.doubleToLongBits(a.coverage) != Double.doubleToLongBits(b.coverage))
          throw new AssertionError("Subword altered frozen ranking");
        topology++;
        for (ProductSupportModel.Article ref : b.topic.references) {
          ProductSupportModel.Article real = original.get(ref.id);
          if (real == null
              || !ref.topic.equals(b.topic.id)
              || !ref.question.equals(real.question)
              || !ref.text.equals(real.text)
              || !ref.status.equals(real.status)
              || !ref.editions.equals(real.editions))
            throw new AssertionError("Subword invented source");
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
        throw new AssertionError("Subword changed retained default");
      if (before.accepted
          && q.primary.contains(before.matches.get(0).article.topic)
          && !(result.accepted && correct)) retainedLost.add(q.id);
    }
    if (labels.size() != 1488 || !expected.isEmpty() || !expectedArms.isEmpty())
      throw new AssertionError("Missing subword probe");
    String report =
        "{\"predictions\":1488,\"matched_arm_predictions\":2976,\"retained_default_checks\":1488,\"unchanged_rank_hits\":"
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
