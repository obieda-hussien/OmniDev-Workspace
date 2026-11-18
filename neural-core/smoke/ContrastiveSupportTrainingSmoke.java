package com.omnidev.workspace.domain.neural;

import static com.omnidev.workspace.domain.neural.AnswerabilitySupportExperiment.*;
import static com.omnidev.workspace.domain.neural.ContrastiveSupportTrainer.*;

import com.omnidev.workspace.domain.neural.AnswerabilityTrainer.Row;
import java.nio.file.*;
import java.util.*;

public final class ContrastiveSupportTrainingSmoke {
  static int numeric(Row row, Pair pair, int hidden) throws Exception {
    Random rng = new Random(97);
    double[] w = new double[hidden == 0 ? 1025 : 8209], g = new double[w.length];
    for (int i = 0; i < w.length; i++) w[i] = rng.nextGaussian() * .08;
    if (pair == null) objective(row, w, hidden, g);
    else pairObjective(pair, w, hidden, g);
    Set<Integer> coords = new TreeSet<>();
    coords.add(w.length - 1);
    if (hidden == 8) for (int i = 8192; i < 8209; i++) coords.add(i);
    for (int i : row.nonzero) {
      if (coords.size() >= 64) break;
      coords.add(i);
    }
    while (coords.size() < 128) coords.add(rng.nextInt(w.length));
    for (int i : coords) {
      double before = w[i], h = 1e-5;
      w[i] = before + h;
      double plus =
          pair == null
              ? objective(row, w, hidden, new double[w.length])
              : pairObjective(pair, w, hidden, new double[w.length]);
      w[i] = before - h;
      double minus =
          pair == null
              ? objective(row, w, hidden, new double[w.length])
              : pairObjective(pair, w, hidden, new double[w.length]);
      w[i] = before;
      if (Math.abs(g[i] - (plus - minus) / (2 * h)) > 1e-7)
        throw new AssertionError("Contrast gradient " + hidden + ":" + i);
    }
    Model snapshot = new Model(w, hidden);
    double actual = snapshot.runtime.infer(row.x)[0];
    double unrounded = forward(row, toDouble(snapshot.weights), hidden, new double[8]);
    if (Math.abs(actual - unrounded) > 2e-6)
      throw new AssertionError("Dense FP32 topology mismatch");
    if (pair != null && g[w.length - 1] != 0)
      throw new AssertionError("Pair loss must cancel output bias");
    return coords.size();
  }

  static double[] toDouble(float[] values) {
    double[] w = new double[values.length];
    for (int i = 0; i < w.length; i++) w[i] = values[i];
    return w;
  }

  public static void main(String[] args) throws Exception {
    List<ProductSupportModel.Article> docs =
        ProductSupportExperiment.loadArticles(Paths.get("build/product-support/documents.tsv"));
    List<SupportQueryMemory.Exemplar> examples =
        QueryMemoryExperiment.examples(Paths.get("build/query-memory-v5/exemplars.tsv"));
    SupportSubwordModel proposer =
        SubwordSupportExperiment.checkpoint(
            "TrainedSubwordSupport", docs, examples, SupportSubwordModel.class);
    SupportPairEncoder encoder = new SupportPairEncoder(docs);
    List<Row> train = rows(contrasts("train", proposer, encoder)),
        dev = rows(contrasts("development", proposer, encoder));
    List<Pair> pairs = pairs(train);
    if (pairs.size() != 48) throw new AssertionError("Training pair count");
    Pair pair = pairs.get(0);
    int checked = 0;
    for (int hidden : new int[] {0, 8}) {
      checked += numeric(pair.positive, null, hidden);
      checked += numeric(pair.negative, null, hidden);
      checked += numeric(pair.positive, pair, hidden);
    }
    Fit a = fit(train, dev, 8, .25, 42), b = fit(train, dev, 8, .25, 42);
    if (!Arrays.equals(a.model.weights, b.model.weights)
        || a.epoch != b.epoch
        || Math.abs(a.nll - nll(dev, a.model, 1)) > 1e-12
        || checked != 768) throw new AssertionError("Contrast snapshot/gradient count");
    SupportReliabilityTrainingSmoke.rejects(() -> new Pair(pair.negative, pair.positive));
    Row pos = pair.positive;
    SupportReliabilityTrainingSmoke.rejects(
        () ->
            new Pair(
                new Row("foreign", pos.group, pos.language, "fresh", 1, pos.x), pair.negative));
    SupportReliabilityTrainingSmoke.rejects(() -> pairs(dev));
    List<Row> missing = new ArrayList<>(train);
    missing.remove(0);
    SupportReliabilityTrainingSmoke.rejects(() -> pairs(missing));
    List<Row> duplicate = new ArrayList<>(train);
    duplicate.add(train.get(0));
    SupportReliabilityTrainingSmoke.rejects(() -> pairs(duplicate));
    List<Row> leaking = new ArrayList<>(dev);
    leaking.set(0, new Row("overlap", pos.group, "en", "development", 1, pos.x));
    SupportReliabilityTrainingSmoke.rejects(() -> fit(train, leaking, 8, .25, 42));
    SupportReliabilityTrainingSmoke.rejects(() -> fit(train, dev, 16, .25, 42));
    SupportQueryMemory memory =
        SubwordSupportExperiment.checkpoint(
            "TrainedQueryMemory", docs, examples, SupportQueryMemory.class);
    List<Case> policy =
        legacyCalibration(false, QueryMemoryExperiment.base(docs), memory, proposer, encoder);
    policy.addAll(contrasts("policy", proposer, encoder));
    double reference = 0;
    int bestWrong = Integer.MAX_VALUE;
    for (int i = 0; i <= 40; i++) {
      double cutoff = i / 40.0;
      Stats measured = ContrastiveSupportExperiment.measured(policy, a.model, 1, cutoff);
      if (measured.lost == 0 && measured.wrong <= bestWrong) {
        reference = cutoff;
        bestWrong = measured.wrong;
      }
    }
    if (reference != ContrastiveSupportExperiment.calibrate(policy, policy, a.model, 1))
      throw new AssertionError("Cached calibration changed cutoff");
    List<Case> foreign = new ArrayList<>(policy);
    Case first = policy.get(0);
    foreign.set(
        0,
        new Case(
            new Row(
                first.pair.id,
                first.pair.group,
                first.pair.language,
                "observed",
                first.pair.label,
                first.pair.x),
            first.query,
            first.primary,
            proposer,
            encoder));
    SupportReliabilityTrainingSmoke.rejects(
        () -> ContrastiveSupportExperiment.calibrate(policy, foreign, a.model, 1));
    List<Case> duplicates = new ArrayList<>(policy);
    duplicates.set(0, policy.get(1));
    SupportReliabilityTrainingSmoke.rejects(
        () -> ContrastiveSupportExperiment.calibrate(policy, duplicates, a.model, 1));
    System.out.println(
        "Contrastive training:768 actual BCE/pairwise coordinates for linear and tanh8, all bias"
            + " roles, FP32 topology, deterministic snapshots, TRAIN-only pairing and"
            + " role/group/shape barriers passed");
  }
}
