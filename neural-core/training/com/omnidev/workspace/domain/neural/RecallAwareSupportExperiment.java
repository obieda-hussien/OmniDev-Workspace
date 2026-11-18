package com.omnidev.workspace.domain.neural;

import static com.omnidev.workspace.domain.neural.AnswerabilitySupportExperiment.*;
import static com.omnidev.workspace.domain.neural.AnswerabilityTrainer.*;
import static com.omnidev.workspace.domain.neural.HybridSupportExperiment.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Eight reserved cost-weighted fits; a fitting-data recall guard is not a generalization bound. */
public final class RecallAwareSupportExperiment {
  static final class Candidate {
    final Fit fit;
    final int positiveWeight;
    final double temperature, threshold, policyOnly;

    Candidate(Fit fit, int positiveWeight, List<Case> temp, List<Case> policy, List<Case> guard) {
      this.fit = fit;
      this.positiveWeight = positiveWeight;
      temperature = calibrateTemperature(temp, fit.model);
      threshold = calibrate(policy, guard, fit.model, temperature);
      policyOnly = calibrate(policy, policy, fit.model, temperature);
    }

    String json() {
      return "{\"positive_weight\":"
          + positiveWeight
          + ",\"fit\":"
          + fit.json().replace("development_nll", "development_cost4_bce")
          + ",\"temperature\":"
          + temperature
          + ",\"guarded_threshold\":"
          + threshold
          + ",\"policy_only_threshold\":"
          + policyOnly
          + "}";
    }
  }

  static double calibrate(List<Case> policy, List<Case> guard, Model model, double temp) {
    AnswerabilityTrainer.require(rows(policy), "policy");
    if (policy.size() != 75) throw new IllegalArgumentException("Recall POLICY count");
    if (guard.size() != 75 && guard.size() != 783)
      throw new IllegalArgumentException("Recall guard count");
    Set<String> ids = new HashSet<>();
    for (Case c : guard) {
      if (!Arrays.asList("train", "development", "temperature", "policy").contains(c.pair.split)
          || !ids.add(c.pair.id))
        throw new IllegalArgumentException("Observed/duplicate recall guard row");
    }
    double threshold = 0;
    int best = Integer.MAX_VALUE;
    for (int i = 0; i <= 40; i++) {
      double t = i / 40.0;
      if (measured(guard, model, temp, t).lost != 0) continue;
      Stats s = measured(policy, model, temp, t);
      if (s.wrong <= best) {
        threshold = t;
        best = s.wrong;
      }
    }
    return threshold;
  }

  static SupportAnswerabilityModel model(
      SupportSubwordModel proposer, SupportPairEncoder encoder, Candidate c, double threshold) {
    return new SupportAnswerabilityModel(
        proposer,
        encoder,
        c.fit.model.runtime,
        c.temperature,
        threshold,
        SupportAnswerabilityModel.binding(proposer, encoder),
        SupportAnswerabilityModel.VERSION);
  }

  static String factory(String method, String weights, Candidate c, String binding) {
    return "public static SupportAnswerabilityModel "
        + method
        + "(List<ProductSupportModel.Article>d,List<SupportQueryMemory.Exemplar>e){return new"
        + " SupportAnswerabilityModel(TrainedSubwordSupport.createCandidate(d,e),new"
        + " SupportPairEncoder(d),new TinyNeuralRuntime(new TinyNeuralRuntime.Dense(1024,1,"
        + weights
        + "(),new float[]{"
        + c.fit.model.weights[1024]
        + "f},TinyNeuralRuntime.Activation.LINEAR)).withSparseInput(),"
        + c.temperature
        + ","
        + c.threshold
        + ",\""
        + binding
        + "\",SupportAnswerabilityModel.VERSION);}\n";
  }

  static List<Case> fresh(SupportSubwordModel proposer, SupportPairEncoder encoder)
      throws Exception {
    List<String> lines =
        Files.readAllLines(
            Paths.get("neural-core/fixtures/support-recall-v9/fresh.tsv"), StandardCharsets.UTF_8);
    if (!lines.get(0).equals("id\tgroup\tsplit\tlanguage\ttopic\tlabel\tquery"))
      throw new IllegalArgumentException("Recall fresh schema");
    List<Case> out = new ArrayList<>();
    for (String line : lines.subList(1, lines.size())) {
      String[] r = line.split("\t", -1);
      if (r.length != 7 || !r[2].equals("fresh"))
        throw new IllegalArgumentException("Recall fresh role");
      int label = Integer.parseInt(r[5]);
      out.add(
          new Case(
              new Row(r[0], r[1], r[3], "fresh", label, encoder.encode(r[6], r[4])),
              r[6],
              label == 1 ? Collections.singleton(r[4]) : Collections.emptySet(),
              proposer,
              encoder));
    }
    if (out.size() != 90) throw new AssertionError("Recall fresh count");
    return out;
  }

