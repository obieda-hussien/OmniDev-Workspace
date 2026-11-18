package com.omnidev.workspace.domain.neural;

import static com.omnidev.workspace.domain.neural.AnswerabilitySupportExperiment.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Reserved fixed-head follow-up: only POLICY decides the alternate acceptance cutoff. */
public final class AnswerabilityPreservationExperiment {
  static Stats measure(List<Case> cases, SupportAnswerabilityModel model) {
    Stats s = new Stats();
    for (Case c : cases) {
      SupportAnswerabilityModel.Result r = model.search(c.query, 10);
      boolean correct = c.pipeline.label == 1;
      s.count++;
      if (r.proposalAccepted) {
        if (correct) s.baselineCorrect++;
        else s.baselineWrong++;
      }
      if (r.accepted) {
        if (correct) s.correct++;
        else s.wrong++;
      }
      if (r.proposalAccepted && correct && !r.accepted) s.lost++;
      if (r.accepted && !r.proposalAccepted) s.newWrong++;
      if (c.primary.isEmpty()) {
        s.unsupported++;
        if (!r.accepted) s.refused++;
      }
    }
    return s;
  }

  static double calibrate(List<Case> policy, SupportAnswerabilityModel original) {
    AnswerabilityTrainer.require(rows(policy), "policy");
    if (policy.size() != 75) throw new AssertionError("Follow-up POLICY size");
    int best = Integer.MAX_VALUE;
    double selected = 0;
    for (int i = 0; i <= 40; i++) {
      double t = i / 40.0;
      Stats s = measure(policy, original.withThreshold(t));
      if (s.lost == 0 && s.wrong <= best) {
        best = s.wrong;
        selected = t;
      }
    }
    return selected;
  }

  static String report(List<Case> cases, SupportAnswerabilityModel model) {
    List<String> lost = new ArrayList<>(), vetoed = new ArrayList<>(), wrong = new ArrayList<>();
    for (Case c : cases) {
      SupportAnswerabilityModel.Result r = model.search(c.query, 10);
      if (r.proposalAccepted && !r.accepted) (c.pipeline.label == 1 ? lost : vetoed).add(c.pair.id);
      if (r.accepted && c.pipeline.label == 0) wrong.add(c.pair.id);
    }
    return "{\"pipeline\":"
        + measure(cases, model).json()
        + ",\"lost_correct_ids\":"
        + strings(lost)
        + ",\"vetoed_wrong_ids\":"
        + strings(vetoed)
        + ",\"accepted_wrong_ids\":"
        + strings(wrong)
        + "}";
  }

  static String strings(List<String> v) {
    StringJoiner s = new StringJoiner(",", "[", "]");
    for (String x : v) s.add("\"" + x + "\"");
    return s.toString();
  }

  static List<Case> all(
      List<ProductSupportModel.Article> docs, List<SupportQueryMemory.Exemplar> examples)
      throws Exception {
    HybridSupportRetriever base = QueryMemoryExperiment.base(docs);
    SupportQueryMemory memory =
        SubwordSupportExperiment.checkpoint(
            "TrainedQueryMemory", docs, examples, SupportQueryMemory.class);
    SupportSubwordModel proposer =
        SubwordSupportExperiment.checkpoint(
            "TrainedSubwordSupport", docs, examples, SupportSubwordModel.class);
    SupportPairEncoder encoder = new SupportPairEncoder(docs);
    List<HybridSupportExperiment.Query> labels = SubwordSupportExperiment.oldQueries(base);
    labels.addAll(SubwordSupportExperiment.fresh(base));
    List<Case> all = original(labels, "observed", false, memory, proposer, encoder);
    for (String split :
        new String[] {"train", "development", "temperature", "policy", "fresh", "followup"})
      all.addAll(contrasts(split, proposer, encoder));
    if (all.size() != 1782) throw new AssertionError("Follow-up full corpus");
    return all;
  }

