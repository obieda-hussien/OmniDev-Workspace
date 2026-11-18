package com.omnidev.workspace.domain.neural;

import static com.omnidev.workspace.domain.neural.AnswerabilitySupportExperiment.*;
import static com.omnidev.workspace.domain.neural.AnswerabilityTrainer.bce;
import static com.omnidev.workspace.domain.neural.ContrastiveSupportTrainer.*;
import static com.omnidev.workspace.domain.neural.HybridSupportExperiment.*;

import com.omnidev.workspace.domain.neural.AnswerabilityTrainer.Row;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Eight reserved balanced/pairwise fits; a fitting-data recall guard is not a generalization bound.
 */
public final class ContrastiveSupportExperiment {
  static final class Candidate {
    final Fit fit;
    final int hidden;
    final double temperature, threshold, policyOnly;

    Candidate(Fit fit, int hidden, List<Case> temp, List<Case> policy, List<Case> guard) {
      this.fit = fit;
      this.hidden = hidden;
      temperature = temperature(temp, fit.model);
      threshold = calibrate(policy, guard, fit.model, temperature);
      policyOnly = calibrate(policy, policy, fit.model, temperature);
    }

    String json() {
      return "{\"hidden\":"
          + hidden
          + ",\"fit\":"
          + fit.json()
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
    double protectedMinimum = 1;
    for (Case c : guard)
      if (c.proposal.accepted && c.pipeline.label == 1)
        protectedMinimum = Math.min(protectedMinimum, model.probability(c.pipeline, temp));
    List<Double> policyWrong = new ArrayList<>();
    for (Case c : policy)
      if (c.proposal.accepted && c.pipeline.label == 0)
        policyWrong.add(model.probability(c.pipeline, temp));
    double threshold = 0;
    int best = Integer.MAX_VALUE;
    for (int i = 0; i <= 40; i++) {
      double t = i / 40.0;
      if (t > protectedMinimum) continue;
      int wrong = 0;
      for (double probability : policyWrong) if (probability >= t) wrong++;
      if (wrong <= best) {
        threshold = t;
        best = wrong;
      }
    }
    return threshold;
  }

  static SupportContrastiveModel model(
      SupportSubwordModel proposer, SupportPairEncoder encoder, Candidate c, double threshold) {
    return new SupportContrastiveModel(
        proposer,
        encoder,
        c.fit.model.weights,
        c.fit.model.hidden,
        c.temperature,
        threshold,
        SupportContrastiveModel.binding(proposer, encoder),
        SupportContrastiveModel.VERSION);
  }

  static String factory(String method, String weights, Candidate c, String binding) {
    return "public static SupportContrastiveModel "
        + method
        + "(List<ProductSupportModel.Article>d,List<SupportQueryMemory.Exemplar>e){return new"
        + " SupportContrastiveModel(TrainedSubwordSupport.createCandidate(d,e),new"
        + " SupportPairEncoder(d),"
        + weights
        + "(),"
        + c.fit.model.hidden
        + ","
        + c.temperature
        + ","
        + c.threshold
        + ",\""
        + binding
        + "\",SupportContrastiveModel.VERSION); }\n";
  }

  static List<Case> fresh(SupportSubwordModel proposer, SupportPairEncoder encoder)
      throws Exception {
    List<String> lines =
        Files.readAllLines(
            Paths.get("neural-core/fixtures/support-contrastive-v10/fresh.tsv"),
            StandardCharsets.UTF_8);
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
    if (out.size() != 96) throw new AssertionError("Recall fresh count");
    return out;
  }

  static String metric(List<Case> cases, SupportContrastiveModel model) {
    Stats s = new Stats();
    List<String> lost = new ArrayList<>(), vetoed = new ArrayList<>(), wrong = new ArrayList<>();
    for (Case c : cases) {
      SupportContrastiveModel.Result r = model.search(c.query, 10);
      boolean correct = c.pipeline.label == 1;
      s.count++;
      if (r.proposalAccepted) {
        if (correct) s.baselineCorrect++;
        else s.baselineWrong++;
      }
      if (r.accepted) {
        if (correct) s.correct++;
        else {
          s.wrong++;
          wrong.add(c.pair.id);
        }
      }
      if (r.proposalAccepted && !r.accepted) {
        if (correct) {
          s.lost++;
          lost.add(c.pair.id);
        } else vetoed.add(c.pair.id);
      }
      if (r.accepted && !r.proposalAccepted) s.newWrong++;
      if (c.primary.isEmpty()) {
        s.unsupported++;
        if (!r.accepted) s.refused++;
      }
    }
    return "{\"pipeline\":"
        + s.json()
        + ",\"lost_correct_ids\":"
        + AnswerabilityPreservationExperiment.strings(lost)
        + ",\"vetoed_wrong_ids\":"
        + AnswerabilityPreservationExperiment.strings(vetoed)
        + ",\"accepted_wrong_ids\":"
        + AnswerabilityPreservationExperiment.strings(wrong)
        + "}";
  }

