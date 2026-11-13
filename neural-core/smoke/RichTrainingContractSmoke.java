package com.omnidev.workspace.domain.neural;

import java.util.*;

/** Run wider-feature and fused-layer contracts using real training classes. */
public final class RichTrainingContractSmoke {
    public static void main(String[] args) {
        List<OfflineDecisionTrainer.Example> examples = new ArrayList<>();
        for (int i = 0; i < 12; i++) { float[] x = new float[1024]; x[i] = 1;
            examples.add(new OfflineDecisionTrainer.Example("wide-" + i, x, i % 2)); }
        OfflineDecisionTrainer.Model[] models = new OfflineDecisionTrainer.Model[5]; double[] temperatures = {0.5, 0.75, 1, 2, 3};
        for (int i = 0; i < 5; i++) models[i] = OfflineDecisionTrainer.fit(examples, 2, 0, 5, i);
        TinyNeuralRuntime fusion = LinearPresenceFusion.fuse(models, temperatures).runtime();
        float[] probe = new float[1024]; probe[1] = 0.7f; probe[5] = -0.3f;
        float[] scores = fusion.infer(probe);
        for (int i = 0; i < 5; i++) {
            double expected = new TinyDecisionModel(models[i].runtime(), new String[] {"a", "b"}, temperatures[i]).decide(probe, 0, 0).probabilitiesCopy()[1];
            if (Math.abs(expected - scores[i]) > 2e-6) throw new AssertionError("Fused calibration mismatch");
        }
        rejects(() -> new OfflineDecisionTrainer.Example("oversized", new float[1025], 0));
        rejects(() -> OfflineDecisionTrainer.fit(examples, 2, 64, 1, 42));
        rejects(() -> models[0].binaryLinearLayer(0));
        rejects(() -> LinearPresenceFusion.fuse(new OfflineDecisionTrainer.Model[4], new double[4]));
        System.out.println("Wide training and calibrated fusion contracts passed; legacy parameter envelope remains bounded.");
    }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("Unbounded training accepted"); }
}
