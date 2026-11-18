package com.omnidev.workspace.domain.neural;

import static com.omnidev.workspace.domain.neural.AnswerabilityTrainer.*;
import static com.omnidev.workspace.domain.neural.HybridSupportExperiment.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Matched original/paired training; preservation-constrained calibration and a strict proposal
 * veto.
 */
public final class AnswerabilitySupportExperiment {
  static final class Case {
    final Row pair;
    final String query;
    final Set<String> primary;
    final SupportSubwordModel.Result proposal;
    final Row pipeline;

    Case(
        Row pair,
        String query,
        Set<String> primary,
        SupportSubwordModel proposer,
        SupportPairEncoder encoder) {
      this.pair = pair;
      this.query = query;
      this.primary = primary;
      proposal = proposer.search(query, 10);
      boolean correct =
          !proposal.matches.isEmpty() && primary.contains(proposal.matches.get(0).topic.id);
      float[] x =
          proposal.matches.isEmpty()
              ? new float[1024]
              : encoder.encode(query, proposal.matches.get(0).topic.id);
      pipeline = new Row(pair.id, pair.group, pair.language, pair.split, correct ? 1 : 0, x);
    }
  }

  static final class Candidate {
    final Fit fit;
    final String arm;
    final double temperature, threshold;

    Candidate(Fit fit, String arm, List<Case> temperature, List<Case> policy) {
      this.fit = fit;
      this.arm = arm;
      this.temperature = calibrateTemperature(temperature, fit.model);
      threshold = calibrateThreshold(policy, fit.model, this.temperature);
    }

    String json() {
      return "{\"arm\":\""
          + arm
          + "\",\"fit\":"
          + fit.json()
          + ",\"temperature\":"
          + temperature
          + ",\"threshold\":"
          + threshold
          + "}";
    }
  }

  static final class Stats {
    int count, correct, wrong, baselineCorrect, baselineWrong, lost, unsupported, refused, newWrong;

    String json() {
      return "{\"queries\":"
          + count
          + ",\"accepted_correct\":"
          + correct
          + ",\"accepted_wrong\":"
          + wrong
          + ",\"proposal_correct\":"
          + baselineCorrect
          + ",\"proposal_wrong\":"
          + baselineWrong
          + ",\"lost_proposal_correct\":"
          + lost
          + ",\"new_wrong\":"
          + newWrong
          + ",\"unsupported\":"
          + unsupported
          + ",\"unsupported_refused\":"
          + refused
          + "}";
    }
  }

  static List<Row> rows(List<Case> cases) {
    List<Row> out = new ArrayList<>();
    for (Case c : cases) out.add(c.pair);
    return out;
  }

  static List<Case> original(
      List<Query> queries,
      String role,
      boolean exclude,
      SupportQueryMemory memory,
      SupportSubwordModel proposer,
      SupportPairEncoder encoder)
      throws Exception {
    Map<String, String> groups = sourceGroups();
    if (role.equals("train") != exclude)
      throw new IllegalArgumentException("Pair TRAIN extraction must exclude its source group");
    List<Case> out = new ArrayList<>();
    for (Query q : queries) {
      String group = groups.get(q.id);
      if (group == null
          || role.equals("development") && !q.split.equals("development")
          || (role.equals("temperature") || role.equals("policy"))
              && !q.split.equals("calibration"))
        throw new IllegalArgumentException("Foreign original pair role/source");
      if (exclude && (!q.split.equals("train") || group == null))
        throw new IllegalArgumentException("Unattributed pair TRAIN query");
      SupportReliabilityModel.Frame frame =
          new SupportReliabilityModel.Frame(memory, q.text, exclude ? group : null);
      String topic = frame.hits.isEmpty() ? null : frame.hits.get(0).topic.id;
      int label = topic != null && q.primary.contains(topic) ? 1 : 0;
      Row row =
          new Row(
              q.id,
              group,
              q.language,
              role,
              label,
              topic == null ? new float[1024] : encoder.encode(q.text, topic));
      out.add(new Case(row, q.text, q.primary, proposer, encoder));
    }
    return out;
  }

  static Map<String, String> sourceGroups() throws Exception {
    Map<String, String> out = new HashMap<>();
    for (String name :
        new String[] {
          "product-support/queries.tsv",
          "product-support-v2/fresh.tsv",
          "hybrid-support-v3/fresh.tsv",
          "support-data-v4/queries.tsv",
          "query-memory-v5/fresh.tsv",
          "support-reliability-v6/fresh.tsv",
          "support-subword-v7/fresh.tsv"
        }) {
      String prefix =
          name.equals("product-support/queries.tsv")
              ? "old:"
              : name.equals("support-data-v4/queries.tsv") ? "new:" : name + ":";
      List<String> lines =
          Files.readAllLines(Paths.get("neural-core/fixtures", name), StandardCharsets.UTF_8);
      for (String line : lines.subList(1, lines.size())) {
        String[] r = line.split("\t", -1);
        if (out.put(r[0], prefix + r[1]) != null)
          throw new IllegalArgumentException("Duplicate original pair ID");
      }
    }
    return out;
  }

