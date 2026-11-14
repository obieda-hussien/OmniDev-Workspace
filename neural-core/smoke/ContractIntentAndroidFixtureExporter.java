package com.omnidev.workspace.domain.neural;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** New weights/policy against all existing Android trust inputs plus the authored corpus. */
public final class ContractIntentAndroidFixtureExporter {
  public static void main(String[] args) throws Exception {
    Path assets = Paths.get(args[0], "assets");
    List<String> texts = new ArrayList<>();
    List<String> old =
        Files.readAllLines(assets.resolve("trust-probes.tsv"), StandardCharsets.UTF_8);
    for (String line : old.subList(6, old.size())) texts.add(line.split("\t", -1)[1]);
    for (PublicRefusalCorpus.Row row : ContractIntentCorpus.load("all")) texts.add(row.text);
    if (texts.size() != 1366) throw new AssertionError("Contract Android probe count changed");
    ContractIntentCheckpointSmoke.Loaded cp = new ContractIntentCheckpointSmoke.Loaded();
    UtilityExpertDecisionModel model = cp.model(false);
    RichTextDecisionModel raw = cp.raw(false);
    RichIntentRefusalModel teacher = ResidualAdapterExperiment.teacher(false);
    StringBuilder out =
        new StringBuilder(
            "runtime_sha256\t"
                + cp.fingerprint
                + "\nsource\t"
                + cp.source
                + "\ntemperature\t"
                + cp.temperature
                + "\nvocabulary_sha256\t"
                + PublicRefusalExperiment.encoder().fingerprint()
                + "\nreject\t"
                + Arrays.toString(cp.reject)
                + "\nchange\t"
                + Arrays.toString(cp.change)
                + "\nsupport\t"
                + Arrays.toString(cp.support)
                + "\n"
                + "id\ttext\tteacher_status\tteacher_choice\tteacher_probabilities"
                + "\tprotected_status\tprotected_choice\tprotected_probabilities\traw_status"
                + "\traw_choice\traw_probabilities\n");
    for (int i = 0; i < texts.size(); i++) {
      String text = texts.get(i);
      out.append(i).append('\t').append(text);
      decision(out, teacher.classify(text, .6, .15));
      decision(out, model.classify(text, .6, .15));
      decision(out, raw.classify(text, .6, .15));
      out.append('\n');
    }
    Files.write(
        assets.resolve("contract-probes.tsv"), out.toString().getBytes(StandardCharsets.UTF_8));
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
