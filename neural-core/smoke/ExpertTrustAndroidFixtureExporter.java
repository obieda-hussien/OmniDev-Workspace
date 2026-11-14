package com.omnidev.workspace.domain.neural;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Full-text decisions plus fixed numerical selector inputs for the exact Android test checkpoint.
 */
public final class ExpertTrustAndroidFixtureExporter {
  public static void main(String[] args) throws Exception {
    Path assets = Paths.get(args[0], "assets");
    List<String> texts = new ArrayList<>();
    List<String> old =
        Files.readAllLines(assets.resolve("prototype-probes.tsv"), StandardCharsets.UTF_8);
    for (String row : old.subList(6, old.size())) texts.add(row.split("\t", -1)[1]);
    for (PublicRefusalCorpus.Row row : ExpertTrustExperiment.fresh()) texts.add(row.text);
    if (texts.size() != 1150) throw new AssertionError("Trust Android probe count changed");
    Class<?> cp = Class.forName("TrainedExpertTrust");
    TinyNeuralRuntime network = (TinyNeuralRuntime) cp.getMethod("model").invoke(null);
    double temperature = cp.getField("TEMPERATURE").getDouble(null);
    double[] cutoffs = (double[]) cp.getMethod("cutoffs").invoke(null);
    String fingerprint = (String) cp.getField("BASE_SHA256").get(null),
        version = (String) cp.getField("FEATURE_VERSION").get(null),
        source = (String) cp.getField("SOURCE").get(null);
    UtilityExpertDecisionModel base = PrototypeSupportExperiment.utility(false);
    ExpertTrustDecisionModel model =
        new ExpertTrustDecisionModel(base, network, temperature, cutoffs, version, fingerprint);
    TinyDecisionModel risk =
        new TinyDecisionModel(network, new String[] {"keep", "use"}, temperature);
    StringBuilder out =
        new StringBuilder(
            "base_sha256\t"
                + fingerprint
                + "\nfeature_version\t"
                + version
                + "\ntemperature\t"
                + temperature
                + "\ncutoffs\t"
                + Arrays.toString(cutoffs)
                + "\nsource\t"
                + source
                + "\n"
                + "id\ttext\tfeatures\ttrust_probability\tteacher_status\tteacher_choice"
                + "\tteacher_probabilities\tutility_status\tutility_choice\tutility_probabilities"
                + "\ttrust_status\ttrust_choice\ttrust_probabilities\n");
    for (int i = 0; i < texts.size(); i++) {
      String text = texts.get(i);
      UtilityExpertDecisionModel.Review review = base.review(text, .6, .15);
      out.append(i).append('\t').append(text).append('\t');
      if (review.features == null) out.append('-');
      else
        for (int j = 0; j < 20; j++) {
          if (j > 0) out.append(',');
          out.append(review.features[j]);
        }
      out.append('\t')
          .append(review.features == null ? -1 : ExpertTrustExperiment.score(risk, review));
      decision(out, review.before);
      decision(out, review.proposed);
      decision(out, model.classify(text, .6, .15));
      out.append('\n');
    }
    Files.write(
        assets.resolve("trust-probes.tsv"), out.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static void decision(StringBuilder out, TinyDecisionModel.Decision d) {
    out.append('\t')
        .append(d.status)
        .append('\t')
        .append(d.choice == null ? "-" : d.choice)
        .append('\t');
    double[] p = d.probabilitiesCopy();
    for (int i = 0; i < p.length; i++) {
      if (i > 0) out.append(',');
      out.append(p[i]);
    }
  }
}
