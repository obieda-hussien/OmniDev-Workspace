package com.omnidev.workspace.domain.neural;

import com.omnidev.workspace.domain.neural.ProductSupportExperiment.Frame;
import com.omnidev.workspace.domain.neural.ProductSupportExperiment.Policy;
import com.omnidev.workspace.domain.neural.ProductSupportExperiment.Stats;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Frozen four-fit ranking experiment; includes a lexical control and a new authored-wording
 * challenge.
 */
public final class RankedSupportExperiment {
  public static Map<String, ProductSupportModel> models(
      List<ProductSupportModel.Article> articles,
      TinyNeuralRuntime network,
      Policy policy,
      Policy control,
      String fingerprint) {
    Map<String, ProductSupportModel> out = new LinkedHashMap<>();
    out.put(
        "lexical",
        new ProductSupportModel(articles, null, control.minimum, control.margin, fingerprint));
    out.put("v1_raw", previous(articles, true));
    out.put("v1_guarded", previous(articles, false));
    out.put(
        "v2_raw",
        new ProductSupportModel(articles, network, policy.minimum, policy.margin, fingerprint));
    out.put(
        "v2_guarded",
        new ProductSupportModel(
            articles,
            network,
            policy.minimum,
            policy.margin,
            fingerprint,
            new ProductSupportModel.Guard(control.minimum, control.margin)));
    return out;
  }

  private static ProductSupportModel previous(
      List<ProductSupportModel.Article> corpus, boolean raw) {
    try {
      return (ProductSupportModel)
          Class.forName("com.omnidev.workspace.domain.neural.TrainedProductSupport")
              .getMethod(raw ? "createRaw" : "create", List.class)
              .invoke(null, corpus);
    } catch (ReflectiveOperationException invalid) {
      throw new IllegalStateException("Frozen v1 support checkpoint unavailable", invalid);
    }
  }

  static Stats measure(List<Frame> rows, ProductSupportModel model) {
    Stats stats = new Stats();
    for (Frame f : rows) {
      List<ProductSupportModel.Match> rank = model.candidates(f.query);
      ProductSupportModel.Result result = model.search(f.query, 3);
      boolean supported = !f.topic.equals("-");
      if (supported) {
        stats.supported++;
        int position = -1;
        for (int i = 0; i < rank.size(); i++)
          if (rank.get(i).article.topic.equals(f.topic)) {
            position = i;
            break;
          }
        if (position >= 0) {
          stats.found++;
          stats.reciprocal += 1.0 / (position + 1);
          if (position == 0) stats.top1++;
          if (position < 3) stats.top3++;
        }
      } else {
        stats.ood++;
        if (!result.accepted) stats.oodRefused++;
      }
      if (result.accepted) {
        if (supported && result.matches.get(0).article.topic.equals(f.topic))
          stats.acceptedCorrect++;
        else stats.acceptedWrong++;
      }
    }
    return stats;
  }

  static String evaluation(List<Frame> rows, Map<String, ProductSupportModel> models) {
    StringBuilder s = new StringBuilder("{");
    for (Map.Entry<String, ProductSupportModel> e : models.entrySet()) {
      if (s.length() > 1) s.append(',');
      s.append('"').append(e.getKey()).append("\":").append(measure(rows, e.getValue()).json());
    }
    return s.append('}').toString();
  }

  static String languages(List<Frame> rows, Map<String, ProductSupportModel> models) {
    StringBuilder s = new StringBuilder("{");
    for (String language : new String[] {"en", "ar", "mixed"}) {
      List<Frame> subset = new ArrayList<>();
      for (Frame f : rows) if (f.language.equals(language)) subset.add(f);
      if (s.length() > 1) s.append(',');
      s.append('"').append(language).append("\":").append(evaluation(subset, models));
    }
    return s.append('}').toString();
  }

  static boolean outranksControl(RankedSupportTrainer.Fit f, Stats control) {
    return f.development.top1 > control.top1
        || f.development.top1 == control.top1
            && f.development.reciprocal > control.reciprocal + 1e-12;
  }