  static String metric(List<Case> cases, SupportAnswerabilityModel model) {
    return AnswerabilityPreservationExperiment.report(cases, model);
  }

  static String paired(List<Case> cases, Candidate c) {
    int correct = 0;
    double loss = 0, brier = 0;
    for (Case row : cases) {
      double p = c.fit.model.probability(row.pair, c.temperature);
      loss += bce(p, row.pair.label);
      brier += (p - row.pair.label) * (p - row.pair.label);
      if ((p >= .5) == (row.pair.label == 1)) correct++;
    }
    return "{\"pairs\":"
        + cases.size()
        + ",\"correct_at_half\":"
        + correct
        + ",\"unweighted_nll\":"
        + loss / cases.size()
        + ",\"brier\":"
        + brier / cases.size()
        + "}";
  }

  static String arm(
      List<Case> cases, Candidate c, SupportSubwordModel proposer, SupportPairEncoder encoder) {
    return "{\"candidate\":"
        + c.json()
        + ",\"pipeline\":"
        + metric(cases, model(proposer, encoder, c, c.threshold))
        + ",\"paired\":"
        + paired(cases, c)
        + "}";
  }

  static List<Case> all(
      List<ProductSupportModel.Article> docs, List<SupportQueryMemory.Exemplar> examples)
      throws Exception {
    List<Case> cases = AnswerabilityPreservationExperiment.all(docs, examples);
    SupportSubwordModel proposer =
        SubwordSupportExperiment.checkpoint(
            "TrainedSubwordSupport", docs, examples, SupportSubwordModel.class);
    cases.addAll(fresh(proposer, new SupportPairEncoder(docs)));
    if (cases.size() != 1872) throw new AssertionError("Recall full corpus");
    return cases;
  }

