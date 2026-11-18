package com.omnidev.workspace.domain.neural;

import static com.omnidev.workspace.domain.neural.AnswerabilitySupportExperiment.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Exact exported policy/arm reload, frozen rank/source/default and full loss accounting. */
public final class RecallAwareCheckpointSmoke {
  public static void main(String[] args) throws Exception {
    Path out = Paths.get(args[0]);
    List<ProductSupportModel.Article> docs =
        ProductSupportExperiment.loadArticles(Paths.get("build/product-support/documents.tsv"));
    List<SupportQueryMemory.Exemplar> examples =
        QueryMemoryExperiment.examples(Paths.get("build/query-memory-v5/exemplars.tsv"));
    SupportAnswerabilityModel model = TrainedRecallAwareSupport.createCandidate(docs, examples),
        policy = TrainedRecallAwareSupport.createPolicyOnly(docs, examples),
        unit = TrainedRecallAwareSupport.createMatchedUnit(docs, examples),
        weighted = TrainedRecallAwareSupport.createMatchedWeighted(docs, examples);
    ProductSupportModel prior = TrainedProductSupport.create(docs),
        retained = TrainedRecallAwareSupport.retainedDefault(docs);
    Map<String, String> e = AnswerabilityCheckpointSmoke.expected(out.resolve("expected.tsv")),
        arms = AnswerabilityCheckpointSmoke.expected(out.resolve("arm-expected.tsv")),
        controls = AnswerabilityCheckpointSmoke.expected(out.resolve("policy-expected.tsv"));
    List<Case> old = new ArrayList<>(), fresh = new ArrayList<>();
    int count = 0, refs = 0;
    for (Case c : RecallAwareSupportExperiment.all(docs, examples)) {
      SupportAnswerabilityModel.Result r = model.search(c.query, 10),
          u = unit.search(c.query, 10),
          w = weighted.search(c.query, 10),
          p = policy.search(c.query, 10);
      if (!probe(c.pair.id, r).equals(e.remove(c.pair.id))
          || !armProbe(c.pair.id, u, w).equals(arms.remove(c.pair.id))
          || !probe(c.pair.id, p).equals(controls.remove(c.pair.id)))
        throw new AssertionError("Recall compiled numeric drift " + c.pair.id);
      for (SupportAnswerabilityModel.Result a :
          new SupportAnswerabilityModel.Result[] {r, u, w, p}) {
        if (a.accepted && !c.proposal.accepted
            || a.proposalAccepted != c.proposal.accepted
            || a.matches.size() != c.proposal.matches.size())
          throw new AssertionError("Recall upgraded/changed proposal");
        for (int i = 0; i < a.matches.size(); i++) {
          SupportQueryMemory.Hit hit = a.matches.get(i), before = c.proposal.matches.get(i);
          if (!hit.topic.id.equals(before.topic.id)
              || hit.score != before.score
              || hit.coverage != before.coverage)
            throw new AssertionError("Recall frozen ranking drift");
          for (ProductSupportModel.Article ref : hit.topic.references) {
            if (!docs.contains(ref)) throw new AssertionError("Recall forged source");
            refs++;
          }
        }
      }
      if (!RankedSupportExperiment.probe(c.pair.id, "default", prior.search(c.query, 3))
          .equals(RankedSupportExperiment.probe(c.pair.id, "default", retained.search(c.query, 3))))
        throw new AssertionError("Recall changed retained default");
      if (!c.pair.id.startsWith("answer8-") && !c.pair.id.startsWith("recall9-")) old.add(c);
      if (c.pair.id.startsWith("recall9-")) fresh.add(c);
      count++;
    }
    if (count != 1872
        || old.size() != 1488
        || fresh.size() != 90
        || !e.isEmpty()
        || !arms.isEmpty()
        || !controls.isEmpty()) throw new AssertionError("Incomplete recall reload");
    String report =
        "{\"predictions\":1872,\"matched_arm_predictions\":3744,\"policy_only_predictions\":1872,\"retained_default_checks\":1872,\"original_reference_checks\":"
            + refs
            + ",\"old1488\":"
            + RecallAwareSupportExperiment.metric(old, model)
            + ",\"fresh90\":"
            + RecallAwareSupportExperiment.metric(fresh, model)
            + ",\"production_eligible\":false}";
    Files.write(out.resolve("parity-retention.json"), report.getBytes(StandardCharsets.UTF_8));
    System.out.println(report);
  }
}
