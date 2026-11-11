import com.omnidev.workspace.domain.neural.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/** Synthetic numerical fixture only. These metrics do not measure real Omni requests or Jev. */
public final class DecisionTrainingSmoke {
    public static void main(String[] args) throws Exception {
        List<OfflineDecisionTrainer.Example> train = fixture("train", 320, 6, 10);
        List<OfflineDecisionTrainer.Example> development = fixture("development", 100, 6, 20);
        List<OfflineDecisionTrainer.Example> calibration = fixture("calibration", 100, 6, 30);
        List<OfflineDecisionTrainer.Example> holdout = fixture("holdout", 200, 6, 40);
        OfflineDecisionTrainer.requireDisjointGroups(train, development, calibration, holdout);
        rejects(() -> OfflineDecisionTrainer.requireDisjointGroups(train, train));
        OfflineDecisionTrainer.Model linear = OfflineDecisionTrainer.fit(train, 2, 0, 140, 100);
        OfflineDecisionTrainer.Model neural = OfflineDecisionTrainer.fit(train, 2, 8, 140, 100);
        rejects(() -> OfflineDecisionTrainer.calibrate(neural, train));
        double linearDevelopment = OfflineDecisionTrainer.logLoss(linear, development, 1);
        double neuralDevelopment = OfflineDecisionTrainer.logLoss(neural, development, 1);
        OfflineDecisionTrainer.Model selected = linearDevelopment <= neuralDevelopment ? linear : neural;
        double temperature = OfflineDecisionTrainer.calibrate(selected, calibration);
        // Read holdout only after architecture and temperature are fixed.
        double loss = OfflineDecisionTrainer.logLoss(selected, holdout, temperature);
        TinyDecisionModel decision = new TinyDecisionModel(selected.runtime(), new String[] {"irrelevant", "relevant"}, temperature);
        float[] probe = {0.9f, 0.7f, 0.1f, 0.2f, 0.1f, 0.4f};
        TinyDecisionModel.Decision result = decision.decide(probe, 0.6, 0.1);
        double probability = result.probabilitiesCopy()[1];
        double rankingLogit = selected.binaryRankingRuntime(temperature).infer(probe)[0];
        check(Math.abs(probability - 1 / (1 + Math.exp(-rankingLogit))) < 1e-6, "Binary scorer parity");
        check(loss < 0.25, "Fixture heldout learning");
        check(result.status == TinyDecisionModel.Status.CHOICE && "relevant".equals(result.choice), "Typed fixture prediction");
        result.probabilitiesCopy()[0] = 99;
        check(result.probabilitiesCopy()[0] <= 1, "Immutable result");
        TinyNeuralRuntime tie = new TinyNeuralRuntime(new TinyNeuralRuntime.Dense(1, 2,
                new float[] {0, 0}, new float[] {0, 0}, TinyNeuralRuntime.Activation.LINEAR));
        check(new TinyDecisionModel(tie, new String[] {"a", "b"}, 1).decide(new float[] {0}, 0, 0).status
                == TinyDecisionModel.Status.ABSTAIN, "Tied decisions abstain");
        rejects(() -> new TinyDecisionModel(tie, new String[] {"a", "a"}, 1));
        rejects(() -> decision.decide(probe, Double.NaN, 0));
        // Five-feature scorer exports also match NeuralEvidenceReranker's input shape.
        OfflineDecisionTrainer.Model evidence = OfflineDecisionTrainer.fit(fixture("evidence", 80, 5, 50), 2, 4, 40, 1);
        check(NeuralEvidenceReranker.rank("fixture", Collections.emptyList(), evidence.binaryRankingRuntime(1), 0.1).isEmpty(),
                "Evidence scorer contract");
        NeuralToolRanker.Candidate candidate = new NeuralToolRanker.Candidate("eligible_fixture", probe);
        check(NeuralToolRanker.proposals(Collections.singletonList(candidate), selected.binaryRankingRuntime(1))
                .get(0).candidate == candidate, "Tool scorer preserves original eligible candidate");
        OfflineDecisionTrainer.Model large = largeFixture();
        check(large.runtime().parameterCount() == 18528, "Supported large export shape");
        rejects(() -> large.javaSource(1, "injected.class"));
        if (args.length > 0 && "--verify".equals(args[0])) {
            Class<?> checkpoint = Class.forName("TrainedDecisionCheckpoint");
            TinyNeuralRuntime loaded = (TinyNeuralRuntime) checkpoint.getMethod("model").invoke(null);
            check(Arrays.equals(selected.runtime().infer(probe), loaded.infer(probe)), "Compiled checkpoint parity");
            check(checkpoint.getField("TEMPERATURE").getDouble(null) == temperature, "Calibration export parity");
            TinyNeuralRuntime loadedLarge = (TinyNeuralRuntime) Class.forName("TrainedLargeDecisionCheckpoint")
                    .getMethod("model").invoke(null);
            float[] largeProbe = new float[256]; Arrays.fill(largeProbe, 0.5f);
            check(Arrays.equals(large.runtime().infer(largeProbe), loadedLarge.infer(largeProbe)), "Large checkpoint parity");
        } else if (args.length > 0) {
            Files.write(Paths.get(args[0]), selected.javaSource(temperature).getBytes(StandardCharsets.UTF_8));
            Files.write(Paths.get(args[0]).resolveSibling("TrainedLargeDecisionCheckpoint.java"),
                    large.javaSource(1, "TrainedLargeDecisionCheckpoint").getBytes(StandardCharsets.UTF_8));
        }
        System.out.printf(Locale.ROOT, "{\"fixture_only\":true,\"training_examples\":320,\"development_examples\":100," +
                "\"calibration_examples\":100,\"holdout_examples\":200,\"linear_development_nll\":%.6f," +
                "\"neural_development_nll\":%.6f,\"selected\":\"%s\",\"temperature\":%.2f," +
                "\"holdout_nll\":%.6f,\"parameters\":%d,\"fp32_weight_bytes\":%d}%n",
                linearDevelopment, neuralDevelopment, selected == linear ? "linear" : "mlp8", temperature,
                loss, selected.runtime().parameterCount(), selected.runtime().parameterCount() * 4);
    }

    private static OfflineDecisionTrainer.Model largeFixture() {
        List<OfflineDecisionTrainer.Example> training = new ArrayList<>();
        Random random = new Random(300);
        for (int label = 0; label < 32; label++) {
            float[] features = new float[256];
            for (int i = 0; i < features.length; i++) features[i] = random.nextFloat();
            training.add(new OfflineDecisionTrainer.Example("large-" + label, features, label));
        }
        return OfflineDecisionTrainer.fit(training, 32, 64, 1, 10);
    }

    private static List<OfflineDecisionTrainer.Example> fixture(String split, int count, int width, long seed) {
        List<OfflineDecisionTrainer.Example> result = new ArrayList<>();
        Random random = new Random(seed);
        for (int n = 0; n < count; n++) {
            float[] features = new float[width];
            for (int i = 0; i < width; i++) features[i] = random.nextFloat();
            int label = features[0] + 0.5 * features[1] - features[2] > 0.3 ? 1 : 0;
            result.add(new OfflineDecisionTrainer.Example(split + "-" + n, features, label));
        }
        return result;
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void rejects(Runnable work) {
        try { work.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected contract rejection");
    }
}
