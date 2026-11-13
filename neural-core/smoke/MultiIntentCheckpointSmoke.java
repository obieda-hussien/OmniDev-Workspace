import com.omnidev.workspace.domain.neural.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Reload every trained presence head and verify actual typed decisions against the fitting process. */
public final class MultiIntentCheckpointSmoke {
    public static void main(String[] args) throws Exception {
        if (!BilingualTextEncoder.VERSION.equals(TrainedMultiIntentManifest.ENCODER_VERSION) ||
                !BilingualRefusalCorpus.SHA256.equals(TrainedMultiIntentManifest.CORPUS_SHA256) ||
                !Arrays.equals(MultiIntentRefusalModel.labels(), TrainedMultiIntentManifest.labels())) throw new AssertionError("Presence manifest schema changed");
        List<String> metadata = Files.readAllLines(Paths.get(args[2]), StandardCharsets.UTF_8);
        List<String> actualMetadata = new ArrayList<>();
        actualMetadata.add("threshold\t" + TrainedMultiIntentManifest.THRESHOLD);
        actualMetadata.add("require_match\t" + TrainedMultiIntentManifest.REQUIRE_MATCH);
        String[] labels = TrainedMultiIntentManifest.labels(); double[] temperatures = TrainedMultiIntentManifest.temperatures();
        for (int i = 0; i < 5; i++) actualMetadata.add(labels[i] + "\t" + temperatures[i]);
        if (!metadata.equals(actualMetadata)) throw new AssertionError("Presence fitted metadata mismatch");
        GatedTextIntentModel prior = new GatedTextIntentModel(TrainedTextIntentCheckpoint.model().withSparseInput(), TrainedTextIntentCheckpoint.labels(),
                TrainedTextIntentCheckpoint.TEMPERATURE, TrainedRefusalGateCheckpoint.model().withSparseInput(), TrainedRefusalGateCheckpoint.TEMPERATURE,
                TrainedRefusalGateCheckpoint.CUTOFF, BilingualTextEncoder.VERSION);
        TinyNeuralRuntime[] dense = TrainedMultiIntentManifest.models(), sparse = new TinyNeuralRuntime[dense.length];
        for (int i = 0; i < dense.length; i++) sparse[i] = dense[i].withSparseInput();
        MultiIntentRefusalModel a = new MultiIntentRefusalModel(prior, dense, TrainedMultiIntentManifest.temperatures(),
                TrainedMultiIntentManifest.THRESHOLD, TrainedMultiIntentManifest.REQUIRE_MATCH, BilingualTextEncoder.VERSION);
        MultiIntentRefusalModel b = new MultiIntentRefusalModel(prior, sparse, TrainedMultiIntentManifest.temperatures(),
                TrainedMultiIntentManifest.THRESHOLD, TrainedMultiIntentManifest.REQUIRE_MATCH, BilingualTextEncoder.VERSION);
        List<BilingualRefusalCorpus.Row> rows = BilingualRefusalCorpus.load(Paths.get(args[0]));
        List<String> expected = Files.readAllLines(Paths.get(args[1]), StandardCharsets.UTF_8);
        if (expected.size() != rows.size() + 1 || !expected.get(0).equals("status\tchoice")) throw new AssertionError("Presence observation bounds mismatch");
        for (int i = 0; i < rows.size(); i++) {
            String text = rows.get(i).text; TinyDecisionModel.Decision x = a.classify(text, 0.6, 0.15), y = b.classify(text, 0.6, 0.15), before = prior.classify(text, 0.6, 0.15);
            if (!(x.status + "\t" + (x.choice == null ? "-" : x.choice)).equals(expected.get(i + 1))) throw new AssertionError("Exported presence decision mismatch");
            if (x.status != y.status || !Objects.equals(x.choice, y.choice) || !Arrays.equals(x.probabilitiesCopy(), y.probabilitiesCopy())) throw new AssertionError("Presence dense/sparse mismatch");
            if (!Arrays.equals(before.probabilitiesCopy(), x.probabilitiesCopy()) || x.status == TinyDecisionModel.Status.CHOICE && !Objects.equals(before.choice, x.choice)) throw new AssertionError("Presence gate changed proposal");
            if (before.status == TinyDecisionModel.Status.ABSTAIN && x.status != TinyDecisionModel.Status.ABSTAIN) throw new AssertionError("Presence gate upgraded a refusal");
        }
        System.out.println("Multi-intent compiled checkpoint/dense-sparse/refusal-only parity passed on " + rows.size() + " observations.");
    }
}