  public static void main(String[] args) throws Exception {
    Path out = Paths.get(args[0]);
    Files.createDirectories(out);
    List<ProductSupportModel.Article> articles =
        ProductSupportExperiment.loadArticles(Paths.get("build/product-support/documents.tsv"));
    ProductSupportModel base = new ProductSupportModel(articles, null, 0, 0, null);
    Path old = Paths.get("neural-core/fixtures/product-support/queries.tsv");
    List<Frame> train = ProductSupportExperiment.frames(old, "train", base),
        dev = ProductSupportExperiment.frames(old, "development", base),
        cal = ProductSupportExperiment.frames(old, "calibration", base);
    Stats control = ProductSupportExperiment.stats(dev, null, new Policy(0, 0));
    RankedSupportTrainer.Fit selected = null;
    StringBuilder candidates = new StringBuilder("[");
    for (double anchor : new double[] {.02, .1})
      for (long seed : new long[] {42, 97}) {
        RankedSupportTrainer.Fit f = RankedSupportTrainer.fit(train, dev, anchor, seed);
        if (candidates.length() > 1) candidates.append(',');
        candidates
            .append("{\"anchor\":")
            .append(anchor)
            .append(",\"seed\":")
            .append(seed)
            .append(",\"epoch\":")
            .append(f.epoch)
            .append(",\"epochs_run\":")
            .append(f.ran)
            .append(",\"development\":")
            .append(f.development.json())
            .append(",\"nll\":")
            .append(f.loss)
            .append('}');
        if (RankedSupportTrainer.languageFloor(dev, f.network.runtime())
            && outranksControl(f, control)
            && (selected == null
                || RankedSupportTrainer.better(
                    f.development, f.loss, selected.development, selected.loss))) selected = f;
        System.err.println(
            "Ranked support anchor="
                + anchor
                + " seed="
                + seed
                + " devTop1="
                + f.development.top1
                + " /"
                + f.development.supported);
      }
    candidates.append(']');
    TinyNeuralRuntime runtime = selected == null ? null : selected.network.runtime();
    Policy baseline = ProductSupportExperiment.calibrate(cal, null),
        policy = runtime == null ? baseline : ProductSupportExperiment.calibrate(cal, runtime);
    String source =
        selected == null
            ? controlSource(baseline, base.fingerprint())
            : ProductSupportExperiment.javaSource(
                    selected.network, policy, baseline, base.fingerprint())
                .replace("TrainedProductSupport", "TrainedRankedSupport");
    source =
        source.replace(
            "public static ProductSupportModel create(",
            "public static ProductSupportModel createCandidateGuarded(");
    int closing = source.lastIndexOf('}');
    source =
        source.substring(0, closing)
            + "public static ProductSupportModel create(java.util.List<ProductSupportModel.Article>"
            + " corpus){return TrainedProductSupport.create(corpus);}\n"
            + source.substring(closing);
    Files.write(out.resolve("TrainedRankedSupport.java"), source.getBytes(StandardCharsets.UTF_8));
    String selection =
        "{\"selected\":\""
            + (selected == null ? "lexical_control" : "pairwise_linear")
            + "\",\"selected_seed\":"
            + (selected == null ? "null" : Long.toString(selected.seed))
            + ",\"selected_anchor\":"
            + (selected == null ? "null" : Double.toString(selected.anchor))
            + ",\"selected_epoch\":"
            + (selected == null ? 0 : selected.epoch)
            + ",\"parameters\":"
            + (runtime == null ? 0 : runtime.parameterCount())
            + ",\"minimum\":"
            + policy.minimum
            + ",\"margin\":"
            + policy.margin
            + ",\"control_minimum\":"
            + baseline.minimum
            + ",\"control_margin\":"
            + baseline.margin
            + ",\"candidates\":"
            + candidates
            + ",\"development_control\":"
            + control.json()
            + "}";
    Files.write(out.resolve("selection.json"), selection.getBytes(StandardCharsets.UTF_8));
    // No new challenge features/scores are created until after weights and policies have been
    // exported.
    List<Frame>
        fresh =
            ProductSupportExperiment.frames(
                Paths.get("neural-core/fixtures/product-support-v2/fresh.tsv"), "test", base),
        observed = ProductSupportExperiment.frames(old, "test", base);
    Map<String, ProductSupportModel> models =
        models(articles, runtime, policy, baseline, base.fingerprint());
    String report =
        "{\"selection\":"
            + selection
            + ",\"fresh_authored_wordings\":"
            + evaluation(fresh, models)
            + ",\"fresh_languages\":"
            + languages(fresh, models)
            + ",\"observed_v1_regression\":"
            + evaluation(observed, models)
            + ",\"production_eligible\":false,\"independent_human_labels\":false,\"semantic_ood\":false}";
    Files.write(out.resolve("report.json"), report.getBytes(StandardCharsets.UTF_8));
    List<Frame> all = new ArrayList<>();
    for (String split : new String[] {"train", "development", "calibration", "test"})
      all.addAll(ProductSupportExperiment.frames(old, split, base));
    all.addAll(fresh);
    List<String> expected = new ArrayList<>();
    for (Frame f : all)
      for (Map.Entry<String, ProductSupportModel> entry : models.entrySet())
        expected.add(probe(f.id, entry.getKey(), entry.getValue().search(f.query, 3)));
    Files.write(out.resolve("expected.tsv"), expected, StandardCharsets.UTF_8);
    System.out.println(report);
  }

  static String probe(String id, String mode, ProductSupportModel.Result result) {
    StringBuilder s = new StringBuilder(id + "\t" + mode + "\t" + result.accepted);
    for (ProductSupportModel.Match m : result.matches)
      s.append('\t')
          .append(m.article.id)
          .append(':')
          .append(m.evidence.startOffset)
          .append(':')
          .append(m.evidence.endOffset)
          .append(':')
          .append(String.format(Locale.ROOT, "%.9f", m.score));
    return s.toString();
  }

  static String controlSource(Policy p, String fingerprint) {
    return "package com.omnidev.workspace.domain.neural;\n"
        + "import java.util.List;\n"
        + "public final class TrainedRankedSupport{\n"
        + "public static TinyNeuralRuntime network(){return null;}\n"
        + "public static ProductSupportModel baseline(List<ProductSupportModel.Article>"
        + " a){return new ProductSupportModel(a,null,"
        + p.minimum
        + ","
        + p.margin
        + ",\""
        + fingerprint
        + "\");}\n"
        + "public static ProductSupportModel createRaw(List<ProductSupportModel.Article>a){return"
        + " baseline(a);}\n"
        + "public static ProductSupportModel create(List<ProductSupportModel.Article>a){return"
        + " baseline(a);}\n"
        + "private TrainedRankedSupport(){}\n"
        + "}\n";
  }
}
