package com.omnidev.workspace.domain.neural;

import static com.omnidev.workspace.domain.neural.AnswerabilitySupportExperiment.*;
import static com.omnidev.workspace.domain.neural.ContrastiveSupportExperiment.probe;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Reload all six exported policies and retain every loss versus the exact frozen proposer. */
public final class ContrastiveSupportCheckpointSmoke {
  public static void main(String[] args) throws Exception {
    Path out = Paths.get(args[0]);
    List<ProductSupportModel.Article> docs =
        ProductSupportExperiment.loadArticles(Paths.get("build/product-support/documents.tsv"));
    List<SupportQueryMemory.Exemplar> examples =
        QueryMemoryExperiment.examples(Paths.get("build/query-memory-v5/exemplars.tsv"));
    SupportContrastiveModel model = TrainedContrastiveSupport.createCandidate(docs, examples),
        policy = TrainedContrastiveSupport.createPolicyOnly(docs, examples);
    SupportContrastiveModel[] arms = {
      TrainedContrastiveSupport.createMatchedLinear(docs, examples),
      TrainedContrastiveSupport.createMatchedNonlinear(docs, examples),
      TrainedContrastiveSupport.createMatchedBce(docs, examples),
      TrainedContrastiveSupport.createMatchedRanking(docs, examples)
    };
    ProductSupportModel prior = TrainedProductSupport.create(docs),
        retained = TrainedContrastiveSupport.retainedDefault(docs);
    Map<String, String> e = AnswerabilityCheckpointSmoke.expected(out.resolve("expected.tsv")),
        a = AnswerabilityCheckpointSmoke.expected(out.resolve("arm-expected.tsv")),
        p = AnswerabilityCheckpointSmoke.expected(out.resolve("policy-expected.tsv"));
    List<Case> old = new ArrayList<>(), fresh = new ArrayList<>();
    int count = 0, refs = 0;
    for (Case c : ContrastiveSupportExperiment.all(docs, examples)) {
      SupportContrastiveModel.Result result = model.search(c.query, 10),
          control = policy.search(c.query, 10);
      SupportContrastiveModel.Result[] matched = new SupportContrastiveModel.Result[4];
      for (int i = 0; i < 4; i++) matched[i] = arms[i].search(c.query, 10);
      if (!probe(c.pair.id, result).equals(e.remove(c.pair.id))
          || !probe(c.pair.id, control).equals(p.remove(c.pair.id))
          || !ContrastiveSupportExperiment.armsProbe(c.pair.id, matched)
              .equals(a.remove(c.pair.id)))
        throw new AssertionError("Contrastive reload drift " + c.pair.id);
      List<SupportContrastiveModel.Result> all = new ArrayList<>(Arrays.asList(matched));
      all.add(result);
      all.add(control);
      for (SupportContrastiveModel.Result r : all) {
        if (r.accepted && !c.proposal.accepted
            || r.proposalAccepted != c.proposal.accepted
            || r.matches.size() != c.proposal.matches.size())
          throw new AssertionError("Contrastive veto upgraded/changed proposal");
        for (int i = 0; i < r.matches.size(); i++) {
          SupportQueryMemory.Hit hit = r.matches.get(i), before = c.proposal.matches.get(i);
          if (!hit.topic.id.equals(before.topic.id)
              || hit.score != before.score
              || hit.coverage != before.coverage)
            throw new AssertionError("Contrastive changed ranking");
          for (ProductSupportModel.Article ref : hit.topic.references) {
            if (!docs.contains(ref)) throw new AssertionError("Contrastive invented source");
            refs++;
          }
        }
      }
      if (!RankedSupportExperiment.probe(c.pair.id, "default", prior.search(c.query, 3))
          .equals(RankedSupportExperiment.probe(c.pair.id, "default", retained.search(c.query, 3))))
        throw new AssertionError("Contrastive changed default");
      if (!c.pair.id.startsWith("answer8-")
          && !c.pair.id.startsWith("recall9-")
          && !c.pair.id.startsWith("contrast10-")) old.add(c);
      if (c.pair.id.startsWith("contrast10-")) fresh.add(c);
      count++;
    }
    if (count != 1968
        || old.size() != 1488
        || fresh.size() != 96
        || !e.isEmpty()
        || !a.isEmpty()
        || !p.isEmpty()) throw new AssertionError("Incomplete contrastive reload");
    String report =
        "{\"predictions\":1968,\"matched_arm_predictions\":7872,\"policy_only_predictions\":1968,\"retained_default_checks\":1968,\"original_reference_checks\":"
            + refs
            + ",\"selected_parameters\":"
            + model.parameterCount()
            + ",\"old1488\":"
            + ContrastiveSupportExperiment.metric(old, model)
            + ",\"fresh96\":"
            + ContrastiveSupportExperiment.metric(fresh, model)
            + ",\"production_eligible\":false}";
    Files.write(out.resolve("parity-retention.json"), report.getBytes(StandardCharsets.UTF_8));
    System.out.println(report);
  }
}
