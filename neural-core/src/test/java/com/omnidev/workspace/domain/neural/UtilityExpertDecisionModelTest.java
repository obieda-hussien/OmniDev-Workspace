package com.omnidev.workspace.domain.neural;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public final class UtilityExpertDecisionModelTest {
  private static TinyDecisionModel.Decision decision(int best, int count, double confidence) {
    double[] p = new double[count];
    Arrays.fill(p, (1 - confidence) / (count - 1));
    p[best] = confidence;
    return TinyDecisionModel.fromDistribution(
        p, count == 5 ? MultiIntentRefusalModel.labels() : RichTextDecisionModel.labels(), 0, 0);
  }

  @Test
  public void previousAbstentionsCannotBeUpgraded() {
    TinyDecisionModel.Decision before = decision(0, 5, .9).asAbstention();
    assertSame(
        before, UtilityExpertDecisionModel.choose(before, decision(2, 6, .99), .5, .6, .1, .5));
  }

  @Test
  public void rejectionPreservesTheOriginalDistribution() {
    TinyDecisionModel.Decision before = decision(0, 5, .9),
        after = UtilityExpertDecisionModel.choose(before, decision(5, 6, .9), .8, 1.1, .1, .5);
    assertEquals(TinyDecisionModel.Status.ABSTAIN, after.status);
    assertArrayEquals(before.probabilitiesCopy(), after.probabilitiesCopy(), 0);
  }

  @Test
  public void strongExistingDomainSupportProtectsAgainstARejection() {
    TinyDecisionModel.Decision before = decision(0, 5, .9);
    assertSame(
        before, UtilityExpertDecisionModel.choose(before, decision(5, 6, .99), .8, 1.1, .9, .8));
  }

  @Test
  public void agreeingAndDisabledActionsKeepTheExactOriginalDecision() {
    TinyDecisionModel.Decision before = decision(0, 5, .9);
    assertSame(before, UtilityExpertDecisionModel.choose(before, decision(0, 6, .99), .5, .6));
    assertSame(before, UtilityExpertDecisionModel.choose(before, decision(2, 6, .99), 1.1, 1.1));
    assertSame(before, UtilityExpertDecisionModel.choose(before, decision(5, 6, .99), 1.1, 1.1));
  }

  @Test
  public void alternativeProposalKeepsItsOwnSixClassScores() {
    TinyDecisionModel.Decision before = decision(0, 5, .9), expert = decision(2, 6, .9);
    assertSame(expert, UtilityExpertDecisionModel.choose(before, expert, 1.1, .8));
    assertEquals("device", expert.choice);
    assertEquals(6, expert.probabilitiesCopy().length);
  }

  @Test
  public void invalidPoliciesForeignSchemasAndScoresAreRejected() {
    TinyDecisionModel.Decision before = decision(0, 5, .9);
    rejects(() -> UtilityExpertDecisionModel.choose(before, decision(2, 5, .9), .8, .8));
    rejects(() -> UtilityExpertDecisionModel.choose(before, decision(2, 6, .9), 1.01, .8));
    rejects(
        () ->
            UtilityExpertDecisionModel.choose(before, decision(5, 6, .9), .8, .8, Double.NaN, .7));
    rejects(() -> UtilityExpertDecisionModel.choose(before, decision(5, 6, .9), .8, .8, 1.01, .7));
  }

  @Test
  public void constructorCopiesPoliciesAndEnforcesTeacherVocabularyPairing() {
    RichTextEncoder e =
        new RichTextEncoder(new String[] {"w:source"}, new float[] {1}, RichTextEncoder.VERSION);
    RichTextEncoder foreign =
        new RichTextEncoder(new String[] {"w:other"}, new float[] {1}, RichTextEncoder.VERSION);
    GatedTextIntentModel base =
        new GatedTextIntentModel(
            bias(256, 10, 0, 0, 0, 0),
            MultiIntentRefusalModel.labels(),
            1,
            bias(256, 10, 0),
            1,
            .99,
            BilingualTextEncoder.VERSION);
    RichIntentRefusalModel teacher =
        new RichIntentRefusalModel(
            base,
            e,
            bias(1024, 0, 0, 0, 0, 0),
            .7,
            false,
            RichTextEncoder.VERSION,
            e.fingerprint());
    double[] reject = {1.1, 1.1, 1.1, 1.1, 1.1}, change = reject.clone();
    UtilityExpertDecisionModel model =
        new UtilityExpertDecisionModel(
            teacher, e, bias(1024, 0, 0, 0, 0, 0, 10), 1, reject, change, e.fingerprint());
    reject[0] = .5;
    assertEquals("repository", model.classify("source", .6, .15).choice);
    rejects(
        () ->
            new UtilityExpertDecisionModel(
                teacher,
                foreign,
                bias(1024, 0, 0, 0, 0, 0, 10),
                1,
                reject,
                change,
                foreign.fingerprint()));
    rejects(
        () ->
            new UtilityExpertDecisionModel(
                teacher, e, bias(256, 0, 0, 0, 0, 0, 10), 1, reject, change, e.fingerprint()));
  }

  private static TinyNeuralRuntime bias(int width, float... b) {
    return new TinyNeuralRuntime(
        new TinyNeuralRuntime.Dense(
            width, b.length, new float[width * b.length], b, TinyNeuralRuntime.Activation.LINEAR));
  }

  private static void rejects(Runnable action) {
    try {
      action.run();
    } catch (IllegalArgumentException expected) {
      return;
    }
    fail("Expected utility rejection");
  }
}
