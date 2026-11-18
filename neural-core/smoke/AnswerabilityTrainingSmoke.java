package com.omnidev.workspace.domain.neural;

import static com.omnidev.workspace.domain.neural.AnswerabilityTrainer.*;

import java.nio.file.*;
import java.util.*;

public final class AnswerabilityTrainingSmoke {
  public static void main(String[] args) throws Exception {
    List<ProductSupportModel.Article> docs =
        ProductSupportExperiment.loadArticles(Paths.get("build/product-support/documents.tsv"));
    List<SupportQueryMemory.Exemplar> examples =
        QueryMemoryExperiment.examples(Paths.get("build/query-memory-v5/exemplars.tsv"));
    SupportPairEncoder encoder = new SupportPairEncoder(docs);
    SupportSubwordModel proposer =
        SubwordSupportExperiment.checkpoint(
            "TrainedSubwordSupport", docs, examples, SupportSubwordModel.class);
    List<AnswerabilitySupportExperiment.Case>
        train = AnswerabilitySupportExperiment.contrasts("train", proposer, encoder),
        dev = AnswerabilitySupportExperiment.contrasts("development", proposer, encoder),
        temperature = AnswerabilitySupportExperiment.contrasts("temperature", proposer, encoder),
        policy = AnswerabilitySupportExperiment.contrasts("policy", proposer, encoder);
    Set<String> t = new HashSet<>(), p = new HashSet<>();
    for (AnswerabilitySupportExperiment.Case row : temperature) t.add(row.pair.group);
    for (AnswerabilitySupportExperiment.Case row : policy) p.add(row.pair.group);
    t.retainAll(p);
    if (!t.isEmpty()) throw new AssertionError("Pair calibration groups overlap");
    int checked = 0;
    Set<Integer> checkedLabels = new HashSet<>();
    for (AnswerabilitySupportExperiment.Case c : train) {
      if (checked >= 2) break;
      Row row = c.pair;
      if (!checkedLabels.add(row.label)) continue;
      double[] weights = new double[1025], gradient = new double[1025];
      Random rng = new Random(97);
      for (int i = 0; i < weights.length; i++) weights[i] = rng.nextGaussian() * .02;
      objective(row, weights, gradient, .0001);
      Set<Integer> coordinates = new TreeSet<>();
      coordinates.add(1024);
      for (int i : row.nonzero) {
        if (coordinates.size() == 64) break;
        coordinates.add(i);
      }
      while (coordinates.size() < 128) coordinates.add(rng.nextInt(1025));
      for (int i : coordinates) {
        double before = weights[i], h = 1e-5;
        weights[i] = before + h;
        double plus = objective(row, weights, new double[1025], .0001);
        weights[i] = before - h;
        double minus = objective(row, weights, new double[1025], .0001);
        weights[i] = before;
        if (Math.abs(gradient[i] - (plus - minus) / (2 * h)) > 1e-7)
          throw new AssertionError("Pair gradient mismatch");
      }
      checked++;
    }
    if (checked != 2
        || train.size() != 96
        || dev.size() != 36
        || temperature.size() != 18
        || policy.size() != 24) throw new AssertionError("Incomplete pair smoke");
    SupportReliabilityTrainingSmoke.rejects(
        () ->
            fit(
                AnswerabilitySupportExperiment.rows(policy),
                AnswerabilitySupportExperiment.rows(dev),
                42,
                .0001));
    List<Row> invalid = new ArrayList<>(AnswerabilitySupportExperiment.rows(dev));
    Row first = train.get(0).pair;
    invalid.set(0, new Row("overlap", first.group, "en", "development", first.label, first.x));
    SupportReliabilityTrainingSmoke.rejects(
        () -> fit(AnswerabilitySupportExperiment.rows(train), invalid, 42, .0001));
    Fit
        one =
            fit(
                AnswerabilitySupportExperiment.rows(train),
                AnswerabilitySupportExperiment.rows(dev),
                42,
                .0001),
        two =
            fit(
                AnswerabilitySupportExperiment.rows(train),
                AnswerabilitySupportExperiment.rows(dev),
                42,
                .0001);
    if (!Arrays.equals(one.model.weights, two.model.weights)
        || one.epoch != two.epoch
        || Math.abs(one.nll - nll(AnswerabilitySupportExperiment.rows(dev), one.model, 1)) > 1e-12)
      throw new AssertionError("Pair fit snapshot drift");
    System.out.println(
        "Answerability: 256 actual pair-objective gradient coordinates, positive/negative groups,"
            + " deterministic snapshots and split barriers passed");
  }
}
