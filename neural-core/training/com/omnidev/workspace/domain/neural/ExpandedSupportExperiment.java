package com.omnidev.workspace.domain.neural;

import static com.omnidev.workspace.domain.neural.HybridSupportExperiment.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** A matched old-only/expanded-data comparison; frozen v3 representation and trainer unchanged. */
public final class ExpandedSupportExperiment {
  static List<Query> load(String split, HybridSupportRetriever index) throws Exception {
    List<Query> out = new ArrayList<>();
    List<String> lines =
        Files.readAllLines(
            Paths.get("neural-core/fixtures/support-data-v4/queries.tsv"), StandardCharsets.UTF_8);
    if (!lines.get(0).equals("id\tgroup\tsplit\tlanguage\tprimary\tsecondary\tquery"))
      throw new IllegalArgumentException("Expanded scenario schema");
    for (String line : lines.subList(1, lines.size())) {
      String[] r = line.split("\t", -1);
      if (r.length != 7) throw new IllegalArgumentException("Expanded scenario columns");
      if (r[2].equals(split))
        out.add(new Query(r[0], r[2], r[3], r[6], ids(r[4]), ids(r[5]), index));
    }
    if (out.isEmpty()) throw new IllegalArgumentException("Empty expanded partition");
    return out;
  }

  static List<Query> observed(HybridSupportRetriever index) throws Exception {
    List<Query> out = new ArrayList<>();
    for (String split : new String[] {"train", "development", "calibration", "test"})
      out.addAll(old(split, index));
    List<String> v2 =
        Files.readAllLines(
            Paths.get("neural-core/fixtures/product-support-v2/fresh.tsv"), StandardCharsets.UTF_8);
    for (String line : v2.subList(1, v2.size())) {
      String[] r = line.split("\t", -1);
      out.add(new Query(r[0], "observed", r[3], r[5], ids(r[4]), Collections.emptySet(), index));
    }
    out.addAll(fresh(index));
    if (out.size() != 459) throw new IllegalArgumentException("Incomplete observed probes");
    return out;
  }

  static final class Selection {
    final Config selected;
    final Policy policy;
    final String json;

    Selection(Config c, Policy p, String j) {
      selected = c;
      policy = p;
      json = j;
    }
  }

  static Selection select(List<Query> train, List<Query> dev, List<Query> cal) {
    for (Query q : cal)
      if (!q.split.equals("calibration"))
        throw new IllegalArgumentException("Foreign calibration partition");
    List<Config> choices = new ArrayList<>();
    for (HybridSupportRetriever.Mode m :
        new HybridSupportRetriever.Mode[] {
          HybridSupportRetriever.Mode.LEGACY,
          HybridSupportRetriever.Mode.TITLE,
          HybridSupportRetriever.Mode.RRF,
          HybridSupportRetriever.Mode.UNIFORM
        }) choices.add(new Config(m, null, 0, 0, 0, 0));
    for (double rate : new double[] {.05, .15})
      for (long seed : new long[] {42, 97}) choices.add(fit(train, dev, rate, seed));
    Config best = null;
    StringBuilder list = new StringBuilder("[");
    for (Config c : choices) {
      if (list.length() > 1) list.append(',');
      list.append(configJson(c, dev));
      if (floor(dev, c) && better(c, best, dev)) best = c;
    }
    if (best == null) throw new IllegalStateException("Missing eligible expanded control");
    Policy p = calibrate(cal, best);
    String json =
        "{\"train_queries\":"
            + train.size()
            + ",\"pairwise_eligible_queries\":"
            + eligibleCount(train)
            + ",\"selected\":"
            + configJson(best, dev)
            + ",\"minimum\":"
            + p.minimum
            + ",\"margin\":"
            + p.margin
            + ",\"coverage\":"
            + p.coverage
            + ",\"candidates\":"
            + list.append(']')
            + "}";
    return new Selection(best, p, json);
  }

  static int eligibleCount(List<Query> queries) {
    int n = 0;
    for (Query q : queries) {
      boolean positive = false, negative = false;
      for (HybridSupportRetriever.Row r : q.rows) {
        if (q.primary.contains(r.topic.id)) positive = true;
        else negative = true;
      }
      if (positive && negative) n++;
    }
    return n;
  }

  static HybridSupportRetriever frozen(List<ProductSupportModel.Article> docs) {
    try {
      return (HybridSupportRetriever)
          Class.forName("com.omnidev.workspace.domain.neural.TrainedHybridSupport")
              .getMethod("createCandidate", List.class)
              .invoke(null, docs);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Frozen hybrid checkpoint missing", e);
    }
  }

