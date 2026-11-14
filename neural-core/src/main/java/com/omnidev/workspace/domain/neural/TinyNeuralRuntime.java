package com.omnidev.workspace.domain.neural;

import java.util.Arrays;

/** Immutable, dependency-free inference for bounded fully connected neural networks. */
public final class TinyNeuralRuntime {
    public enum Activation { LINEAR, RELU, TANH, SIGMOID }
    public static final int MAX_PARAMETERS = 100_000;

    public static final class Dense {
        private final int inputs, outputs;
        private final float[] weights, bias;
        private final Activation activation;

        public Dense(int inputs, int outputs, float[] weights, float[] bias, Activation activation) {
            if (inputs < 1 || outputs < 1 || inputs > 4096 || outputs > 4096 ||
                    (long) inputs * outputs + outputs > MAX_PARAMETERS ||
                    weights == null || bias == null || activation == null ||
                    weights.length != (long) inputs * outputs || bias.length != outputs) {
                throw new IllegalArgumentException("Invalid dense layer dimensions or parameter count");
            }
            requireFinite(weights);
            requireFinite(bias);
            this.inputs = inputs;
            this.outputs = outputs;
            this.weights = weights.clone();
            this.bias = bias.clone();
            this.activation = activation;
        }

        public int inputSize() { return inputs; }
        public int outputSize() { return outputs; }
        public int parameterCount() { return weights.length + bias.length; }
        public float[] weightsCopy() { return weights.clone(); }
        public float[] biasCopy() { return bias.clone(); }

        private float[] forward(float[] input) {
            return forward(input, null);
        }

        private float[] forward(float[] input, int[] nonzero) {
            float[] result = new float[outputs];
            for (int output = 0; output < outputs; output++) {
                double sum = bias[output];
                int offset = output * inputs;
                if (nonzero == null) {
                    for (int i = 0; i < inputs; i++) sum += (double) weights[offset + i] * input[i];
                } else {
                    for (int i : nonzero) sum += (double) weights[offset + i] * input[i];
                    // Preserve signed-zero behavior as well as numerical results.
                    if (sum == 0) {
                        sum = bias[output];
                        for (int i = 0; i < inputs; i++) sum += (double) weights[offset + i] * input[i];
                    }
                }
                if (!Double.isFinite(sum)) throw new IllegalArgumentException("Non-finite activation");
                double activated;
                switch (activation) {
                    case RELU: activated = Math.max(0, sum); break;
                    case TANH: activated = Math.tanh(sum); break;
                    case SIGMOID: activated = sum >= 0 ? 1.0 / (1.0 + Math.exp(-sum)) :
                            Math.exp(sum) / (1.0 + Math.exp(sum)); break;
                    default: activated = sum;
                }
                if (activated > Float.MAX_VALUE || activated < -Float.MAX_VALUE) {
                    throw new IllegalArgumentException("Activation overflow");
                }
                result[output] = (float) activated;
            }
            return result;
        }
    }

    private final Dense[] layers;
    private final int parameterCount;
    private final boolean sparseInput;

    public TinyNeuralRuntime(Dense... layers) {
        this(false, layers);
    }

    private TinyNeuralRuntime(boolean sparseInput, Dense... layers) {
        if (layers == null || layers.length == 0 || layers.length > 8) {
            throw new IllegalArgumentException("Network must have 1-8 layers");
        }
        this.layers = layers.clone();
        long total = 0;
        for (int i = 0; i < this.layers.length; i++) {
            if (this.layers[i] == null || (i > 0 && this.layers[i - 1].outputs != this.layers[i].inputs)) {
                throw new IllegalArgumentException("Disconnected layers");
            }
            total += this.layers[i].parameterCount();
        }
        if (total > MAX_PARAMETERS) throw new IllegalArgumentException("Too many parameters");
        this.parameterCount = (int) total;
        this.sparseInput = sparseInput;
    }

    /** Opt-in first-layer optimization. Dense immutable weights are safely shared. */
    public TinyNeuralRuntime withSparseInput() { return new TinyNeuralRuntime(true, layers); }

    public int parameterCount() { return parameterCount; }
    String checkpointFingerprint() {
        CheckpointFingerprint hash=new CheckpointFingerprint("tiny-dense-fp32-v1").integer(layers.length);
        for(Dense layer:layers)hash.integer(layer.inputs).integer(layer.outputs).text(layer.activation.name()).floats(layer.weights).floats(layer.bias);
        return hash.finish();
    }

    public int inputSize() { return layers[0].inputSize(); }
    public int outputSize() { return layers[layers.length - 1].outputSize(); }

    public float[] infer(float[] features) {
        if (features == null || features.length != inputSize()) {
            throw new IllegalArgumentException("Invalid input vector");
        }
        requireFinite(features);
        float[] current = Arrays.copyOf(features, features.length);
        int[] nonzero = null;
        if (sparseInput && features.length >= 64) {
            int count = 0;
            for (float value : current) if (value != 0) count++;
            if (count <= features.length / 2) {
                nonzero = new int[count];
                int position = 0;
                for (int i = 0; i < current.length; i++) if (current[i] != 0) nonzero[position++] = i;
            }
        }
        current = layers[0].forward(current, nonzero);
        for (int i = 1; i < layers.length; i++) current = layers[i].forward(current);
        return current;
    }

    private static void requireFinite(float[] values) {
        for (float value : values) if (Float.isNaN(value) || Float.isInfinite(value)) {
            throw new IllegalArgumentException("Non-finite value");
        }
    }
}
