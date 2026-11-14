package com.omnidev.workspace.domain.neural;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public final class DomainPrototypeSupportTest {
  private static final String HASH = String.join("", Collections.nCopies(64, "a"));

  private static float[] vectors() {
    float[] x = new float[5120];
    for (int d = 0; d < 5; d++) x[d * 1024 + d] = 1;
    return x;
  }

  private static DomainPrototypeSupport bank() {
    return new DomainPrototypeSupport(
        vectors(), new int[] {0, 1, 2, 3, 4}, HASH, DomainPrototypeSupport.VERSION);
  }

  @Test
  public void domainCosineIsBoundedAndUsesOnlyOwnedPrototypes() {
    float[] x = new float[1024];
    x[0] = 1;
    assertEquals(1, bank().similarity(x, 0), 0);
    assertEquals(0, bank().similarity(x, 1), 0);
    x[0] = -1;
    assertEquals(0, bank().similarity(x, 0), 0);
    assertEquals(0, bank().similarity(new float[1024], 0), 0);
  }

  @Test
  public void inputAndOwnerArraysAreCopiedAndFingerprintsBindTheBank() {
    float[] v = vectors();
    int[] owners = {0, 1, 2, 3, 4};
    DomainPrototypeSupport a =
        new DomainPrototypeSupport(v, owners, HASH, DomainPrototypeSupport.VERSION);
    String fingerprint = a.fingerprint();
    v[0] = 0;
    owners[0] = 4;
    float[] x = new float[1024];
    x[0] = 1;
    assertEquals(1, a.similarity(x, 0), 0);
    assertEquals(fingerprint, a.fingerprint());
    assertEquals(fingerprint, bank().fingerprint());
    float[] changed = vectors();
    changed[0] = 0;
    changed[10] = 1;
    assertNotEquals(
        fingerprint,
        new DomainPrototypeSupport(
                changed, new int[] {0, 1, 2, 3, 4}, HASH, DomainPrototypeSupport.VERSION)
            .fingerprint());
  }

  @Test
  public void malformedDimensionsValuesOwnersAndNormsAreRejected() {
    rejects(
        () ->
            new DomainPrototypeSupport(
                new float[1024], new int[] {0}, HASH, DomainPrototypeSupport.VERSION));
    rejects(
        () ->
            new DomainPrototypeSupport(
                vectors(), new int[] {0, 1, 2, 3, 3}, HASH, DomainPrototypeSupport.VERSION));
    float[] nan = vectors();
    nan[0] = Float.NaN;
    rejects(
        () ->
            new DomainPrototypeSupport(
                nan, new int[] {0, 1, 2, 3, 4}, HASH, DomainPrototypeSupport.VERSION));
    rejects(
        () ->
            new DomainPrototypeSupport(
                new float[5120], new int[] {0, 1, 2, 3, 4}, HASH, DomainPrototypeSupport.VERSION));
    rejects(() -> bank().similarity(new float[1023], 0));
    rejects(() -> bank().similarity(new float[1024], 5));
  }

  @Test
  public void prototypeProtectionOnlyRestoresTheExactOriginalDecision() {
    TinyDecisionModel.Decision before = decision(0), proposed = decision(1);
    assertSame(before, UtilityExpertDecisionModel.prototypeGuard(before, proposed, .8f, .7));
    assertSame(proposed, UtilityExpertDecisionModel.prototypeGuard(before, proposed, .5f, .7));
    assertSame(
        before, UtilityExpertDecisionModel.prototypeGuard(before, before.asAbstention(), .8f, .7));
    TinyDecisionModel.Decision refused = before.asAbstention();
    assertSame(refused, UtilityExpertDecisionModel.prototypeGuard(refused, proposed, .1f, .7));
    rejects(() -> UtilityExpertDecisionModel.prototypeGuard(before, proposed, Float.NaN, .7));
  }

  @Test
  public void optionalBankPairingAndCutoffsAreStrict() {
    RichTextEncoder e =
        new RichTextEncoder(new String[] {"w:source"}, new float[] {1}, RichTextEncoder.VERSION);
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
    DomainPrototypeSupport p =
        new DomainPrototypeSupport(
            vectors(), new int[] {0, 1, 2, 3, 4}, e.fingerprint(), DomainPrototypeSupport.VERSION);
    double[] reject = {1.1, 1.1, 1.1, 1.1, 1.1},
        change = {.6, 1.1, 1.1, 1.1, 1.1},
        support = reject.clone(),
        cutoffs = {0, 0, 0, 0, 0};
    UtilityExpertDecisionModel m =
        new UtilityExpertDecisionModel(
            teacher,
            e,
            bias(1024, 0, 0, 3, 0, 0, 0),
            1,
            reject,
            change,
            support,
            e.fingerprint(),
            p,
            cutoffs,
            p.fingerprint());
    cutoffs[0] = 1.1;
    assertEquals("repository", m.classify("source", .6, .15).choice);
    rejects(
        () ->
            new UtilityExpertDecisionModel(
                teacher,
                e,
                bias(1024, 0, 0, 3, 0, 0, 0),
                1,
                reject,
                change,
                support,
                e.fingerprint(),
                p,
                cutoffs,
                "foreign"));
    rejects(
        () ->
            new UtilityExpertDecisionModel(
                teacher,
                e,
                bias(1024, 0, 0, 3, 0, 0, 0),
                1,
                reject,
                change,
                support,
                e.fingerprint(),
                null,
                cutoffs,
                null));
  }

  private static TinyDecisionModel.Decision decision(int i) {
    double[] p = {.03, .03, .03, .03, .03};
    p[i] = .88;
    return TinyDecisionModel.fromDistribution(p, MultiIntentRefusalModel.labels(), 0, 0);
  }

  private static TinyNeuralRuntime bias(int width, float... v) {
    return new TinyNeuralRuntime(
        new TinyNeuralRuntime.Dense(
            width, v.length, new float[width * v.length], v, TinyNeuralRuntime.Activation.LINEAR));
  }

  private static void rejects(Runnable action) {
    try {
      action.run();
    } catch (IllegalArgumentException expected) {
      return;
    }
    fail("Expected prototype rejection");
  }
}