  public static void main(String[] args) throws Exception {
    Path out = Paths.get(args[0]);
    Files.createDirectories(out);
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
    HybridSupportRetriever base = QueryMemoryExperiment.base(docs);
    List<Query> trainLabels = old("train", base);
    trainLabels.addAll(ExpandedSupportExperiment.load("train", base));
    List<Case> train = original(trainLabels, "train", true, memory, proposer, encoder);
    train.addAll(contrasts("train", proposer, encoder));
    List<Case> dev =
        original(
            ExpandedSupportExperiment.load("development", base),
            "development",
            false,
            memory,
            proposer,
            encoder);
    dev.addAll(contrasts("development", proposer, encoder));
    List<Case> temp = legacyCalibration(true, base, memory, proposer, encoder);
    temp.addAll(contrasts("temperature", proposer, encoder));
    List<Case> policy = legacyCalibration(false, base, memory, proposer, encoder);
    policy.addAll(contrasts("policy", proposer, encoder));
    List<Case> guard = new ArrayList<>(train);
    guard.addAll(dev);
    guard.addAll(temp);
    guard.addAll(policy);
    if (train.size() != 507
        || dev.size() != 135
        || temp.size() != 66
        || policy.size() != 75
        || guard.size() != 783) throw new AssertionError("Recall fitting roles");
    Fit best = null, unit = null, weighted = null;
    int bestWeight = 0;
    StringJoiner fits = new StringJoiner(",", "[", "]");
    for (int weight : new int[] {1, 4})
      for (double l2 : new double[] {.0001, .001})
        for (long seed : new long[] {42, 97}) {
          Fit f = RecallAwareTrainer.fit(rows(train), rows(dev), seed, l2, weight);
          fits.add(
              "{\"positive_weight\":"
                  + weight
                  + ",\"fit\":"
                  + f.json().replace("development_nll", "development_cost4_bce")
                  + "}");
          if (best == null || f.nll < best.nll - 1e-8) {
            best = f;
            bestWeight = weight;
          }
          if (weight == 1 && (unit == null || f.nll < unit.nll - 1e-8)) unit = f;
          if (weight == 4 && (weighted == null || f.nll < weighted.nll - 1e-8)) weighted = f;
        }
    Candidate selected = new Candidate(best, bestWeight, temp, policy, guard),
        one = new Candidate(unit, 1, temp, policy, guard),
        four = new Candidate(weighted, 4, temp, policy, guard);
    String selection =
        "{\"selected\":"
            + selected.json()
            + ",\"matched_unit\":"
            + one.json()
            + ",\"matched_weighted\":"
            + four.json()
            + ",\"fits\":"
            + fits
            + ",\"guard_rows\":783,\"production_eligible\":false}";
    Files.write(out.resolve("selection.json"), selection.getBytes(StandardCharsets.UTF_8));
    String binding = SupportAnswerabilityModel.binding(proposer, encoder);
    StringBuilder source =
        new StringBuilder(
            "package com.omnidev.workspace.domain.neural;\n"
                + "import java.util.List;\n"
                + "public final class TrainedRecallAwareSupport {\n");
    source
        .append(factory("createCandidate", "selectedWeights", selected, binding))
        .append(factory("createMatchedUnit", "unitWeights", one, binding))
        .append(factory("createMatchedWeighted", "weightedWeights", four, binding));
    source.append(
        "public static SupportAnswerabilityModel"
            + " createPolicyOnly(List<ProductSupportModel.Article>d,List<SupportQueryMemory.Exemplar>e){return"
            + " createCandidate(d,e).withThreshold("
            + selected.policyOnly
            + ");}\n");
    OfflineDecisionTrainer.Model.appendArrayFactory(
        source, "selectedWeights", Arrays.copyOf(best.model.weights, 1024));
    OfflineDecisionTrainer.Model.appendArrayFactory(
        source, "unitWeights", Arrays.copyOf(unit.model.weights, 1024));
    OfflineDecisionTrainer.Model.appendArrayFactory(
        source, "weightedWeights", Arrays.copyOf(weighted.model.weights, 1024));
    source.append(
        "public static ProductSupportModel"
            + " retainedDefault(List<ProductSupportModel.Article>d){return"
            + " TrainedProductSupport.create(d);}\n"
            + "private TrainedRecallAwareSupport(){}\n"
            + "}\n");
    Files.write(
        out.resolve("TrainedRecallAwareSupport.java"),
        source.toString().getBytes(StandardCharsets.UTF_8));
    // Freeze weights and both decision policies before reading/scoring the new90.
    List<Case> prior = AnswerabilityPreservationExperiment.all(docs, examples),
        fresh = fresh(proposer, encoder),
        stress = new ArrayList<>();
    for (Case c : prior) if (c.pair.id.startsWith("subword7-")) stress.add(c);
    SupportAnswerabilityModel deployed = model(proposer, encoder, selected, selected.threshold),
        policyOnly = deployed.withThreshold(selected.policyOnly),
        oldHead =
            SubwordSupportExperiment.checkpoint(
                "TrainedAnswerabilitySupport", docs, examples, SupportAnswerabilityModel.class);
    StringJoiner languages = new StringJoiner(",", "{", "}");
    for (String language : new String[] {"en", "ar", "mixed"}) {
      List<Case> part = new ArrayList<>();
      for (Case c : fresh) if (c.pair.language.equals(language)) part.add(c);
      languages.add("\"" + language + "\":" + metric(part, deployed));
    }
    String report =
        "{\"selection\":"
            + selection
            + ",\"guard\":"
            + metric(guard, deployed)
            + ",\"observed1782\":"
            + metric(prior, deployed)
            + ",\"policy_only_observed\":"
            + metric(prior, policyOnly)
            + ",\"v7_stress\":"
            + metric(stress, deployed)
            + ",\"fresh90\":"
            + arm(fresh, selected, proposer, encoder)
            + ",\"matched_unit_fresh\":"
            + arm(fresh, one, proposer, encoder)
            + ",\"matched_weighted_fresh\":"
            + arm(fresh, four, proposer, encoder)
            + ",\"v8_fresh\":"
            + metric(fresh, oldHead)
            + ",\"languages\":"
            + languages
            + ",\"production_eligible\":false,\"independent_human_labels\":false,\"physical_device_tested\":false}";
    Files.write(out.resolve("report.json"), report.getBytes(StandardCharsets.UTF_8));
    List<Case> all = new ArrayList<>(prior);
    all.addAll(fresh);
    SupportAnswerabilityModel a = model(proposer, encoder, one, one.threshold),
        b = model(proposer, encoder, four, four.threshold);
    StringBuilder expected = new StringBuilder(),
        arms = new StringBuilder(),
        controls = new StringBuilder();
    for (Case c : all) {
      expected.append(probe(c.pair.id, deployed.search(c.query, 10))).append('\n');
      arms.append(armProbe(c.pair.id, a.search(c.query, 10), b.search(c.query, 10))).append('\n');
      controls.append(probe(c.pair.id, policyOnly.search(c.query, 10))).append('\n');
    }
    Files.write(out.resolve("expected.tsv"), expected.toString().getBytes(StandardCharsets.UTF_8));
    Files.write(out.resolve("arm-expected.tsv"), arms.toString().getBytes(StandardCharsets.UTF_8));
    Files.write(
        out.resolve("policy-expected.tsv"), controls.toString().getBytes(StandardCharsets.UTF_8));
    System.out.println(report);
  }
}
