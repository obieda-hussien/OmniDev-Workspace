package com.omnidev.workspace.domain.neural;

import static com.omnidev.workspace.domain.neural.HybridSupportExperiment.*;
import static com.omnidev.workspace.domain.neural.SupportReliabilityExperiment.*;

import java.nio.file.*;
import java.util.*;

public final class SupportReliabilityTrainingSmoke {
  interface Action {
    void run() throws Exception;
  }

  static void rejects(Action action) throws Exception {
    try {
      action.run();
    } catch (IllegalArgumentException expected) {
      return;
    }
    throw new AssertionError("Selector split/identity barrier did not reject input");
  }

  public static void main(String[] args) throws Exception {
    List<ProductSupportModel.Article> docs =
        ProductSupportExperiment.loadArticles(Paths.get("build/product-support/documents.tsv"));
    HybridSupportRetriever base = QueryMemoryExperiment.base(docs);
    SupportQueryMemory memory =
        TrainedQueryMemory.createCandidate(
            docs, QueryMemoryExperiment.examples(Paths.get("build/query-memory-v5/exemplars.tsv")));
    List<Query> labels = old("train", base);
    labels.addAll(ExpandedSupportExperiment.load("train", base));
    Map<String, String> groups = QueryMemoryExperiment.trainingGroups();
    List<Datum> train = data(labels, memory, groups, true),
        dev = data(ExpandedSupportExperiment.load("development", base), memory, groups, false),
        cal = data(ExpandedSupportExperiment.load("calibration", base), memory, groups, false);
    int coordinates = 0;
    for (int width : new int[] {0, 8}) {
      ProductSupportExperiment.Network network = new ProductSupportExperiment.Network(width, 97);
      int checked = 0;
      for (Datum d : train) {
        if (d.frame.hits.isEmpty()) continue;
        double[] gradient = new double[network.w.length];
        objective(d, network, gradient, .01);
        for (int i = 0; i < gradient.length; i++) {
          double before = network.w[i], h = 1e-5;
          network.w[i] = before + h;
          double plus = objective(d, network, new double[gradient.length], .01);
          network.w[i] = before - h;
          double minus = objective(d, network, new double[gradient.length], .01);
          network.w[i] = before;
          if (Math.abs(gradient[i] - (plus - minus) / (2 * h)) > 1e-7)
            throw new AssertionError("Selector gradient mismatch");
          coordinates++;
        }
        if (++checked == 2) break;
      }
    }
    List<Datum> a = calibrationPart(cal, true), b = calibrationPart(cal, false);
    Set<String> ids = new HashSet<>(), ga = new HashSet<>(), gb = new HashSet<>();
    Map<String, String> canonical = new HashMap<>();
    List<String> lines =
        Files.readAllLines(Paths.get("neural-core/fixtures/support-data-v4/queries.tsv"));
    for (String line : lines.subList(1, lines.size())) {
      String[] r = line.split("\t", -1);
      canonical.put(r[0], r[1]);
    }
    for (Datum d : a) {
      ids.add(d.query.id);
      ga.add(canonical.get(d.query.id));
    }
    for (Datum d : b) {
      if (!ids.add(d.query.id)) throw new AssertionError("Calibration ID overlap");
      gb.add(canonical.get(d.query.id));
    }
    ga.retainAll(gb);
    if (!ga.isEmpty()
        || a.size() != 48
        || b.size() != 51
        || coordinates != 252
        || train.size() != 411) throw new AssertionError("Selector calibration or gradient count");
    rejects(() -> fit(cal, dev, 0, 0, 42));
    rejects(() -> fit(train, cal, 0, 0, 42));
    rejects(() -> temperature(dev, new ProductSupportExperiment.Network(0, 42).runtime()));
    rejects(() -> calibrate(dev, new ProductSupportExperiment.Network(0, 42).runtime(), 1));
    rejects(() -> temperature(b, new ProductSupportExperiment.Network(0, 42).runtime()));
    rejects(() -> calibrate(a, new ProductSupportExperiment.Network(0, 42).runtime(), 1));
    Map<String, String> wrong = new HashMap<>(groups);
    wrong.put(labels.get(0).id, "fake-group");
    rejects(() -> data(labels, memory, wrong, true));
    List<Datum> duplicate = new ArrayList<>(cal);
    duplicate.set(1, duplicate.get(0));
    rejects(() -> calibrationPart(duplicate, true));
    Fit first = fit(train, dev, 0, 0, 42), again = fit(train, dev, 0, 0, 42);
    if (!Arrays.equals(first.n.w, again.n.w)
        || first.epoch != again.epoch
        || Math.abs(first.nll - macroNll(dev, first.n.runtime(), 1)) > 1e-12)
      throw new AssertionError("Nonreproducible selector snapshot");
    if (first.epoch == 0) throw new AssertionError("Actual selector control did not learn");
    System.out.println(
        "Selector: 252 actual objective gradient coordinates; deterministic learned snapshot; TRAIN"
            + " exclusions and disjoint CAL48/CAL51 barriers passed");
  }
}
