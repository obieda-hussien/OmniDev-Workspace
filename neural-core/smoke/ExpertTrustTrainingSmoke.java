package com.omnidev.workspace.domain.neural;

import com.omnidev.workspace.domain.neural.PublicRefusalCorpus.Row;
import java.util.*;

/** Exercises actual source labels, counterfactual targets and calibration-retention constraints. */
public final class ExpertTrustTrainingSmoke {
  public static void main(String[] args) throws Exception {
    UtilityExpertDecisionModel base = PrototypeSupportExperiment.utility(true);
    List<Row> rows = ResidualAdapterExperiment.rows();
    List<Row> sourceTrain = PublicRefusalCorpus.split(rows, "train"),
        fittingRows = args.length == 2 ? PairedTrustRehearsal.augment(sourceTrain) : sourceTrain;
    List<ExpertTrustExperiment.Item> train = ExpertTrustExperiment.items(fittingRows, base),
        dev = ExpertTrustExperiment.items(PublicRefusalCorpus.split(rows, "development"), base),
        cal = ExpertTrustExperiment.items(PublicRefusalCorpus.split(rows, "calibration"), base);
    List<Row> augmented = PairedTrustRehearsal.augment(sourceTrain);
    Map<String, String> labels = new HashMap<>();
    for (Row r : sourceTrain) labels.put(r.group, r.expected);
    Set<String> texts = new HashSet<>();
    for (Row r : augmented) {
      if (!r.split.equals("train") || !Objects.equals(labels.get(r.group), r.expected))
        throw new AssertionError("Rehearsal lost source intent/identity");
      if (r.kind.equals("trust_paired_rehearsal")
          && (!r.language.equals("mixed") || r.text.length() > 512))
        throw new AssertionError("Rehearsal shape changed");
      if (!texts.add(BilingualTextEncoder.normalize(r.text)))
        throw new AssertionError("Duplicate rehearsal text");
    }
    try {
      PairedTrustRehearsal.augment(PublicRefusalCorpus.split(rows, "development"));
      throw new AssertionError("Development used for rehearsal");
    } catch (IllegalArgumentException expected) {
    }
    int correctTeacherRawRejected = 0, positive = 0, negative = 0;
    Map<String, Integer> strata = new TreeMap<>();
    for (ExpertTrustExperiment.Item item : train)
      if (ExpertTrustExperiment.counterfactual(item)) {
        int label = ExpertTrustExperiment.counterfactualLabel(item);
        strata.merge(item.row.language + ":" + label, 1, Integer::sum);
        if (label == 1) positive++;
        else negative++;
        if (ExpertTrustExperiment.correct(item.row, item.review.before)) {
          if (label != 0) throw new AssertionError("Correct teacher distilled as a trust positive");
          if (item.review.rawExpert.choice.equals("__reject__")) correctTeacherRawRejected++;
        }
      }
    if (positive == 0 || negative == 0 || correctTeacherRawRejected == 0)
      throw new AssertionError("Missing real counterfactual controls");
    TinyNeuralRuntime zero =
        new TinyNeuralRuntime(
            new TinyNeuralRuntime.Dense(
                20, 2, new float[40], new float[2], TinyNeuralRuntime.Activation.LINEAR));
    TinyDecisionModel trust = new TinyDecisionModel(zero, new String[] {"keep", "use"}, 1);
    List<ExpertTrustExperiment.Item> constraints = new ArrayList<>(train);
    constraints.addAll(dev);
    constraints.addAll(cal);
    double[] cutoffs = ExpertTrustExperiment.selectCutoffs(dev, constraints, trust);
    for (ExpertTrustExperiment.Item item : constraints)
      if (item.review.before.choice != null
          && ExpertTrustExperiment.correct(item.row, item.review.before)) {
        double score =
            item.review.features == null ? 0 : ExpertTrustExperiment.score(trust, item.review);
        if (!ExpertTrustExperiment.correct(
            item.row,
            ExpertTrustDecisionModel.select(
                item.review,
                score,
                cutoffs[UtilityExpertDecisionModel.domain(item.review.before.choice)])))
          throw new AssertionError("Known correct constraint lost");
      }
    if (args.length >= 1) {
      StringBuilder json =
          new StringBuilder("{\"correct_teacher_raw_rejected\":")
              .append(correctTeacherRawRejected)
              .append(",\"use_targets\":")
              .append(positive)
              .append(",\"keep_targets\":")
              .append(negative)
              .append(",\"strata\":{");
      boolean first = true;
      for (Map.Entry<String, Integer> e : strata.entrySet()) {
        if (!first) json.append(',');
        first = false;
        json.append('"').append(e.getKey()).append("\":").append(e.getValue());
      }
      java.nio.file.Files.write(
          java.nio.file.Paths.get(args[0]),
          json.append("}}\n").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    System.out.println(
        "Trust real-row counterfactual targets and train/development/calibration retention controls"
            + " passed.");
  }
}
