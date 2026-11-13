package com.omnidev.workspace.domain.neural;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Desktop-only supervised training. Not part of the Android dependency or runtime. */
public final class OfflineDecisionTrainer {
    public static final int MAX_TRAINED_PARAMETERS = 20_000;
    private OfflineDecisionTrainer() { }

    public static final class Example {
        public final String group;
        public final int label;
        private final float[] features;

        public Example(String group, float[] features, int label) {
            if (group == null || group.isEmpty() || group.length() > 160 || features == null ||
                    features.length < 1 || features.length > 1024 || label < 0 || label >= 32) {
                throw new IllegalArgumentException("Invalid training example");
            }
            for (float value : features) if (!Float.isFinite(value) || Math.abs(value) > 1) {
                throw new IllegalArgumentException("Training features must be normalized to [-1,1]");
            }
            this.group = group;
            this.features = features.clone();
            this.label = label;
        }
    }

    public static final class Model {
        private final int inputs, hidden, classes;
        private final float[] first, firstBias, last, lastBias;
        private final Set<String> trainingGroups;

        private Model(int inputs, int hidden, int classes, float[] first, float[] firstBias,
                      float[] last, float[] lastBias, List<Example> training) {
            this.inputs = inputs; this.hidden = hidden; this.classes = classes;
            this.first = first.clone(); this.firstBias = firstBias.clone();
            this.last = last.clone(); this.lastBias = lastBias.clone();
            this.trainingGroups = new HashSet<>();
            for (Example example : training) trainingGroups.add(example.group);
        }

        public TinyNeuralRuntime runtime() {
            TinyNeuralRuntime.Dense output = new TinyNeuralRuntime.Dense(hidden == 0 ? inputs : hidden,
                    classes, last, lastBias, TinyNeuralRuntime.Activation.LINEAR);
            if (hidden == 0) return new TinyNeuralRuntime(output);
            return new TinyNeuralRuntime(new TinyNeuralRuntime.Dense(inputs, hidden, first, firstBias,
                    TinyNeuralRuntime.Activation.TANH), output);
        }

        /** A binary log-odds head usable by the existing tool/evidence rerankers. */
        public TinyNeuralRuntime binaryRankingRuntime(double temperature) {
            if (classes != 2 || !Double.isFinite(temperature) || temperature <= 0) {
                throw new IllegalArgumentException("Binary ranking requires two classes and a positive temperature");
            }
            int width = hidden == 0 ? inputs : hidden;
            float[] difference = new float[width];
            for (int i = 0; i < width; i++) difference[i] = (float) ((last[width + i] - last[i]) / temperature);
            float[] bias = {(float) ((lastBias[1] - lastBias[0]) / temperature)};
            TinyNeuralRuntime.Dense output = new TinyNeuralRuntime.Dense(width, 1, difference, bias,
                    TinyNeuralRuntime.Activation.LINEAR);
            return hidden == 0 ? new TinyNeuralRuntime(output) : new TinyNeuralRuntime(
                    new TinyNeuralRuntime.Dense(inputs, hidden, first, firstBias, TinyNeuralRuntime.Activation.TANH), output);
        }

        public TinyNeuralRuntime.Dense binaryLinearLayer(double temperature) {
            if (hidden != 0 || classes != 2 || !Double.isFinite(temperature) || temperature <= 0) throw new IllegalArgumentException("Expected a linear binary head");
            float[] weights = new float[inputs];
            for (int i = 0; i < inputs; i++) weights[i] = (float) (((double) last[inputs + i] - last[i]) / temperature);
            float[] bias = {(float) (((double) lastBias[1] - lastBias[0]) / temperature)};
            return new TinyNeuralRuntime.Dense(inputs, 1, weights, bias, TinyNeuralRuntime.Activation.LINEAR);
        }

        /** Portable Java checkpoint; no Python, native library or training code is needed to load it. */
        public String javaSource(double temperature) {
            return javaSource(temperature, "TrainedDecisionCheckpoint");
        }

        public String javaSource(double temperature, String className) {
            if (!Double.isFinite(temperature) || temperature <= 0) throw new IllegalArgumentException("Invalid temperature");
            if (className == null || !className.matches("Trained[A-Za-z0-9_]{0,56}")) {
                throw new IllegalArgumentException("Invalid checkpoint class name");
            }
            StringBuilder out = new StringBuilder("import com.omnidev.workspace.domain.neural.TinyNeuralRuntime;\n")
                    .append("public final class ").append(className).append(" {\n")
                    .append("  public static final double TEMPERATURE = ").append(temperature).append(";\n")
                    .append("  public static TinyNeuralRuntime model() { return new TinyNeuralRuntime(\n");
            if (hidden > 0) appendLayer(out, inputs, hidden, "firstWeights", "firstBias", "TANH").append(",\n");
            appendLayer(out, hidden == 0 ? inputs : hidden, classes, "lastWeights", "lastBias", "LINEAR");
            out.append("); }\n");
            if (hidden > 0) { appendArrayFactory(out, "firstWeights", first); appendArrayFactory(out, "firstBias", firstBias); }
            appendArrayFactory(out, "lastWeights", last); appendArrayFactory(out, "lastBias", lastBias);
            return out.append("}\n").toString();
        }