  static List<Case> contrasts(
      String split, SupportSubwordModel proposer, SupportPairEncoder encoder) throws Exception {
    Path path =
        Paths.get(
            "neural-core/fixtures/support-answerability-v8",
            split.equals("followup")
                ? "followup.tsv"
                : split.equals("fresh") ? "fresh.tsv" : "contrast.tsv");
    List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
    if (!lines.get(0).equals("id\tgroup\tsplit\tlanguage\ttopic\tlabel\tquery"))
      throw new IllegalArgumentException("Pair corpus schema");
    List<Case> out = new ArrayList<>();
    for (String line : lines.subList(1, lines.size())) {
      String[] r = line.split("\t", -1);
      if (r.length != 7) throw new IllegalArgumentException("Pair corpus columns");
      if (!r[2].equals(split)) continue;
      int label = Integer.parseInt(r[5]);
      Set<String> primary = label == 1 ? Collections.singleton(r[4]) : Collections.emptySet();
      out.add(
          new Case(
              new Row(r[0], r[1], r[3], split, label, encoder.encode(r[6], r[4])),
              r[6],
              primary,
              proposer,
              encoder));
    }
    return out;
  }

  static List<Case> legacyCalibration(
      boolean temperature,
      HybridSupportRetriever base,
      SupportQueryMemory memory,
      SupportSubwordModel proposer,
      SupportPairEncoder encoder)
      throws Exception {
    List<SupportReliabilityExperiment.Datum> data =
        SupportReliabilityExperiment.data(
            ExpandedSupportExperiment.load("calibration", base),
            memory,
            Collections.emptyMap(),
            false);
    List<Query> labels = new ArrayList<>();
    for (SupportReliabilityExperiment.Datum d :
        SupportReliabilityExperiment.calibrationPart(data, temperature)) labels.add(d.query);
    return original(
        labels, temperature ? "temperature" : "policy", false, memory, proposer, encoder);
  }

  static double calibrateTemperature(List<Case> cases, Model model) {
    List<Row> rows = rows(cases);
    require(rows, "temperature");
    if (rows.size() != 66) throw new IllegalArgumentException("Pair temperature count");
    double selected = 1, best = Double.POSITIVE_INFINITY;
    for (double t : new double[] {.5, .75, 1, 1.5, 2, 3}) {
      double nll = nll(rows, model, t);
      if (nll < best - 1e-12) {
        best = nll;
        selected = t;
      }
    }
    return selected;
  }

  static Stats measured(List<Case> cases, Model model, double temperature, double threshold) {
    Stats s = new Stats();
    for (Case c : cases) {
      boolean before = c.proposal.accepted, correct = c.pipeline.label == 1;
      double p = before ? model.probability(c.pipeline, temperature) : 0;
      boolean accepted = before && p >= threshold;
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
      if (c.primary.isEmpty()) {
        s.unsupported++;
        if (!accepted) s.refused++;
      }
      if (accepted && !before) s.newWrong++;
    }
    return s;
  }

  static double calibrateThreshold(List<Case> cases, Model model, double temperature) {
    require(rows(cases), "policy");
    if (cases.size() != 75) throw new IllegalArgumentException("Pair policy count");
    int bestWrong = Integer.MAX_VALUE;
    double threshold = 0;
    for (int i = 0; i <= 40; i++) {
      double t = i / 40.0;
      Stats s = measured(cases, model, temperature, t);
      if (s.lost == 0 && s.wrong < bestWrong) {
        bestWrong = s.wrong;
        threshold = t;
      }
    }
    return threshold;
  }

  static boolean better(Fit a, String arm, Fit b, String oldArm) {
    return b == null
        || a.nll < b.nll - 1e-8
        || Math.abs(a.nll - b.nll) <= 1e-8
            && (!arm.equals(oldArm) ? arm.equals("original") : a.seed < b.seed);
  }

