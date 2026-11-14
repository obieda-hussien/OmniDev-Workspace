package com.omnidev.workspace.domain.neural;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public final class ExpertTrustDecisionModelTest {
  private static TinyNeuralRuntime bias(int width, float... bias) {
    return new TinyNeuralRuntime(
        new TinyNeuralRuntime.Dense(
            width,
            bias.length,
            new float[width * bias.length],
            bias,
            TinyNeuralRuntime.Activation.LINEAR));
  }

  private static UtilityExpertDecisionModel base(
      boolean sparse, float teacherBias, float expertBias) {
    RichTextEncoder e =
        new RichTextEncoder(new String[] {"w:source"}, new float[] {1}, RichTextEncoder.VERSION);
    TinyNeuralRuntime a = bias(256, teacherBias, 0, 0, 0, 0),
        g = bias(256, 10, 0),
        p = bias(1024, 0, 0, 0, 0, 0),
        n = bias(1024, 0, expertBias, 0, 0, 0, 0);
    GatedTextIntentModel original =
        new GatedTextIntentModel(
            sparse ? a.withSparseInput() : a,
            MultiIntentRefusalModel.labels(),
            1,
            sparse ? g.withSparseInput() : g,
            1,
            .99,
            BilingualTextEncoder.VERSION);
    RichIntentRefusalModel teacher =
        new RichIntentRefusalModel(
            original,
            e,
            sparse ? p.withSparseInput() : p,
            .7,
            false,
            RichTextEncoder.VERSION,
            e.fingerprint());
    double[] reject = {1.1, 1.1, 1.1, 1.1, 1.1}, change = {.6, 1.1, 1.1, 1.1, 1.1};
    return new UtilityExpertDecisionModel(
        teacher, e, sparse ? n.withSparseInput() : n, 1, reject, change, e.fingerprint());
  }

  private static ExpertTrustDecisionModel model(UtilityExpertDecisionModel base, double[] cutoffs) {
    return new ExpertTrustDecisionModel(
        base,
        bias(20, 0, 5),
        1,
        cutoffs,
        ExpertTrustDecisionModel.VERSION,
        base.checkpointFingerprint());
  }

  @Test
  public void fingerprintsBindAllWeightsAndPoliciesButNotSparseExecution() {
    UtilityExpertDecisionModel a = base(false, 5, 5);
    assertEquals(a.checkpointFingerprint(), base(true, 5, 5).checkpointFingerprint());
    assertNotEquals(a.checkpointFingerprint(), base(false, 6, 5).checkpointFingerprint());
    assertNotEquals(a.checkpointFingerprint(), base(false, 5, 6).checkpointFingerprint());
    assertNotEquals(bias(20, 0, 5).checkpointFingerprint(), bias(20, 0, 4).checkpointFingerprint());
    assertNotEquals(
        new CheckpointFingerprint("a").text("bc").finish(),
        new CheckpointFingerprint("ab").text("c").finish());
  }

  @Test
  public void selectorCanOnlyReturnOneOfTheExactExistingDecisions() {
    UtilityExpertDecisionModel base = base(false, 5, 5);
    UtilityExpertDecisionModel.Review r = base.review("source", .6, .15);
    assertEquals("repository", r.before.choice);
    assertEquals("build", r.proposed.choice);
    assertSame(r.before, ExpertTrustDecisionModel.select(r, .8, .9));
    assertSame(r.proposed, ExpertTrustDecisionModel.select(r, .9, .8));
    assertEquals(
        "build",
        model(base, new double[] {0, 1.1, 1.1, 1.1, 1.1}).classify("source", .6, .15).choice);
  }

  @Test
  public void rejectedOrUnchangedTeacherCannotBeUpgraded() {
    UtilityExpertDecisionModel a = base(false, 5, 5);
    TinyDecisionModel.Decision refused = a.review("source", .6, .15).before.asAbstention();
    UtilityExpertDecisionModel.Review r =
        new UtilityExpertDecisionModel.Review(
            refused, a.review("source", .6, .15).proposed, null, null);
    assertSame(refused, ExpertTrustDecisionModel.select(r, 1, 0));
    UtilityExpertDecisionModel tied = base(false, 5, 0);
    assertEquals(
        "repository", model(tied, new double[] {0, 0, 0, 0, 0}).classify("source", .6, .15).choice);
    assertEquals(
        TinyDecisionModel.Status.ABSTAIN,
        model(a, new double[] {0, 0, 0, 0, 0}).classify("", .6, .15).status);
  }

  @Test
  public void cutoffArraysAreCopiedAndDisabledRoutesRetainTeacher() {
    UtilityExpertDecisionModel a = base(false, 5, 5);
    double[] cutoffs = {1.1, 1.1, 1.1, 1.1, 1.1};
    ExpertTrustDecisionModel m = model(a, cutoffs);
    cutoffs[0] = 0;
    assertEquals("repository", m.classify("source", .6, .15).choice);
    assertEquals(a.checkpointFingerprint(), m.baseFingerprint());
  }

  @Test
  public void foreignCheckpointSchemaAndInvalidCutoffsAreRejected() {
    UtilityExpertDecisionModel a = base(false, 5, 5);
    double[] cutoffs = {0, 0, 0, 0, 0};
    rejects(
        () ->
            new ExpertTrustDecisionModel(
                a,
                bias(20, 0, 5),
                1,
                cutoffs,
                ExpertTrustDecisionModel.VERSION,
                base(false, 6, 5).checkpointFingerprint()));
    rejects(
        () ->
            new ExpertTrustDecisionModel(
                a,
                bias(21, 0, 5),
                1,
                cutoffs,
                ExpertTrustDecisionModel.VERSION,
                a.checkpointFingerprint()));
    rejects(
        () ->
            new ExpertTrustDecisionModel(
                a, bias(20, 0, 5), 1, cutoffs, "foreign", a.checkpointFingerprint()));
    rejects(() -> model(a, new double[] {Double.NaN, 0, 0, 0, 0}));
    rejects(() -> model(a, new double[] {1.01, 0, 0, 0, 0}));
  }

  @Test
  public void callerConfidenceAndBoundsRemainEnforced() {
    ExpertTrustDecisionModel m = model(base(false, 5, 5), new double[] {0, 0, 0, 0, 0});
    assertEquals(TinyDecisionModel.Status.ABSTAIN, m.classify("source", .99999, .99).status);
    rejects(() -> m.classify(null, .6, .15));
    rejects(() -> m.classify("source", Double.NaN, .15));
    rejects(() -> m.classify(String.join("", Collections.nCopies(2049, "x")), .6, .15));
  }

  @Test
  public void featureContractIsBoundedAndUsesExactProbabilitySchemas() {
    UtilityExpertDecisionModel.Review r = base(false, 5, 5).review("source", .6, .15);
    assertEquals(20, r.features.length);
    assertEquals(1, r.features[11], 0);
    for (float x : r.features) assertTrue(Float.isFinite(x) && x >= 0 && x <= 1);
    rejects(() -> ExpertTrustDecisionModel.features(r.before, r.before, .5f));
    rejects(() -> ExpertTrustDecisionModel.features(r.before, r.rawExpert, Float.NaN));
    rejects(() -> ExpertTrustDecisionModel.select(r, Double.NaN, .5));
  }

  private static void rejects(Runnable r) {
    try {
      r.run();
    } catch (IllegalArgumentException expected) {
      return;
    }
    fail("Invalid trust input accepted");
  }
}
