package com.omnidev.workspace.domain.neural;

import static com.omnidev.workspace.domain.neural.AnswerabilitySupportExperiment.*;
import static com.omnidev.workspace.domain.neural.AnswerabilityTrainer.*;

import java.nio.file.*;
import java.util.*;

public final class RecallAwareTrainingSmoke {
  public static void main(String[] args) throws Exception {
    List<ProductSupportModel.Article> docs =
        ProductSupportExperiment.loadArticles(Paths.get("build/product-support/documents.tsv"));
    List<SupportQueryMemory.Exemplar> examples =
        QueryMemoryExperiment.examples(Paths.get("build/query-memory-v5/exemplars.tsv"));
    SupportPairEncoder encoder = new SupportPairEncoder(docs);
    SupportSubwordModel proposer =
        SubwordSupportExperiment.checkpoint(
            "TrainedSubwordSupport", docs, examples, SupportSubwordModel.class);
    List<Case> train = contrasts("train", proposer, encoder),
        dev = contrasts("development", proposer, encoder);
    int checked = 0;
    for (int weight : new int[] {1, 4}) {
      Set<Integer> labels = new HashSet<>();
      for (Case c : train) {
        if (!labels.add(c.pair.label)) continue;
        Row row = c.pair;
        double[] w = new double[1025], g = new double[1025];
        Random rng = new Random(42);
        for (int i = 0; i < w.length; i++) w[i] = rng.nextGaussian() * .02;
        RecallAwareTrainer.objective(row, w, g, .001, weight);
        Set<Integer> coordinates = new TreeSet<>();
        coordinates.add(1024);
        for (int i : row.nonzero) {
          if (coordinates.size() == 64) break;
          coordinates.add(i);
        }
        while (coordinates.size() < 128) coordinates.add(rng.nextInt(1025));
        for (int i : coordinates) {
          double before = w[i], h = 1e-5;
          w[i] = before + h;
          double plus = RecallAwareTrainer.objective(row, w, new double[1025], .001, weight);
          w[i] = before - h;
          double minus = RecallAwareTrainer.objective(row, w, new double[1025], .001, weight);
          w[i] = before;
          if (Math.abs(g[i] - (plus - minus) / (2 * h)) > 1e-7)
            throw new AssertionError("Weighted gradient " + weight + ":" + i);
          checked++;
        }
      }
    }
    Fit a = RecallAwareTrainer.fit(rows(train), rows(dev), 42, .0001, 4),
        b = RecallAwareTrainer.fit(rows(train), rows(dev), 42, .0001, 4);
    if (!Arrays.equals(a.model.weights, b.model.weights)
        || a.epoch != b.epoch
        || a.nll != RecallAwareTrainer.nll(rows(dev), a.model, 1))
      throw new AssertionError("Weighted snapshot nondeterministic");
    double expected = 0;
    for (String language : new String[] {"en", "ar", "mixed"}) {
      double loss = 0, importance = 0;
      for (Case c : dev)
        if (c.pair.language.equals(language)) {
          int weight = c.pair.label == 1 ? 4 : 1;
          loss += weight * bce(a.model.probability(c.pair, 1), c.pair.label);
          importance += weight;
        }
      expected += loss / importance / 3;
    }
    if (Math.abs(expected - a.nll) > 1e-12 || checked != 512)
      throw new AssertionError("DEV weighted objective mismatch");
    SupportReliabilityTrainingSmoke.rejects(
        () -> RecallAwareTrainer.fit(rows(train), rows(dev), 42, .0001, 2));
    SupportReliabilityTrainingSmoke.rejects(
        () ->
            RecallAwareTrainer.fit(
                rows(contrasts("policy", proposer, encoder)), rows(dev), 42, .0001, 4));
    List<Row> leaking = new ArrayList<>(rows(dev));
    Row first = train.get(0).pair;
    leaking.set(0, new Row("leak", first.group, "en", "development", first.label, first.x));
    SupportReliabilityTrainingSmoke.rejects(
        () -> RecallAwareTrainer.fit(rows(train), leaking, 42, .0001, 4));
    SupportQueryMemory memory =
        SubwordSupportExperiment.checkpoint(
            "TrainedQueryMemory", docs, examples, SupportQueryMemory.class);
    List<Case> policy =
        legacyCalibration(false, QueryMemoryExperiment.base(docs), memory, proposer, encoder);
    policy.addAll(contrasts("policy", proposer, encoder));
    List<Case> invalid = new ArrayList<>(policy);
    Case c = policy.get(0);
    invalid.set(
        0,
        new Case(
            new Row(c.pair.id, c.pair.group, c.pair.language, "observed", c.pair.label, c.pair.x),
            c.query,
            c.primary,
            proposer,
            encoder));
    final List<Case> foreign = invalid;
    SupportReliabilityTrainingSmoke.rejects(
        () -> RecallAwareSupportExperiment.calibrate(policy, foreign, a.model, 1));
    invalid = new ArrayList<>(policy);
    invalid.set(0, invalid.get(1));
    final List<Case> duplicates = invalid;
    SupportReliabilityTrainingSmoke.rejects(
        () -> RecallAwareSupportExperiment.calibrate(policy, duplicates, a.model, 1));
    System.out.println(
        "Recall training:512 actual weighted objective coordinates, DEV cost normalization,"
            + " deterministic fits and observed/duplicate/group/role barriers passed");
  }
}