        private static StringBuilder appendLayer(StringBuilder out, int inputs, int outputs,
                                                  String weights, String bias, String activation) {
            out.append("    new TinyNeuralRuntime.Dense(").append(inputs).append(',').append(outputs).append(',');
            out.append(weights).append("(),").append(bias).append("()");
            return out.append(",TinyNeuralRuntime.Activation.").append(activation).append(')');
        }

        static void appendArrayFactory(StringBuilder out, String name, float[] values) {
            final int chunkSize = 256;
            out.append("  private static float[] ").append(name).append("() { float[] values = new float[")
                    .append(values.length).append("];\n");
            for (int start = 0; start < values.length; start += chunkSize) {
                out.append("    ").append(name).append(start / chunkSize).append("(values);\n");
            }
            out.append("    return values; }\n");
            for (int start = 0; start < values.length; start += chunkSize) {
                out.append("  private static void ").append(name).append(start / chunkSize).append("(float[] values) {\n");
                for (int i = start; i < Math.min(values.length, start + chunkSize); i++) {
                    out.append("    values[").append(i).append("] = ").append(values[i]).append("f;\n");
                }
                out.append("  }\n");
            }
        }
    }

    public static final class TrainingResult {
        public final Model model;
        public final int bestEpoch, epochsRun;
        public final double validationLoss;
        private TrainingResult(Model model, int bestEpoch, int epochsRun, double validationLoss) {
            this.model = model; this.bestEpoch = bestEpoch; this.epochsRun = epochsRun; this.validationLoss = validationLoss;
        }
    }

    public static Model fit(List<Example> training, int classes, int hidden, int epochs, long seed) {
        return fitInternal(training, classes, hidden, epochs, seed, null).model;
    }

    /** Check every five epochs, retain best macro-stratum NLL, stop after 25 epochs without improvement. */
    public static TrainingResult fitEarlyStopped(List<Example> training, int classes, int hidden, int epochs,
                                                  long seed, List<List<Example>> validationStrata) {
        validate(training, classes);
        if (validationStrata == null || validationStrata.isEmpty() || validationStrata.size() > 64) {
            throw new IllegalArgumentException("Invalid bounded validation strata");
        }
        List<List<Example>> snapshot = new ArrayList<>(); List<Example> combined = new ArrayList<>();
        for (List<Example> stratum : validationStrata) {
            validate(stratum, classes);
            for (Example example : stratum) if (example.features.length != training.get(0).features.length) {
                throw new IllegalArgumentException("Validation feature schema mismatch");
            }
            if (combined.size() + stratum.size() > 100_000) throw new IllegalArgumentException("Validation bounds exceeded");
            snapshot.add(new ArrayList<>(stratum)); combined.addAll(stratum);
        }
        requireDisjointGroups(training, combined);
        return fitInternal(training, classes, hidden, epochs, seed, snapshot);
    }

