package com.omnidev.workspace.domain.neural;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Offline-only gate for proposed direct weight edits. Does not mutate or deploy weights. */
public final class WeightEditGate {
    private WeightEditGate() { }

    public static final class Example {
        public final float[] input, expected;
        public Example(float[] input, float[] expected) {
            if (input == null || expected == null || input.length == 0 || expected.length == 0) {
                throw new IllegalArgumentException("Empty example");
            }
            this.input = input.clone();
            this.expected = expected.clone();
            for (float x : this.input) if (!Float.isFinite(x)) throw new IllegalArgumentException("Non-finite input");
            for (float y : this.expected) if (!Float.isFinite(y)) throw new IllegalArgumentException("Non-finite target");
        }
    }

    public static final class Result {
        public final boolean accepted;
        public final double baselineDevMse, candidateDevMse, baselineGateMse, candidateGateMse;
        public final String reason;
        public final TinyNeuralRuntime selected;

        private Result(boolean accepted, double baselineDevMse, double candidateDevMse,
                       double baselineGateMse, double candidateGateMse, String reason,
                       TinyNeuralRuntime selected) {
            this.accepted = accepted;
            this.baselineDevMse = baselineDevMse;
            this.candidateDevMse = candidateDevMse;
            this.baselineGateMse = baselineGateMse;
            this.candidateGateMse = candidateGateMse;
            this.reason = reason;
            this.selected = selected;
        }
    }

    public static Result evaluate(TinyNeuralRuntime original, TinyNeuralRuntime proposed,
                                  List<Example> development, List<Example> holdout,
                                  double minimumImprovement, double maximumSampleRegression) {
        if (original == null || proposed == null || development == null || holdout == null ||
                development.size() < 2 || holdout.size() < 2 ||
                !Double.isFinite(minimumImprovement) || minimumImprovement < 0 ||
                !Double.isFinite(maximumSampleRegression) || maximumSampleRegression < 0 ||
                original.parameterCount() != proposed.parameterCount() ||
                original.inputSize() != proposed.inputSize() || original.outputSize() != proposed.outputSize()) {
            throw new IllegalArgumentException("Incompatible model, data or gate policy");
        }
        Set<String> seen = new HashSet<>();
        for (Example example : development) {
            ensureShape(example, original);
            seen.add(fingerprint(example));
        }
        for (Example example : holdout) {
            ensureShape(example, original);
            if (seen.contains(fingerprint(example))) {
                throw new IllegalArgumentException("Development/holdout leakage");
            }
        }
        double baselineDev = mse(original, development);
        double candidateDev = mse(proposed, development);
        double baselineHoldout = mse(original, holdout);
        double candidateHoldout = mse(proposed, holdout);
        boolean noLargeRegression = true;
        for (Example sample : holdout) {
            if (sampleError(proposed, sample) > sampleError(original, sample) + maximumSampleRegression) {
                noLargeRegression = false;
                break;
            }
        }
        boolean accepted = baselineDev - candidateDev > minimumImprovement &&
                baselineHoldout - candidateHoldout > minimumImprovement && noLargeRegression;
        return new Result(accepted, baselineDev, candidateDev, baselineHoldout, candidateHoldout,
                accepted ? "Improved on both splits without excessive per-sample regression" :
                        "Edit rejected; original retained", accepted ? proposed : original);
    }

    private static void ensureShape(Example e, TinyNeuralRuntime m) {
        if (e == null || e.input.length != m.inputSize() || e.expected.length != m.outputSize()) {
            throw new IllegalArgumentException("Example shape mismatch");
        }
    }

    private static String fingerprint(Example e) {
        StringBuilder b = new StringBuilder();
        for (float n : e.input) b.append(Integer.toHexString(Float.floatToIntBits(n))).append(':');
        b.append('|');
        for (float n : e.expected) b.append(Integer.toHexString(Float.floatToIntBits(n))).append(':');
        return b.toString();
    }

    private static double mse(TinyNeuralRuntime m, List<Example> samples) {
        double error = 0;
        for (Example e : samples) error += sampleError(m, e);
        double result = error / samples.size();
        if (!Double.isFinite(result)) throw new IllegalArgumentException("Non-finite evaluation");
        return result;
    }

    private static double sampleError(TinyNeuralRuntime m, Example e) {
        float[] predicted = m.infer(e.input);
        double total = 0;
        for (int i = 0; i < predicted.length; i++) {
            double delta = (double) predicted[i] - e.expected[i];
            total += delta * delta;
        }
        double result = total / predicted.length;
        if (!Double.isFinite(result)) throw new IllegalArgumentException("Non-finite evaluation");
        return result;
    }
}