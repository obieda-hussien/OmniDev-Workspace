package com.omnidev.workspace.domain.neural;

import java.util.*;
import com.omnidev.workspace.domain.neural.ResidualAdapterTrainer.Example;
import com.omnidev.workspace.domain.neural.ResidualAdapterTrainer.Model;

/** Exercise real bounded SGD, immutable snapshots and group exclusion on a learnable numerical control. */
public final class ResidualTrainingContractSmoke {
    public static void main(String[] args) {
        List<Example> train = new ArrayList<>(), dev = new ArrayList<>();
        for (int i = 0; i < 20; i++) train.add(example("train-" + i, i % 2));
        dev.add(example("dev-a", 0)); dev.add(example("dev-b", 1));
        Model a = ResidualAdapterTrainer.fit(train, dev, 1, 42), b = ResidualAdapterTrainer.fit(train, dev, 1, 42);
        if (!Arrays.equals(a.weights, b.weights) || !Arrays.equals(a.bias, b.bias) || a.epoch != b.epoch || a.epochsRun > 100 || a.epochsRun < a.epoch) throw new AssertionError("Residual determinism/snapshot contract changed");
        if (Math.abs(ResidualAdapterTrainer.loss(a, dev, 1) - a.loss) > 1e-9) throw new AssertionError("Residual snapshot validation drift");
        float[] source = new float[1024]; source[0] = 1; double[] teacher = {0.3, 0.4, 0.1, 0.1, 0.1};
        Example copied = new Example("copied", "en", 0, false, source, teacher); source[0] = 0; teacher[0] = 0;
        if (copied.x[0] != 1 || copied.teacher[0] != 0.3) throw new AssertionError("Residual examples expose mutable input");
        Model zero = new Model(new float[5120], new float[5], 0, 0, 0);
        if (!(a.loss < ResidualAdapterTrainer.loss(zero, dev, 1))) throw new AssertionError("Residual SGD did not learn control");
        List<Example> leaked = new ArrayList<>(dev); leaked.add(example("train-0", 0));
        try { ResidualAdapterTrainer.fit(train, leaked, 1, 42); throw new AssertionError("Leaked residual group accepted"); } catch (IllegalArgumentException expected) { }
        try { ResidualAdapterTrainer.fit(train, dev, Double.NaN, 42); throw new AssertionError("Nonfinite anchor accepted"); } catch (IllegalArgumentException expected) { }
        for (float value : a.runtime().infer(dev.get(0).x)) if (!Float.isFinite(value) || Math.abs(value) > 1) throw new AssertionError("Residual bound changed");
        System.out.println("Residual real-SGD/control/snapshot/determinism/leakage/bounds contracts passed.");
    }
    private static Example example(String group, int label) { float[] x = new float[1024]; x[0] = label == 0 ? 1 : -1; return new Example(group, "en", label, false, x, new double[] {0.3, 0.4, 0.1, 0.1, 0.1}); }
}
