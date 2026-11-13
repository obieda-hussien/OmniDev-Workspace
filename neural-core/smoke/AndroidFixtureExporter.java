import com.omnidev.workspace.domain.neural.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Public regression observations for cross-runtime parity, never new accuracy evidence. */
public final class AndroidFixtureExporter {
    public static void main(String[] args) throws Exception {
        Path assets = Paths.get(args[0], "assets"); Files.createDirectories(assets);
        TinyNeuralRuntime domain = TrainedTextIntentCheckpoint.model(), refusal = TrainedRefusalGateCheckpoint.model();
        TextIntentModel baseline = new TextIntentModel(domain, TrainedTextIntentCheckpoint.labels(),
                TrainedTextIntentCheckpoint.TEMPERATURE, BilingualTextEncoder.VERSION);
        GatedTextIntentModel gated = new GatedTextIntentModel(domain, TrainedTextIntentCheckpoint.labels(),
                TrainedTextIntentCheckpoint.TEMPERATURE, refusal, TrainedRefusalGateCheckpoint.TEMPERATURE,
                TrainedRefusalGateCheckpoint.CUTOFF, BilingualTextEncoder.VERSION);
        List<String> texts = new ArrayList<>();
        addSplit(texts, "neural-core/fixtures/text-intents.tsv", "holdout");
        addSplit(texts, "neural-core/fixtures/clinc-subset/rejection.tsv", "test");
        List<String> challenge = Files.readAllLines(Paths.get("neural-core/fixtures/decision-challenges.tsv"), StandardCharsets.UTF_8);
        for (String line : challenge.subList(1, challenge.size())) texts.add(line.split("\t", -1)[4]);
        texts.add(""); texts.add("!!!"); texts.add("\u0640\u064e\u064f"); texts.add("\ud83d\ude00");
        StringBuilder rows = new StringBuilder("id\ttext\tfeature_sha256\tbase_status\tbase_choice\tgate_status\tgate_choice\tprobabilities\n");
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            TinyDecisionModel.Decision a = baseline.classify(text, 0.6, 0.15), b = gated.classify(text, 0.6, 0.15);
            rows.append(i).append('\t').append(text).append('\t').append(featureHash(BilingualTextEncoder.encode(text)))
                    .append('\t').append(a.status).append('\t').append(a.choice == null ? "-" : a.choice)
                    .append('\t').append(b.status).append('\t').append(b.choice == null ? "-" : b.choice).append('\t');
            double[] p = a.probabilitiesCopy();
            for (int j = 0; j < p.length; j++) { if (j > 0) rows.append(','); rows.append(p[j]); }
            rows.append('\n');
        }
        Files.write(assets.resolve("probes.tsv"), rows.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder manifest = new StringBuilder("Previously observed regression inputs. Not a fresh evaluation or production model.\n");
        for (String file : new String[] {"neural-core/fixtures/text-intents.tsv", "neural-core/fixtures/clinc-subset/rejection.tsv",
                "neural-core/fixtures/decision-challenges.tsv", "neural-core/fixtures/clinc-subset/provenance.json"}) {
            manifest.append(file).append(" sha256=").append(hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Paths.get(file))))).append('\n');
        }
        manifest.append("probe_count=").append(texts.size()).append("\nencoder=").append(BilingualTextEncoder.VERSION)
                .append("\nparameters=").append(domain.parameterCount() + refusal.parameterCount()).append('\n');
        Files.write(assets.resolve("provenance.txt"), manifest.toString().getBytes(StandardCharsets.UTF_8));
        for (String file : new String[] {"LICENSE", "NOTICE.md"}) {
            Files.copy(Paths.get("neural-core/fixtures/clinc-subset/" + file), assets.resolve("CLINC-" + file), StandardCopyOption.REPLACE_EXISTING);
        }
    }
    private static void addSplit(List<String> texts, String file, String split) throws Exception {
        for (String line : Files.readAllLines(Paths.get(file), StandardCharsets.UTF_8)) {
            String[] cells = line.split("\t", -1); if (cells[0].equals(split)) texts.add(cells[4]);
        }
    }
    public static String featureHash(float[] features) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (float f : features) { int bits = Float.floatToIntBits(f);
            digest.update((byte) (bits >>> 24)); digest.update((byte) (bits >>> 16)); digest.update((byte) (bits >>> 8)); digest.update((byte) bits); }
        return hex(digest.digest());
    }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255)); return out.toString();
    }
}
