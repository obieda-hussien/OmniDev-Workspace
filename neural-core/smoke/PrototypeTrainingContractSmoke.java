package com.omnidev.workspace.domain.neural;

import com.omnidev.workspace.domain.neural.PublicRefusalCorpus.Row;
import java.util.*;

/**
 * Exercise the actual fitter and empirical calibration on real source rows, including group
 * weighting.
 */
public final class PrototypeTrainingContractSmoke {
  public static void main(String[] args) throws Exception {
    List<Row> all = ResidualAdapterExperiment.rows(),
        train = PublicRefusalCorpus.split(all, "train"),
        cal = PublicRefusalCorpus.split(all, "calibration");
    RichTextEncoder encoder = PublicRefusalExperiment.encoder();
    RichIntentRefusalModel teacher = ResidualAdapterExperiment.teacher(true);
    PrototypeSupportTrainer.Fit a = PrototypeSupportTrainer.fit(train, encoder, teacher);
    List<Row> repeated = new ArrayList<>(train);
    Row original = null;
    for (Row r : train)
      if (r.label == 0 && Objects.equals(teacher.classify(r.text, .6, .15).choice, r.expected)) {
        original = r;
        break;
      }
    if (original == null) throw new AssertionError("No known prototype training control");
    repeated.add(original);
    PrototypeSupportTrainer.Fit b = PrototypeSupportTrainer.fit(repeated, encoder, teacher);
    if (!Arrays.equals(a.groupCounts, b.groupCounts) || a.bank.floatCount() != 15360)
      throw new AssertionError("Prototype source-group weighting changed");
    for (int i = 0; i < a.vectors.length; i++)
      if (Math.abs(a.vectors[i] - b.vectors[i]) > 2e-6)
        throw new AssertionError("Duplicating a source group reweighted the bank");
    PrototypeSupportTrainer.Calibration calibration =
        PrototypeSupportTrainer.calibrate(cal, a.bank, encoder, teacher);
    for (Row r : cal)
      if (r.label == 0 && Objects.equals(teacher.classify(r.text, .6, .15).choice, r.expected)) {
        int d = UtilityExpertDecisionModel.domain(r.expected);
        if (a.bank.similarity(encoder.encode(r.text), d) < calibration.cutoffs[d])
          throw new AssertionError("Empirical calibration control not protected");
      }
    try {
      PrototypeSupportTrainer.fit(cal, encoder, teacher);
      throw new AssertionError("Calibration used to fit prototypes");
    } catch (IllegalArgumentException expected) {
    }
    try {
      PrototypeSupportTrainer.calibrate(train, a.bank, encoder, teacher);
      throw new AssertionError("Training used to fit protection floors");
    } catch (IllegalArgumentException expected) {
    }
    List<Row> missing = new ArrayList<>();
    for (Row r : train)
      if (!(r.language.equals("mixed") && r.expected.equals("repository"))) missing.add(r);
    try {
      PrototypeSupportTrainer.fit(missing, encoder, teacher);
      throw new AssertionError("Missing training stratum accepted");
    } catch (IllegalArgumentException expected) {
    }
    List<Row> noRepository = new ArrayList<>();
    for (Row r : cal) if (!r.expected.equals("repository")) noRepository.add(r);
    PrototypeSupportTrainer.Calibration partial =
        PrototypeSupportTrainer.calibrate(noRepository, a.bank, encoder, teacher);
    if (partial.counts[0] != 0 || partial.cutoffs[0] != 1.1)
      throw new AssertionError("Unsupported calibration route enabled");
    System.out.println(
        "Prototype real-row/group-balance/partition/calibration coverage controls passed.");
  }
}