  static Stats measured(List<Case> cases, Model model, double temp, double threshold) {
    Stats s = new Stats();
    for (Case c : cases) {
      boolean before = c.proposal.accepted,
          correct = c.pipeline.label == 1,
          accepted = before && model.probability(c.pipeline, temp) >= threshold;
      s.count++;
      if (before) {
        if (correct) s.baselineCorrect++;
        else s.baselineWrong++;
      }
      if (accepted) {
        if (correct) s.correct++;
        else s.wrong++;
      }
      if (before && correct && !accepted) s.lost++;
    }
    return s;
  }

  static double temperature(List<Case> cases, Model model) {
    AnswerabilityTrainer.require(rows(cases), "temperature");
    if (cases.size() != 66) throw new IllegalArgumentException("Contrast TEMP count");
    double selected = 1, best = Double.POSITIVE_INFINITY;
    for (double t : new double[] {.5, .75, 1, 1.5, 2, 3}) {
      double loss = ContrastiveSupportTrainer.nll(rows(cases), model, t);
      if (loss < best - 1e-12) {
        selected = t;
        best = loss;
      }
    }
    return selected;
  }

  static String probe(String id, SupportContrastiveModel.Result r) {
    StringBuilder s =
        new StringBuilder(
            id
                + "\t"
                + r.accepted
                + "\t"
                + r.proposalAccepted
                + "\t"
                + r.scored
                + "\t"
                + String.format(Locale.ROOT, "%.9f", r.answerability));
    for (SupportQueryMemory.Hit h : r.matches)
      s.append('\t')
          .append(h.topic.id)
          .append(':')
          .append(String.format(Locale.ROOT, "%.9f", h.score))
          .append(':')
          .append(String.format(Locale.ROOT, "%.9f", h.coverage));
    return s.toString();
  }

  static String armsProbe(String id, SupportContrastiveModel.Result... results) {
    StringBuilder s = new StringBuilder(id);
    for (SupportContrastiveModel.Result r : results)
      s.append('\t')
          .append(r.accepted)
          .append('\t')
          .append(r.scored)
          .append('\t')
          .append(String.format(Locale.ROOT, "%.9f", r.answerability));
    return s.toString();
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
    Map<String, double[]> ordering = new TreeMap<>();
    for (Case row : cases) {
      double[] values =
          ordering.computeIfAbsent(
              row.pair.group + "|" + row.pair.language, k -> new double[] {Double.NaN, Double.NaN});
      values[row.pair.label] = c.fit.model.probability(row.pair, c.temperature);
    }
    int pairs = 0, ordered = 0, tied = 0;
    for (double[] v : ordering.values())
      if (Double.isFinite(v[0]) && Double.isFinite(v[1])) {
        pairs++;
        if (v[1] > v[0]) ordered++;
        if (v[1] == v[0]) tied++;
      }
    return "{\"ordering_pairs\":"
        + pairs
        + ",\"correct_order\":"
        + ordered
        + ",\"tied_order\":"
        + tied
        + ",\"pairs\":"
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
    List<Case> cases = RecallAwareSupportExperiment.all(docs, examples);
    SupportSubwordModel proposer =
        SubwordSupportExperiment.checkpoint(
            "TrainedSubwordSupport", docs, examples, SupportSubwordModel.class);
    cases.addAll(fresh(proposer, new SupportPairEncoder(docs)));
    if (cases.size() != 1968) throw new AssertionError("Recall full corpus");
    return cases;
  }

  static boolean better(Fit a, Fit b) {
    if (b == null || a.nll < b.nll - 1e-8) return true;
    if (Math.abs(a.nll - b.nll) > 1e-8) return false;
    if (a.model.weights.length != b.model.weights.length)
      return a.model.weights.length < b.model.weights.length;
    if (a.ranking != b.ranking) return a.ranking < b.ranking;
    return a.seed < b.seed;
  }