  public static void main(String[] args) throws Exception {
    Path out = Paths.get(args[0]);
    List<ProductSupportModel.Article> docs =
        ProductSupportExperiment.loadArticles(Paths.get("build/product-support/documents.tsv"));
    List<SupportQueryMemory.Exemplar> examples =
        QueryMemoryExperiment.examples(Paths.get("build/query-memory-v5/exemplars.tsv"));
    SupportQueryMemory memory =
        SubwordSupportExperiment.checkpoint(
            "TrainedQueryMemory", docs, examples, SupportQueryMemory.class);
    SupportSubwordModel proposer =
        SubwordSupportExperiment.checkpoint(
            "TrainedSubwordSupport", docs, examples, SupportSubwordModel.class);
    SupportPairEncoder encoder = new SupportPairEncoder(docs);
    SupportAnswerabilityModel original =
        SubwordSupportExperiment.checkpoint(
            "TrainedAnswerabilitySupport", docs, examples, SupportAnswerabilityModel.class);
    List<Case> policy =
        legacyCalibration(false, QueryMemoryExperiment.base(docs), memory, proposer, encoder);
    policy.addAll(contrasts("policy", proposer, encoder));
    double threshold = calibrate(policy, original);
    String selection =
        "{\"protocol\":\"8-preservation-followup\",\"threshold\":"
            + threshold
            + ",\"original_fingerprint\":\""
            + original.fingerprint()
            + "\",\"policy\":"
            + measure(policy, original.withThreshold(threshold)).json()
            + ",\"production_eligible\":false}";
    Files.write(
        out.resolve("preservation-selection.json"), selection.getBytes(StandardCharsets.UTF_8));
    String source =
        "package com.omnidev.workspace.domain.neural;\n"
            + "import java.util.List;\n"
            + "public final class TrainedAnswerabilityPreservation {\n"
            + "public static SupportAnswerabilityModel"
            + " createCandidate(List<ProductSupportModel.Article>d,List<SupportQueryMemory.Exemplar>e){return"
            + " TrainedAnswerabilitySupport.createCandidate(d,e).withThreshold("
            + threshold
            + ");}\n"
            + "public static ProductSupportModel"
            + " retainedDefault(List<ProductSupportModel.Article>d){return"
            + " TrainedProductSupport.create(d);}\n"
            + "private TrainedAnswerabilityPreservation(){}\n"
            + "}\n";
    Files.write(
        out.resolve("TrainedAnswerabilityPreservation.java"),
        source.getBytes(StandardCharsets.UTF_8));
    // Alternate decision rule is exported before new follow-up queries are read/scored.
    List<Case> all = all(docs, examples),
        old = new ArrayList<>(),
        stress = new ArrayList<>(),
        fresh = new ArrayList<>(),
        followup = new ArrayList<>();
    SupportAnswerabilityModel model = original.withThreshold(threshold);
    StringBuilder expected = new StringBuilder();
    for (Case c : all) {
      if (!c.pair.id.startsWith("answer8-")) old.add(c);
      if (c.pair.id.startsWith("subword7-")) stress.add(c);
      if (c.pair.split.equals("fresh")) fresh.add(c);
      if (c.pair.split.equals("followup")) followup.add(c);
      expected.append(probe(c.pair.id, model.search(c.query, 10))).append('\n');
    }
    String r =
        "{\"selection\":"
            + selection
            + ",\"old1488\":"
            + report(old, model)
            + ",\"v7_stress\":"
            + report(stress, model)
            + ",\"observed_fresh72\":"
            + report(fresh, model)
            + ",\"followup48\":"
            + report(followup, model)
            + ",\"original_followup48\":"
            + report(followup, original)
            + ",\"production_eligible\":false,\"physical_device_tested\":false,\"independent_human_labels\":false}";
    Files.write(out.resolve("preservation-report.json"), r.getBytes(StandardCharsets.UTF_8));
    Files.write(
        out.resolve("preservation-expected.tsv"),
        expected.toString().getBytes(StandardCharsets.UTF_8));
    System.out.println(r);
  }
}
