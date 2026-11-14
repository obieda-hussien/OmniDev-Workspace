package com.omnidev.workspace.domain.neural;

import com.omnidev.workspace.domain.neural.PublicRefusalCorpus.Row;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Compiled and committed prototype/policy numeric parity, shared preparation and prior refusal
 * integrity.
 */
public final class PrototypeCheckpointSmoke {
  public static void main(String[] args) throws Exception {
    Class<?> bankType = Class.forName("TrainedDomainPrototypes"),
        policy = Class.forName("TrainedPrototypePolicy");
    DomainPrototypeSupport bank = (DomainPrototypeSupport) bankType.getMethod("model").invoke(null);
    RichTextEncoder encoder = PublicRefusalExperiment.encoder();
    if (policy.getField("PRODUCTION_ELIGIBLE").getBoolean(null)
        || !bank.fingerprint().equals(policy.getField("BANK_SHA256").get(null))
        || !bank.vocabularyFingerprint().equals(encoder.fingerprint()))
      throw new AssertionError("Foreign/promoted prototype checkpoint");
    double[] reject = (double[]) policy.getMethod("reject").invoke(null),
        change = (double[]) policy.getMethod("change").invoke(null),
        floors = (double[]) policy.getMethod("cutoffs").invoke(null);
    UtilityExpertDecisionModel
        a = PrototypeSupportExperiment.model(bank, floors, reject, change, false),
        b = PrototypeSupportExperiment.model(bank, floors, reject, change, true);
    RichIntentRefusalModel teacher = ResidualAdapterExperiment.teacher(true);
    List<Row> rows = ResidualAdapterExperiment.rows();
    rows.addAll(
        PublicRefusalCorpus.old(
            BilingualRefusalCorpus.freshEnglish(
                Paths.get("neural-core/fixtures/rich-text/fresh-english.tsv"))));
    rows.addAll(
        PublicRefusalCorpus.old(
            BilingualRefusalCorpus.challenges(
                Paths.get("neural-core/fixtures/decision-challenges.tsv"))));
    rows.addAll(ResidualAdapterExperiment.fresh());
    rows.addAll(UtilityExpertExperiment.fresh());
    rows.addAll(PrototypeSupportExperiment.authored());
    List<String> expected = Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8);
    List<String> meta =
        Arrays.asList(
            "bank_sha256\t" + bank.fingerprint(),
            "reject\t" + Arrays.toString(reject),
            "change\t" + Arrays.toString(change),
            "cutoffs\t" + Arrays.toString(floors),
            "status\tchoice\tprobabilities\tsupport");
    if (expected.size() != rows.size() + 5 || !expected.subList(0, 5).equals(meta))
      throw new AssertionError("Prototype metadata/count drift");
    for (int i = 0; i < rows.size(); i++) {
      Row r = rows.get(i);
      TinyDecisionModel.Decision d = a.classify(r.text, .6, .15),
          s = b.classify(r.text, .6, .15),
          u = a.classifyUnshared(r.text, .6, .15);
      exact(d, s);
      exact(d, u);
      UtilityExpertExperiment.priorRefusal(teacher.classify(r.text, .6, .15), d);
      String[] ref = expected.get(i + 5).split("\t", -1);
      if (ref.length != 4
          || !ref[0].equals(d.status.name())
          || !ref[1].equals(d.choice == null ? "-" : d.choice))
        throw new AssertionError("Prototype typed decision drift");
      String[] probabilities = ref[2].split(",", -1), support = ref[3].split(",", -1);
      double[] actual = d.probabilitiesCopy();
      if (probabilities.length != actual.length || support.length != 5)
        throw new AssertionError("Prototype score schema drift");
      float[] x = encoder.encode(r.text);
      for (int j = 0; j < actual.length; j++) {
        double p = Double.parseDouble(probabilities[j]);
        if (!Double.isFinite(p) || Math.abs(p - actual[j]) > 2e-6)
          throw new AssertionError("Prototype decision numeric drift");
      }
      for (int j = 0; j < 5; j++) {
        double value = Double.parseDouble(support[j]);
        if (!Double.isFinite(value) || Math.abs(value - bank.similarity(x, j)) > 2e-6)
          throw new AssertionError("Prototype support numeric drift");
      }
    }
    System.out.println(
        "Prototype compiled/committed metadata/numeric/dense-sparse/shared-unshared integrity"
            + " passed on "
            + rows.size()
            + " observations.");
  }

  private static void exact(TinyDecisionModel.Decision a, TinyDecisionModel.Decision b) {
    if (a.status != b.status
        || !Objects.equals(a.choice, b.choice)
        || !Arrays.equals(a.probabilitiesCopy(), b.probabilitiesCopy()))
      throw new AssertionError("Prototype implementation equivalence changed");
  }
}