  static final class TrainedViaReflection {
    static SupportAnswerabilityModel unit(
        List<ProductSupportModel.Article> d, List<SupportQueryMemory.Exemplar> e) throws Exception {
      return (SupportAnswerabilityModel)
          Class.forName("com.omnidev.workspace.domain.neural.TrainedRecallAwareSupport")
              .getMethod("createMatchedUnit", List.class, List.class)
              .invoke(null, d, e);
    }
  }

  static String priorPairs(List<Case> cases, SupportAnswerabilityModel model) {
    int correct = 0;
    double loss = 0, brier = 0;
    Map<String, double[]> ordering = new TreeMap<>();
    for (Case row : cases) {
      String topic = row.pair.group.substring("contrast10-fresh-".length());
      double p = model.score(row.query, topic);
      loss += bce(p, row.pair.label);
      brier += (p - row.pair.label) * (p - row.pair.label);
      if ((p >= .5) == (row.pair.label == 1)) correct++;
      ordering
              .computeIfAbsent(
                  row.pair.group + "|" + row.pair.language,
                  k -> new double[] {Double.NaN, Double.NaN})[row.pair.label] =
          p;
    }
    int pairs = 0, ordered = 0, tied = 0;
    for (double[] values : ordering.values())
      if (Double.isFinite(values[0]) && Double.isFinite(values[1])) {
        pairs++;
        if (values[1] > values[0]) ordered++;
        if (values[1] == values[0]) tied++;
      }
    return "{\"ordering_pairs\":"
        + pairs
        + ",\"correct_order\":"
        + ordered
        + ",\"tied_order\":"
        + tied
        + ",\"pairs\":"
        + cases.size()
        + ",\"correct_at_half\":"
        + correct
        + ",\"unweighted_nll\":"
        + loss / cases.size()
        + ",\"brier\":"
        + brier / cases.size()
        + "}";
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
    Fit best = null, linear = null, nonlinear = null, bce = null, ranking = null;
    StringJoiner fits = new StringJoiner(",", "[", "]");
    for (int hidden : new int[] {0, 8})
      for (double lambda : new double[] {0, .25})
        for (long seed : new long[] {42, 97}) {
          Fit f = ContrastiveSupportTrainer.fit(rows(train), rows(dev), hidden, lambda, seed);
          fits.add(f.json());
          System.err.println("Completed fit " + f.json());
          if (better(f, best)) best = f;
          if (hidden == 0 && better(f, linear)) linear = f;
          if (hidden == 8 && better(f, nonlinear)) nonlinear = f;
          if (lambda == 0 && better(f, bce)) bce = f;
          if (lambda > 0 && better(f, ranking)) ranking = f;
        }
    Candidate selected = new Candidate(best, best.model.hidden, temp, policy, guard),
        one = new Candidate(linear, 0, temp, policy, guard),
        four = new Candidate(nonlinear, 8, temp, policy, guard),
        bceArm = new Candidate(bce, bce.model.hidden, temp, policy, guard),
        rankingArm = new Candidate(ranking, ranking.model.hidden, temp, policy, guard);
    String selection =
        "{\"selected\":"
            + selected.json()
            + ",\"matched_linear\":"
            + one.json()
            + ",\"matched_nonlinear\":"
            + four.json()
            + ",\"matched_bce\":"
            + bceArm.json()
            + ",\"matched_ranking\":"
            + rankingArm.json()
            + ",\"fits\":"
            + fits
            + ",\"guard_rows\":783,\"production_eligible\":false}";
    Files.write(out.resolve("selection.json"), selection.getBytes(StandardCharsets.UTF_8));
    String binding = SupportContrastiveModel.binding(proposer, encoder);
    StringBuilder source =
        new StringBuilder(
            "package com.omnidev.workspace.domain.neural;\n"
                + "import java.util.List;\n"
                + "public final class TrainedContrastiveSupport {\n");
    source
        .append(factory("createCandidate", "selectedWeights", selected, binding))
        .append(factory("createMatchedLinear", "unitWeights", one, binding))
        .append(factory("createMatchedNonlinear", "weightedWeights", four, binding))
        .append(factory("createMatchedBce", "bceWeights", bceArm, binding))
        .append(factory("createMatchedRanking", "rankingWeights", rankingArm, binding));
    source.append(
        "public static SupportContrastiveModel"
            + " createPolicyOnly(List<ProductSupportModel.Article>d,List<SupportQueryMemory.Exemplar>e){return"
            + " createCandidate(d,e).withThreshold("
            + selected.policyOnly
            + ");}\n");
    OfflineDecisionTrainer.Model.appendArrayFactory(source, "selectedWeights", best.model.weights);
    OfflineDecisionTrainer.Model.appendArrayFactory(source, "unitWeights", linear.model.weights);
    OfflineDecisionTrainer.Model.appendArrayFactory(
        source, "weightedWeights", nonlinear.model.weights);
    OfflineDecisionTrainer.Model.appendArrayFactory(source, "bceWeights", bce.model.weights);
    OfflineDecisionTrainer.Model.appendArrayFactory(
        source, "rankingWeights", ranking.model.weights);
    source.append(
        "public static ProductSupportModel"
            + " retainedDefault(List<ProductSupportModel.Article>d){return"
            + " TrainedProductSupport.create(d);}\n"
            + "private TrainedContrastiveSupport(){}\n"
            + "}\n");
    Files.write(
        out.resolve("TrainedContrastiveSupport.java"),
        source.toString().getBytes(StandardCharsets.UTF_8));
    // Freeze all heads and policies before scoring new96; no test-driven arm selection.
    List<Case> prior = RecallAwareSupportExperiment.all(docs, examples),
        fresh = fresh(proposer, encoder),
        stress = new ArrayList<>();
    for (Case c : prior) if (c.pair.id.startsWith("subword7-")) stress.add(c);
    SupportContrastiveModel deployed = model(proposer, encoder, selected, selected.threshold),
        policyOnly = deployed.withThreshold(selected.policyOnly);
    SupportAnswerabilityModel
        oldHead =
            SubwordSupportExperiment.checkpoint(
                "TrainedRecallAwareSupport", docs, examples, SupportAnswerabilityModel.class),
        oldUnit = TrainedViaReflection.unit(docs, examples);
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
            + ",\"observed1872\":"
            + metric(prior, deployed)
            + ",\"v9_observed1872\":"
            + RecallAwareSupportExperiment.metric(prior, oldHead)
            + ",\"policy_only_observed\":"
            + metric(prior, policyOnly)
            + ",\"v7_stress\":"
            + metric(stress, deployed)
            + ",\"fresh96\":"
            + arm(fresh, selected, proposer, encoder)
            + ",\"matched_linear_fresh\":"
            + arm(fresh, one, proposer, encoder)
            + ",\"matched_nonlinear_fresh\":"
            + arm(fresh, four, proposer, encoder)
            + ",\"v9_fresh\":"
            + RecallAwareSupportExperiment.metric(fresh, oldHead)
            + ",\"matched_bce_fresh\":"
            + arm(fresh, bceArm, proposer, encoder)
            + ",\"matched_ranking_fresh\":"
            + arm(fresh, rankingArm, proposer, encoder)
            + ",\"v9_selected_pairs\":"
            + priorPairs(fresh, oldHead)
            + ",\"v9_unit_pairs\":"
            + priorPairs(fresh, oldUnit)
            + ",\"languages\":"
            + languages
            + ",\"production_eligible\":false,\"independent_human_labels\":false,\"physical_device_tested\":false}";
    Files.write(out.resolve("report.json"), report.getBytes(StandardCharsets.UTF_8));
    List<Case> all = new ArrayList<>(prior);
    all.addAll(fresh);
    SupportContrastiveModel a = model(proposer, encoder, one, one.threshold),
        b = model(proposer, encoder, four, four.threshold),
        cBce = model(proposer, encoder, bceArm, bceArm.threshold),
        cRank = model(proposer, encoder, rankingArm, rankingArm.threshold);
    StringBuilder expected = new StringBuilder(),
        arms = new StringBuilder(),
        controls = new StringBuilder();
    for (Case c : all) {
      expected.append(probe(c.pair.id, deployed.search(c.query, 10))).append('\n');
      arms.append(
              armsProbe(
                  c.pair.id,
                  a.search(c.query, 10),
                  b.search(c.query, 10),
                  cBce.search(c.query, 10),
                  cRank.search(c.query, 10)))
          .append('\n');
      controls.append(probe(c.pair.id, policyOnly.search(c.query, 10))).append('\n');
    }
    Files.write(out.resolve("expected.tsv"), expected.toString().getBytes(StandardCharsets.UTF_8));
    Files.write(out.resolve("arm-expected.tsv"), arms.toString().getBytes(StandardCharsets.UTF_8));
    Files.write(
        out.resolve("policy-expected.tsv"), controls.toString().getBytes(StandardCharsets.UTF_8));
    System.out.println(report);
  }
}