  static String paired(List<Case> cases, Candidate c) {
    double loss = 0, brier = 0;
    int correct = 0, yes = 0;
    for (Case row : cases) {
      double p = c.fit.model.probability(row.pair, c.temperature);
      loss += bce(p, row.pair.label);
      brier += (p - row.pair.label) * (p - row.pair.label);
      if ((p >= .5) == (row.pair.label == 1)) correct++;
      yes += row.pair.label;
    }
    return "{\"pairs\":"
        + cases.size()
        + ",\"answerable\":"
        + yes
        + ",\"nll\":"
        + loss / cases.size()
        + ",\"brier\":"
        + brier / cases.size()
        + ",\"correct_at_half\":"
        + correct
        + "}";
  }

  static String compare(List<Case> cases, Candidate c) {
    return "{\"pipeline\":"
        + measured(cases, c.fit.model, c.temperature, c.threshold).json()
        + ",\"paired_scores\":"
        + paired(cases, c)
        + "}";
  }

  static String factory(String method, String head, Candidate c, String binding) {
    return "public static SupportAnswerabilityModel "
        + method
        + "(List<ProductSupportModel.Article>d,List<SupportQueryMemory.Exemplar>e){return new"
        + " SupportAnswerabilityModel(TrainedSubwordSupport.createCandidate(d,e),new"
        + " SupportPairEncoder(d),new TinyNeuralRuntime(new TinyNeuralRuntime.Dense(1024,1,"
        + head
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

  static String probe(String id, SupportAnswerabilityModel.Result result) {
    StringBuilder out =
        new StringBuilder(
            id
                + "\t"
                + result.accepted
                + "\t"
                + result.proposalAccepted
                + "\t"
                + result.scored
                + "\t"
                + String.format(Locale.ROOT, "%.9f", result.answerability));
    for (SupportQueryMemory.Hit h : result.matches)
      out.append('\t')
          .append(h.topic.id)
          .append(':')
          .append(String.format(Locale.ROOT, "%.9f", h.score))
          .append(':')
          .append(String.format(Locale.ROOT, "%.9f", h.coverage));
    return out.toString();
  }

  static SupportAnswerabilityModel model(
      SupportSubwordModel proposer, SupportPairEncoder encoder, Candidate c) {
    return new SupportAnswerabilityModel(
        proposer,
        encoder,
        c.fit.model.runtime,
        c.temperature,
        c.threshold,
        SupportAnswerabilityModel.binding(proposer, encoder),
        SupportAnswerabilityModel.VERSION);
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
    List<Case> original = original(trainLabels, "train", true, memory, proposer, encoder),
        contrast = new ArrayList<>(original);
    contrast.addAll(contrasts("train", proposer, encoder));
    List<Case> dev =
        original(
            ExpandedSupportExperiment.load("development", base),
            "development",
            false,
            memory,
            proposer,
            encoder);
    dev.addAll(contrasts("development", proposer, encoder));
    List<Case> temperature = legacyCalibration(true, base, memory, proposer, encoder);
    temperature.addAll(contrasts("temperature", proposer, encoder));
    List<Case> policy = legacyCalibration(false, base, memory, proposer, encoder);
    policy.addAll(contrasts("policy", proposer, encoder));
    if (original.size() != 411
        || contrast.size() != 507
        || dev.size() != 135
        || temperature.size() != 66
        || policy.size() != 75) throw new AssertionError("Incomplete answerability split");
    Fit chosen = null, oldBest = null, pairBest = null;
    String chosenArm = null;
    StringBuilder fits = new StringBuilder("[");
    for (String arm : new String[] {"original", "contrast"})
      for (double l2 : new double[] {.0001, .001})
        for (long seed : new long[] {42, 97}) {
          Fit fit = fit(rows(arm.equals("original") ? original : contrast), rows(dev), seed, l2);
          if (fits.length() > 1) fits.append(',');
          fits.append("{\"arm\":\"")
              .append(arm)
              .append("\",\"fit\":")
              .append(fit.json())
              .append('}');
          if (better(fit, arm, chosen, chosenArm)) {
            chosen = fit;
            chosenArm = arm;
          }
          if (arm.equals("original") && better(fit, arm, oldBest, arm)) oldBest = fit;
          if (arm.equals("contrast") && better(fit, arm, pairBest, arm)) pairBest = fit;
        }
    Candidate selected = new Candidate(chosen, chosenArm, temperature, policy),
        oldOnly = new Candidate(oldBest, "original", temperature, policy),
        paired = new Candidate(pairBest, "contrast", temperature, policy);
    String selection =
        "{\"selected\":"
            + selected.json()
            + ",\"matched_original\":"
            + oldOnly.json()
            + ",\"matched_contrast\":"
            + paired.json()
            + ",\"candidates\":"
            + fits.append(']')
            + "}";
    Files.write(out.resolve("selection.json"), selection.getBytes(StandardCharsets.UTF_8));
    String binding = SupportAnswerabilityModel.binding(proposer, encoder);
    StringBuilder source =
        new StringBuilder(
            "package com.omnidev.workspace.domain.neural;\n"
                + "import java.util.List;\n"
                + "public final class TrainedAnswerabilitySupport{\n");
    source
        .append(factory("createCandidate", "selectedWeights", selected, binding))
        .append(factory("createMatchedOriginal", "originalWeights", oldOnly, binding))
        .append(factory("createMatchedContrast", "contrastWeights", paired, binding));
    OfflineDecisionTrainer.Model.appendArrayFactory(
        source, "selectedWeights", Arrays.copyOf(selected.fit.model.weights, 1024));
    OfflineDecisionTrainer.Model.appendArrayFactory(
        source, "originalWeights", Arrays.copyOf(oldOnly.fit.model.weights, 1024));
    OfflineDecisionTrainer.Model.appendArrayFactory(
        source, "contrastWeights", Arrays.copyOf(paired.fit.model.weights, 1024));
    source.append(
        "public static ProductSupportModel"
            + " retainedDefault(List<ProductSupportModel.Article>d){return"
            + " TrainedProductSupport.create(d);}\n"
            + "private TrainedAnswerabilitySupport(){}\n"
            + "}\n");
    Files.write(
        out.resolve("TrainedAnswerabilitySupport.java"),
        source.toString().getBytes(StandardCharsets.UTF_8));
    // Model and decision policy are exported before fresh pairs are scored.
    List<Query> observed = SubwordSupportExperiment.oldQueries(base);
    observed.addAll(SubwordSupportExperiment.fresh(base));
    List<Case> regressions = original(observed, "observed", false, memory, proposer, encoder),
        fresh = contrasts("fresh", proposer, encoder);
    List<Case> v7Stress = new ArrayList<>();
    for (Case row : regressions) if (row.pair.id.startsWith("subword7-")) v7Stress.add(row);
    StringBuilder languages = new StringBuilder("{");
    for (String language : new String[] {"en", "ar", "mixed"}) {
      List<Case> part = new ArrayList<>();
      for (Case row : fresh) if (row.pair.language.equals(language)) part.add(row);
      if (languages.length() > 1) languages.append(',');
      languages.append('"').append(language).append("\":").append(compare(part, selected));
    }
    String report =
        "{\"selection\":"
            + selection
            + ",\"train_original\":411,\"train_contrast\":507,\"development\":"
            + compare(dev, selected)
            + ",\"temperature\":"
            + compare(temperature, selected)
            + ",\"policy\":"
            + compare(policy, selected)
            + ",\"old1488\":"
            + compare(regressions, selected)
            + ",\"v7_stress\":"
            + compare(v7Stress, selected)
            + ",\"fresh72\":"
            + compare(fresh, selected)
            + ",\"matched_original_fresh\":"
            + compare(fresh, oldOnly)
            + ",\"matched_contrast_fresh\":"
            + compare(fresh, paired)
            + ",\"languages\":"
            + languages.append('}')
            + ",\"production_eligible\":false,\"independent_human_labels\":false}";
    Files.write(out.resolve("report.json"), report.getBytes(StandardCharsets.UTF_8));
    List<Case> all = new ArrayList<>(regressions);
    for (String split : new String[] {"train", "development", "temperature", "policy"})
      all.addAll(contrasts(split, proposer, encoder));
    all.addAll(fresh);
    SupportAnswerabilityModel model = model(proposer, encoder, selected),
        oldModel = model(proposer, encoder, oldOnly),
        pairModel = model(proposer, encoder, paired);
    StringBuilder expected = new StringBuilder(), arms = new StringBuilder();
    for (Case row : all) {
      expected.append(probe(row.pair.id, model.search(row.query, 10))).append('\n');
      arms.append(
              armProbe(
                  row.pair.id, oldModel.search(row.query, 10), pairModel.search(row.query, 10)))
          .append('\n');
    }
    Files.write(out.resolve("expected.tsv"), expected.toString().getBytes(StandardCharsets.UTF_8));
    Files.write(out.resolve("arm-expected.tsv"), arms.toString().getBytes(StandardCharsets.UTF_8));
    System.out.println(report);
  }

  static String armProbe(
      String id,
      SupportAnswerabilityModel.Result original,
      SupportAnswerabilityModel.Result contrast) {
    StringBuilder out = new StringBuilder(id);
    for (SupportAnswerabilityModel.Result r :
        new SupportAnswerabilityModel.Result[] {original, contrast})
      out.append('\t')
          .append(r.accepted)
          .append('\t')
          .append(r.scored)
          .append('\t')
          .append(String.format(Locale.ROOT, "%.9f", r.answerability));
    return out.toString();
  }
}