    private static TrainingResult fitInternal(List<Example> training, int classes, int hidden, int epochs,
                                              long seed, List<List<Example>> validationStrata) {
        validate(training, classes);
        int inputs = training.get(0).features.length;
        long parameterCount = hidden == 0 ? (long) inputs * classes + classes : (long) inputs * hidden + hidden + (long) hidden * classes + classes;
        if (hidden < 0 || hidden > 64 || epochs < 1 || epochs > 1000 || parameterCount > MAX_TRAINED_PARAMETERS) {
            throw new IllegalArgumentException("Invalid bounded training configuration");
        }
        boolean[] present = new boolean[classes];
        for (Example example : training) present[example.label] = true;
        for (boolean seen : present) if (!seen) throw new IllegalArgumentException("Missing training class");
        Random random = new Random(seed);
        int width = hidden == 0 ? inputs : hidden;
        float[] first = new float[inputs * hidden], firstBias = new float[hidden];
        float[] last = new float[width * classes], lastBias = new float[classes];
        initialize(first, random, inputs); initialize(last, random, width);
        List<Example> order = new ArrayList<>(training);
        Model best = null; double bestLoss = Double.POSITIVE_INFINITY; int bestEpoch = 0, epochsRun = 0;
        for (int epoch = 0; epoch < epochs; epoch++) {
            Collections.shuffle(order, random);
            double rate = 0.06 / (1 + epoch * 0.015);
            for (Example example : order) {
                double[] activation = new double[width];
                for (int h = 0; h < width; h++) {
                    if (hidden == 0) { activation[h] = example.features[h]; continue; }
                    double sum = firstBias[h];
                    for (int i = 0; i < inputs; i++) sum += first[h * inputs + i] * example.features[i];
                    activation[h] = Math.tanh(sum);
                }
                double[] errors = new double[classes];
                double max = -Double.MAX_VALUE;
                for (int c = 0; c < classes; c++) {
                    double logit = lastBias[c];
                    for (int h = 0; h < width; h++) logit += last[c * width + h] * activation[h];
                    errors[c] = logit; max = Math.max(max, logit);
                }
                double sum = 0;
                for (int c = 0; c < classes; c++) { errors[c] = Math.exp(errors[c] - max); sum += errors[c]; }
                for (int c = 0; c < classes; c++) errors[c] = errors[c] / sum - (c == example.label ? 1 : 0);
                double[] hiddenErrors = new double[hidden];
                for (int h = 0; h < hidden; h++) {
                    for (int c = 0; c < classes; c++) hiddenErrors[h] += errors[c] * last[c * width + h];
                    hiddenErrors[h] *= 1 - activation[h] * activation[h];
                }
                for (int c = 0; c < classes; c++) {
                    lastBias[c] -= rate * errors[c];
                    for (int h = 0; h < width; h++) {
                        int index = c * width + h;
                        last[index] -= rate * (errors[c] * activation[h] + 0.0001 * last[index]);
                    }
                }
                for (int h = 0; h < hidden; h++) {
                    firstBias[h] -= rate * hiddenErrors[h];
                    for (int i = 0; i < inputs; i++) {
                        int index = h * inputs + i;
                        first[index] -= rate * (hiddenErrors[h] * example.features[i] + 0.0001 * first[index]);
                    }
                }
            }
            epochsRun = epoch + 1;
            if (validationStrata != null && (epochsRun % 5 == 0 || epochsRun == epochs)) {
                Model checkpoint = new Model(inputs, hidden, classes, first, firstBias, last, lastBias, training);
                double loss = 0;
                for (List<Example> stratum : validationStrata) loss += logLoss(checkpoint, stratum, 1);
                loss /= validationStrata.size();
                if (loss < bestLoss) { best = checkpoint; bestLoss = loss; bestEpoch = epochsRun; }
                if (epochsRun - bestEpoch >= 25) break;
            }
        }
        Model result = best == null ? new Model(inputs, hidden, classes, first, firstBias, last, lastBias, training) : best;
        result.runtime(); // Validate exported shapes and finite learned parameters.
        return new TrainingResult(result, validationStrata == null ? epochsRun : bestEpoch, epochsRun, bestLoss);
    }

    /** Select a temperature using calibration data only; holdout is not accepted here. */
    public static double calibrate(Model model, List<Example> calibration) {
        validate(calibration, model.classes);
        for (Example example : calibration) if (model.trainingGroups.contains(example.group)) {
            throw new IllegalArgumentException("Calibration overlaps training groups");
        }
        double best = 1, bestLoss = Double.POSITIVE_INFINITY;
        for (double temperature : new double[] {0.5, 0.75, 1, 1.5, 2, 3, 5}) {
            double loss = logLoss(model, calibration, temperature);
            if (loss < bestLoss) { bestLoss = loss; best = temperature; }
        }
        return best;
    }

    public static double logLoss(Model model, List<Example> examples, double temperature) {
        validate(examples, model.classes);
        String[] labels = new String[model.classes];
        for (int c = 0; c < labels.length; c++) labels[c] = Integer.toString(c);
        TinyDecisionModel decision = new TinyDecisionModel(model.runtime(), labels, temperature);
        double total = 0;
        for (Example example : examples) {
            if (example.features.length != model.inputs) throw new IllegalArgumentException("Inconsistent feature schema");
            double probability = decision.decide(example.features, 0, 0).probabilitiesCopy()[example.label];
            total -= Math.log(Math.max(1e-15, probability));
        }
        return total / examples.size();
    }

    /** Reject paraphrases/scenarios shared across train/development/calibration/holdout. */
    @SafeVarargs public static void requireDisjointGroups(List<Example>... splits) {
        if (splits == null || splits.length < 2) throw new IllegalArgumentException("At least two splits required");
        Set<String> seen = new HashSet<>();
        for (List<Example> split : splits) {
            if (split == null || split.isEmpty()) throw new IllegalArgumentException("Empty split");
            Set<String> local = new HashSet<>();
            for (Example example : split) {
                if (example == null) throw new IllegalArgumentException("Null example");
                local.add(example.group);
            }
            for (String group : local) if (!seen.add(group)) throw new IllegalArgumentException("Group leakage between splits");
        }
    }

    private static void validate(List<Example> examples, int classes) {
        if (examples == null || examples.isEmpty() || examples.size() > 100_000 || classes < 2 || classes > 32) {
            throw new IllegalArgumentException("Invalid bounded dataset");
        }
        if (examples.get(0) == null) throw new IllegalArgumentException("Null example");
        int width = examples.get(0).features.length;
        for (Example example : examples) if (example == null || example.features.length != width || example.label >= classes) {
            throw new IllegalArgumentException("Inconsistent example contract");
        }
    }

    private static void initialize(float[] weights, Random random, int inputs) {
        for (int i = 0; i < weights.length; i++) weights[i] = (float) ((random.nextDouble() * 2 - 1) / Math.sqrt(inputs));
    }
}
