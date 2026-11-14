package com.omnidev.workspace.domain.neural;

import com.omnidev.workspace.domain.neural.DistilledAdamTrainer.Example;
import com.omnidev.workspace.domain.neural.PublicRefusalCorpus.Row;
import java.util.*;

/**
 * Actual optimizer finite differences, AdamW decay/clipping, learnability and policy rehearsal
 * constraints.
 */
public final class UtilityTrainingContractSmoke {
  public static void main(String[] args) {
    for (int hidden : new int[] {0, 16}) gradient(hidden);
    double[] vector = {3, 4};
    DistilledAdamTrainer.clip(vector, 1);
    if (Math.abs(vector[0] - .6) > 1e-12 || Math.abs(vector[1] - .8) > 1e-12)
      throw new AssertionError("Global clipping changed");
    rejects(() -> DistilledAdamTrainer.clip(new double[] {Double.NaN}, 1));
    rejects(() -> DistilledAdamTrainer.clip(new double[] {1}, 0));
    float[] values = {2, 2};
    DistilledAdamTrainer.update(
        values, new double[2], new double[2], new double[2], new boolean[] {true, false}, 1);
    if (Math.abs(values[0] - (float) (2 - .003 * .0005 * 2)) > 1e-7 || values[1] != 2)
      throw new AssertionError("AdamW decay is coupled to gradient or applied to bias");
    List<Example> train = new ArrayList<>(), dev = new ArrayList<>();
    for (int i = 0; i < 18; i++) train.add(example("train-" + i, i % 6));
    for (int i = 0; i < 6; i++) dev.add(example("dev-" + i, i));
    DistilledAdamTrainer.Result a = DistilledAdamTrainer.fit(train, dev, 0, 42),
        b = DistilledAdamTrainer.fit(train, dev, 0, 42);
    if (!Arrays.equals(a.model.parameters, b.model.parameters)
        || a.accuracy < .99
        || a.epochsRun > 100
        || a.epoch > a.epochsRun)
      throw new AssertionError("Adam learnability/determinism/bounds changed");
    double[] actual = DistilledAdamTrainer.evaluate(a.model, dev, 1);
    if (Math.abs(actual[0] - a.accuracy) > 1e-9 || Math.abs(actual[1] - a.loss) > 1e-9)
      throw new AssertionError("Adam best snapshot changed");
    List<Example> leak = new ArrayList<>(dev);
    leak.add(example("train-0", 0));
    rejects(() -> DistilledAdamTrainer.fit(train, leak, 0, 42));
    policy();
    System.out.println(
        "Utility actual gradients/AdamW/clipping/learning/snapshots/leakage/rehearsal policy"
            + " contracts passed.");
  }

  private static Example example(String group, int label) {
    float[] x = new float[1024];
    x[label] = 1;
    double[] target = new double[6];
    target[label] = 1;
    return new Example(group, "en", label, x, target);
  }

  private static void gradient(int hidden) {
    float[] p = new float[DistilledAdamTrainer.size(hidden)];
    int output = hidden == 0 ? 0 : 16400, width = hidden == 0 ? 1024 : 16;
    if (hidden != 0) {
      p[0] = .2f;
      p[16384] = .1f;
    }
    p[output] = .3f;
    p[output + width] = -.2f;
    p[output + width * 6] = .05f;
    float[] x = new float[1024];
    x[0] = .7f;
    x[27] = .5f;
    double[] target = {.9, .02, .02, .02, .02, .02};
    Example e = new Example("gradient", "en", 0, x, target);
    double[] g = new double[p.length];
    DistilledAdamTrainer.accumulate(p, hidden, e, g);
    int[] indices = hidden == 0 ? new int[] {0, 1024, 6144} : new int[] {0, 16384, 16400, 16496};
    for (int i : indices) {
      float[] plus = p.clone(), minus = p.clone();
      plus[i] += .001f;
      minus[i] -= .001f;
      double difference =
          (loss(new DistilledAdamTrainer.Model(hidden, plus), e)
                  - loss(new DistilledAdamTrainer.Model(hidden, minus), e))
              / (plus[i] - minus[i]);
      if (Math.abs(difference - g[i]) > .002)
        throw new AssertionError("Adam gradient mismatch hidden=" + hidden + " index=" + i);
    }
  }

  private static double loss(DistilledAdamTrainer.Model model, Example e) {
    double[] p =
        new TinyDecisionModel(model.runtime(), RichTextDecisionModel.labels(), 1)
            .decide(e.x, 0, 0)
            .probabilitiesCopy();
    double loss = 0;
    for (int i = 0; i < 6; i++) loss -= e.target[i] * Math.log(p[i]);
    return loss;
  }

  private static void policy() {
    TinyDecisionModel.Decision teacher = decision(0, 5, .9),
        rejectHigh = decision(5, 6, .95),
        rejectLow = decision(5, 6, .8);
    Row negative = new Row("development", "neg", "en", "-", "control", "source");
    Row known = new Row("train", "known", "en", "repository", "control", "source");
    List<ExpertPolicyTrainer.Observation> dev =
        Collections.singletonList(
            new ExpertPolicyTrainer.Observation(negative, teacher, rejectLow, .1));
    List<ExpertPolicyTrainer.Observation> rehearsal =
        Collections.singletonList(
            new ExpertPolicyTrainer.Observation(known, teacher, rejectHigh, .9));
    ExpertPolicyTrainer.Policy p = ExpertPolicyTrainer.fit(dev, rehearsal);
    if (UtilityExpertDecisionModel.choose(
                    teacher, rejectLow, p.reject[0], p.change[0], .1, p.support[0])
                .status
            != TinyDecisionModel.Status.ABSTAIN
        || !"repository"
            .equals(
                UtilityExpertDecisionModel.choose(
                        teacher, rejectHigh, p.reject[0], p.change[0], .9, p.support[0])
                    .choice))
      throw new AssertionError("Conditional support/rehearsal policy failed");
    Row wrongSplit = new Row("test", "wrong", "en", "-", "control", "source");
    rejects(
        () ->
            ExpertPolicyTrainer.fit(
                Collections.singletonList(
                    new ExpertPolicyTrainer.Observation(wrongSplit, teacher, rejectLow, .1)),
                rehearsal));
    Row wrongRehearsal = new Row("development", "wrong", "en", "repository", "control", "source");
    rejects(
        () ->
            ExpertPolicyTrainer.fit(
                dev,
                Collections.singletonList(
                    new ExpertPolicyTrainer.Observation(wrongRehearsal, teacher, rejectHigh, .9))));
  }

  private static TinyDecisionModel.Decision decision(int best, int width, double probability) {
    double[] p = new double[width];
    Arrays.fill(p, (1 - probability) / (width - 1));
    p[best] = probability;
    String[] labels =
        width == 5 ? MultiIntentRefusalModel.labels() : RichTextDecisionModel.labels();
    return TinyDecisionModel.fromDistribution(p, labels, 0, 0);
  }

  private static void rejects(Runnable action) {
    try {
      action.run();
    } catch (IllegalArgumentException expected) {
      return;
    }
    throw new AssertionError("Invalid utility training input accepted");
  }
}