  static Metrics measured(List<Query> rows, HybridSupportRetriever candidate) {
    Metrics m = new Metrics();
    for (Query q : rows) {
      HybridSupportRetriever.Result r = candidate.search(q.text, 10);
      List<String> ranking = new ArrayList<>();
      List<HybridSupportRetriever.Row> sorted = new ArrayList<>(candidate.rows(q.text));
      sorted.sort(
          Comparator.comparingDouble((HybridSupportRetriever.Row row) -> candidate.score(row))
              .reversed()
              .thenComparing(row -> row.topic.id));
      for (HybridSupportRetriever.Row row : sorted) ranking.add(row.topic.id);
      observe(m, q, ranking, r.accepted);
    }
    return m;
  }

  static String compare(
      List<Query> rows,
      Selection expanded,
      Selection matched,
      ProductSupportModel lexical,
      ProductSupportModel retained,
      HybridSupportRetriever frozen) {
    return "{\"lexical\":"
        + previousMetrics(rows, lexical).json()
        + ",\"retained_default\":"
        + previousMetrics(rows, retained).json()
        + ",\"frozen_v3\":"
        + measured(rows, frozen).json()
        + ",\"matched_old_only\":"
        + metrics(rows, matched.selected, matched.policy).json()
        + ",\"expanded\":"
        + metrics(rows, expanded.selected, expanded.policy).json()
        + "}";
  }

  public static void main(String[] args) throws Exception {
    Path out = Paths.get(args[0]);
    Files.createDirectories(out);
    List<ProductSupportModel.Article> docs =
        ProductSupportExperiment.loadArticles(Paths.get("build/product-support/documents.tsv"));
    HybridSupportRetriever index =
        new HybridSupportRetriever(docs, HybridSupportRetriever.Mode.LEGACY, null, 0, 0, 0, null);
    List<Query> original = old("train", index),
        train = new ArrayList<>(original),
        dev = load("development", index),
        cal = load("calibration", index);
    train.addAll(load("train", index));
    Selection matched = select(original, dev, cal), expanded = select(train, dev, cal);
    String selected =
        "{\"matched_old_only\":" + matched.json + ",\"expanded\":" + expanded.json + "}";
    // Persist the selected source and policies before reading or scoring fresh questions.
    Files.write(out.resolve("selection.json"), selected.getBytes(StandardCharsets.UTF_8));
    String source =
        source(expanded.selected, expanded.policy, index.corpusFingerprint())
            .replace("TrainedHybridSupport", "TrainedExpandedSupport");
    Files.write(
        out.resolve("TrainedExpandedSupport.java"), source.getBytes(StandardCharsets.UTF_8));
    List<Query> test = load("fresh", index), regression = observed(index);
    ProductSupportModel lexical = previous(docs, "TrainedProductSupport", "baseline"),
        retained = previous(docs, "TrainedProductSupport", "create");
    HybridSupportRetriever old = frozen(docs),
        candidate = model(docs, expanded.selected, expanded.policy, index.corpusFingerprint());
    StringBuilder languages = new StringBuilder("{");
    for (String lang : new String[] {"en", "ar", "mixed"}) {
      List<Query> subset = new ArrayList<>();
      for (Query q : test) if (q.language.equals(lang)) subset.add(q);
      if (languages.length() > 1) languages.append(',');
      languages
          .append('"')
          .append(lang)
          .append("\":")
          .append(compare(subset, expanded, matched, lexical, retained, old));
    }
    String report =
        "{\"selection\":"
            + selected
            + ",\"fresh_authored\":"
            + compare(test, expanded, matched, lexical, retained, old)
            + ",\"languages\":"
            + languages.append('}')
            + ",\"observed_all459\":"
            + compare(regression, expanded, matched, lexical, retained, old)
            + ",\"production_eligible\":false,\"independent_human_labels\":false,\"semantic_ood\":false}";
    Files.write(out.resolve("report.json"), report.getBytes(StandardCharsets.UTF_8));
    List<Query> all = new ArrayList<>(regression);
    for (String split : new String[] {"train", "development", "calibration", "fresh"})
      all.addAll(load(split, index));
    List<String> probes = new ArrayList<>();
    for (Query q : all) probes.add(probe(q.id, candidate.search(q.text, 10)));
    Files.write(out.resolve("expected.tsv"), probes, StandardCharsets.UTF_8);
    System.out.println(
        "Expanded training: "
            + train.size()
            + " queries; "
            + eligibleCount(train)
            + " pairwise-eligible; "
            + all.size()
            + " checkpoint probes");
    System.out.println(report);
  }
}
