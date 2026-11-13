package com.omnidev.workspace.domain.neural;

/** Export five calibrated binary linear heads as one bounded sigmoid matrix. */
public final class LinearPresenceFusion {
    public static final class Result {
        private final float[] weights, bias;
        private Result(float[] weights, float[] bias) { this.weights = weights.clone(); this.bias = bias.clone(); }
        public TinyNeuralRuntime runtime() { return new TinyNeuralRuntime(new TinyNeuralRuntime.Dense(1024, 5, weights, bias, TinyNeuralRuntime.Activation.SIGMOID)); }
        public String javaSource(String vocabularyHash) {
            if (vocabularyHash == null || !vocabularyHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid vocabulary hash");
            StringBuilder out = new StringBuilder("import com.omnidev.workspace.domain.neural.*;\npublic final class TrainedRichPresence {\n")
                    .append("public static final String VOCABULARY_SHA256=\"").append(vocabularyHash).append("\";\n")
                    .append("public static TinyNeuralRuntime model(){return new TinyNeuralRuntime(new TinyNeuralRuntime.Dense(1024,5,weights(),bias(),TinyNeuralRuntime.Activation.SIGMOID));}\n");
            OfflineDecisionTrainer.Model.appendArrayFactory(out, "weights", weights);
            OfflineDecisionTrainer.Model.appendArrayFactory(out, "bias", bias);
            return out.append("}\n").toString();
        }
    }
    public static Result fuse(OfflineDecisionTrainer.Model[] heads, double[] temperatures) {
        if (heads == null || temperatures == null || heads.length != 5 || temperatures.length != 5) throw new IllegalArgumentException("Expected five calibrated heads");
        float[] weights = new float[5120], bias = new float[5];
        for (int i = 0; i < 5; i++) {
            if (heads[i] == null) throw new IllegalArgumentException("Missing head");
            TinyNeuralRuntime.Dense layer = heads[i].binaryLinearLayer(temperatures[i]);
            if (layer.inputSize() != 1024) throw new IllegalArgumentException("Rich feature schema mismatch");
            System.arraycopy(layer.weightsCopy(), 0, weights, i * 1024, 1024); bias[i] = layer.biasCopy()[0];
        }
        return new Result(weights, bias);
    }
    private LinearPresenceFusion() { }
}
