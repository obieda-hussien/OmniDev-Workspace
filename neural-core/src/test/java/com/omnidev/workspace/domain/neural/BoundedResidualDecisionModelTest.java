package com.omnidev.workspace.domain.neural;

import org.junit.Test;
import static org.junit.Assert.*;

public final class BoundedResidualDecisionModelTest {
    private static TinyDecisionModel.Decision before() { return TinyDecisionModel.fromDistribution(new double[] {0.7, 0.2, 0.04, 0.03, 0.03}, MultiIntentRefusalModel.labels(), 0.6, 0.15); }
    @Test public void zeroGainReturnsTheIdenticalPriorDecision() {
        TinyDecisionModel.Decision prior = before();
        assertSame(prior, BoundedResidualDecisionModel.refine(prior, new float[] {-1, 1, 0, 0, 0}, 0, 0.6, 0.15));
    }
    @Test public void priorAbstentionCannotBeUpgradedAndKeepsScores() {
        TinyDecisionModel.Decision prior = before().asAbstention();
        assertSame(prior, BoundedResidualDecisionModel.refine(prior, new float[] {-1, 1, 0, 0, 0}, 1, 0, 0));
    }
    @Test public void boundedCorrectionCanChangeOnlyAProposal() {
        TinyDecisionModel.Decision prior = before(), after = BoundedResidualDecisionModel.refine(prior, new float[] {-1, 1, 0, 0, 0}, 1, 0.5, 0.1);
        assertEquals("build", after.choice); assertEquals("repository", prior.choice);
        double[] p = after.probabilitiesCopy(); p[0] = 1;
        assertTrue(after.probabilitiesCopy()[0] < 0.5); assertEquals(0.7, prior.probabilitiesCopy()[0], 0);
    }
    @Test public void outOfBoundCorrectionsAndGainsAreRejected() {
        rejects(() -> BoundedResidualDecisionModel.refine(before(), new float[] {2, 0, 0, 0, 0}, 1, 0.6, 0.15));
        rejects(() -> BoundedResidualDecisionModel.refine(before(), new float[] {Float.NaN, 0, 0, 0, 0}, 1, 0.6, 0.15));
        rejects(() -> BoundedResidualDecisionModel.refine(before(), new float[5], 1.01, 0.6, 0.15));
        rejects(() -> BoundedResidualDecisionModel.refine(before(), new float[4], 1, 0.6, 0.15));
    }
    @Test public void distributionFactoryRejectsUnnormalizedForgedAndDuplicateSchemas() {
        rejects(() -> TinyDecisionModel.fromDistribution(new double[] {1, 1}, new String[] {"a", "b"}, 0.6, 0.15));
        rejects(() -> TinyDecisionModel.fromDistribution(new double[] {Double.NaN, 0}, new String[] {"a", "b"}, 0.6, 0.15));
        rejects(() -> TinyDecisionModel.fromDistribution(new double[] {0.5, 0.5}, new String[] {"a", "a"}, 0.6, 0.15));
        rejects(() -> TinyDecisionModel.fromDistribution(new double[] {0.5, 0.5}, new String[] {"a", "b"}, Double.NaN, 0.15));
    }
    @Test public void publicWrapperEnforcesPairingAndShapeAndSkipsDisabledInference() {
        RichTextEncoder e = new RichTextEncoder(new String[] {"w:source"}, new float[] {1}, RichTextEncoder.VERSION);
        TinyNeuralRuntime domain = bias(256, 10, 0, 0, 0, 0), refusal = bias(256, 10, 0), presence = bias(1024, 0, 0, 0, 0, 0);
        GatedTextIntentModel base = new GatedTextIntentModel(domain, MultiIntentRefusalModel.labels(), 1, refusal, 1, 0.99, BilingualTextEncoder.VERSION);
        RichIntentRefusalModel teacher = new RichIntentRefusalModel(base, e, presence, 0.7, false, RichTextEncoder.VERSION, e.fingerprint());
        TinyNeuralRuntime invalidValues = bias(1024, 5, 5, 5, 5, 5);
        BoundedResidualDecisionModel disabled = new BoundedResidualDecisionModel(teacher, e, invalidValues, 0, e.fingerprint());
        assertEquals(teacher.classify("source", 0.6, 0.15).choice, disabled.classify("source", 0.6, 0.15).choice);
        rejects(() -> new BoundedResidualDecisionModel(teacher, e, invalidValues, 1, "foreign"));
        rejects(() -> new BoundedResidualDecisionModel(teacher, e, bias(256, 0, 0, 0, 0, 0), 1, e.fingerprint()));
        rejects(() -> new BoundedResidualDecisionModel(teacher, e, invalidValues, Double.NaN, e.fingerprint()));
        rejects(() -> new BoundedResidualDecisionModel(teacher, e, invalidValues, 1, e.fingerprint()).classify("source", 0.6, 0.15));
    }
    private static TinyNeuralRuntime bias(int width, float... bias) { return new TinyNeuralRuntime(new TinyNeuralRuntime.Dense(width, bias.length, new float[width * bias.length], bias, TinyNeuralRuntime.Activation.LINEAR)); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; } fail("Expected residual rejection"); }
}
