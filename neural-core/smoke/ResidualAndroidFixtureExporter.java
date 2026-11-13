import com.omnidev.workspace.domain.neural.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Regression goldens exercise both the selected no-change policy and forced nonzero adapter arithmetic. */
public final class ResidualAndroidFixtureExporter {
    public static void main(String[] args) throws Exception {
        Path assets = Paths.get(args[0], "assets"); List<String> texts = new ArrayList<>();
        List<String> publicRows = Files.readAllLines(assets.resolve("public-probes.tsv"), StandardCharsets.UTF_8);
        for (String row : publicRows.subList(1, publicRows.size())) texts.add(row.split("\t", -1)[1]);
        List<String> fresh = Files.readAllLines(Paths.get("neural-core/fixtures/residual-adapter/fresh.tsv"), StandardCharsets.UTF_8);
        for (String row : fresh.subList(1, fresh.size())) texts.add(row.split("\t", -1)[5]);
        if (texts.size() != 859) throw new AssertionError("Residual Android probe count changed");
        RichTextEncoder encoder = TrainedRichVocabulary.encoder(); TinyNeuralRuntime residual = TrainedResidualAdapter.model();
        GatedTextIntentModel base = new GatedTextIntentModel(TrainedTextIntentCheckpoint.model(), TrainedTextIntentCheckpoint.labels(), TrainedTextIntentCheckpoint.TEMPERATURE,
                TrainedRefusalGateCheckpoint.model(), TrainedRefusalGateCheckpoint.TEMPERATURE, TrainedRefusalGateCheckpoint.CUTOFF, BilingualTextEncoder.VERSION);
        RichIntentRefusalModel teacher = new RichIntentRefusalModel(base, encoder, TrainedRichPresence.model(), TrainedRichVocabulary.THRESHOLD, TrainedRichVocabulary.REQUIRE_MATCH,
                RichTextEncoder.VERSION, TrainedPublicRefusal.VOCABULARY_SHA256, TrainedPublicRefusal.model(), TrainedPublicRefusal.CUTOFF);
        BoundedResidualDecisionModel selected = new BoundedResidualDecisionModel(teacher, encoder, residual, TrainedResidualAdapter.GAIN, TrainedResidualAdapter.VOCABULARY_SHA256),
                active = new BoundedResidualDecisionModel(teacher, encoder, residual, 1, TrainedResidualAdapter.VOCABULARY_SHA256);
        StringBuilder out = new StringBuilder("id\ttext\tfeature_sha256\tselected_status\tselected_choice\tselected_probabilities\tactive_status\tactive_choice\tactive_probabilities\tresidual\n");
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i); float[] features = encoder.encode(text);
            out.append(i).append('\t').append(text).append('\t').append(AndroidFixtureExporter.featureHash(features));
            append(out, selected.classify(text, 0.6, 0.15)); append(out, active.classify(text, 0.6, 0.15)); out.append('\t');
            float[] values = residual.infer(features); for (int j = 0; j < 5; j++) { if (j > 0) out.append(','); out.append(values[j]); } out.append('\n');
        }
        Files.write(assets.resolve("residual-probes.tsv"), out.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static void append(StringBuilder out, TinyDecisionModel.Decision d) {
        out.append('\t').append(d.status).append('\t').append(d.choice == null ? "-" : d.choice).append('\t');
        double[] p = d.probabilitiesCopy(); for (int i = 0; i < 5; i++) { if (i > 0) out.append(','); out.append(p[i]); }
    }
}
