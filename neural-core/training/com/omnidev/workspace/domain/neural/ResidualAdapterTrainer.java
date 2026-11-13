package com.omnidev.workspace.domain.neural;

import java.util.*;

/** Desktop-only bounded residual SGD; old true labels plus frozen-teacher KL anchoring. */
public final class ResidualAdapterTrainer {
    static final class Example {
        final String group, language; final int label; final boolean old;
        final float[] x; final double[] teacher;
        Example(String group, String language, int label, boolean old, float[] x, double[] teacher) {
            if (group == null || group.isEmpty() || group.length() > 160 || !Arrays.asList("ar", "en", "mixed").contains(language) || label < 0 || label >= 5 || x == null || x.length != 1024 || teacher == null || teacher.length != 5) throw new IllegalArgumentException("Invalid residual example");
            double sum = 0;
            for (float v : x) if (!Float.isFinite(v) || Math.abs(v) > 1) throw new IllegalArgumentException("Invalid residual features");
            for (double p : teacher) { if (!Double.isFinite(p) || p < 0 || p > 1) throw new IllegalArgumentException("Invalid teacher scores"); sum += p; }
            if (Math.abs(sum - 1) > 1e-8) throw new IllegalArgumentException("Invalid teacher distribution");
            this.group = group; this.language = language; this.label = label; this.old = old; this.x = x.clone(); this.teacher = teacher.clone();
        }
    }
    static final class Model {
        final float[] weights, bias;
        final int epoch, epochsRun; final double loss;
        Model(float[] weights, float[] bias, int epoch, int epochsRun, double loss) { this.weights = weights.clone(); this.bias = bias.clone(); this.epoch = epoch; this.epochsRun = epochsRun; this.loss = loss; }
        TinyNeuralRuntime runtime() { return new TinyNeuralRuntime(new TinyNeuralRuntime.Dense(1024, 5, weights, bias, TinyNeuralRuntime.Activation.TANH)); }
        String javaSource(double gain, String fingerprint) {
            StringBuilder out = new StringBuilder("import com.omnidev.workspace.domain.neural.*;\npublic final class TrainedResidualAdapter {\n")
                    .append("public static final double GAIN=").append(gain).append(";\npublic static final String VOCABULARY_SHA256=\"").append(fingerprint).append("\";\n")
                    .append("public static TinyNeuralRuntime model(){return new TinyNeuralRuntime(new TinyNeuralRuntime.Dense(1024,5,weights(),bias(),TinyNeuralRuntime.Activation.TANH));}\n");
            OfflineDecisionTrainer.Model.appendArrayFactory(out, "weights", weights); OfflineDecisionTrainer.Model.appendArrayFactory(out, "bias", bias); return out.append("}\n").toString();
        }
    }
    static Model fit(List<Example> train, List<Example> dev, double anchor, long seed) {
        if (train == null || train.isEmpty() || train.size() > 100_000 || dev == null || dev.isEmpty() || dev.size() > 10_000 || !Double.isFinite(anchor) || anchor <= 0 || anchor > 16) throw new IllegalArgumentException("Invalid residual fitting contract");
        Set<String> groups = new HashSet<>(); for (Example e : train) groups.add(e.group);
        for (Example e : dev) if (groups.contains(e.group)) throw new IllegalArgumentException("Residual group leakage");
        float[] weights = new float[5120], bias = new float[5]; Random random = new Random(seed);
        List<Example> order = new ArrayList<>(train); Model best = null; int epochsRun = 0;
        for (int epoch = 1; epoch <= 100; epoch++) {
            Collections.shuffle(order, random); double rate = 0.04 / (1 + 0.02 * (epoch - 1));
            for (Example e : order) {
                float[] correction = infer(weights, bias, e.x); double[] p = BoundedResidualDecisionModel.distribution(e.teacher, correction, 1);
                double strength = e.old ? anchor : 0;
                for (int c = 0; c < 5; c++) {
                    double target = c == e.label ? 1 : 0;
                    double gradient = ((p[c] - target) + strength * (p[c] - e.teacher[c])) * (1 - (double) correction[c] * correction[c]);
                    int offset = c * 1024;
                    for (int i = 0; i < 1024; i++) weights[offset + i] -= (float) (rate * (gradient * e.x[i] + 0.005 * weights[offset + i]));
                    bias[c] -= (float) (rate * gradient);
                }
            }
            epochsRun = epoch;
            if (epoch % 5 == 0) {
                Model candidate = new Model(weights, bias, epoch, epoch, 0); double loss = loss(candidate, dev, 1);
                if (!Double.isFinite(loss)) throw new IllegalStateException("Residual training became nonfinite");
                if (best == null || loss < best.loss) best = new Model(weights, bias, epoch, epoch, loss);
                if (epoch - best.epoch >= 20) break;
            }
        }
        return new Model(best.weights, best.bias, best.epoch, epochsRun, best.loss);
    }
    static double loss(Model model, List<Example> rows, double gain) {
        Map<String, double[]> strata = new TreeMap<>(); TinyNeuralRuntime runtime = model.runtime().withSparseInput();
        for (Example e : rows) { double[] p = BoundedResidualDecisionModel.distribution(e.teacher, runtime.infer(e.x), gain);
            double[] n = strata.computeIfAbsent(e.language + ":" + e.label, k -> new double[2]); n[0] -= Math.log(Math.max(1e-30, p[e.label])); n[1]++; }
        double sum = 0; for (double[] n : strata.values()) sum += n[0] / n[1]; return sum / strata.size();
    }
    private static float[] infer(float[] weights, float[] bias, float[] x) {
        float[] values = new float[5]; for (int c = 0; c < 5; c++) { double sum = bias[c]; for (int i = 0; i < 1024; i++) sum += (double) weights[c * 1024 + i] * x[i]; values[c] = (float) Math.tanh(sum); } return values;
    }
    private ResidualAdapterTrainer() { }
}
