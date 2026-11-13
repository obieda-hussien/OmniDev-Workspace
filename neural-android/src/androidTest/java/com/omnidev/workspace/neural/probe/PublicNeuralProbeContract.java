package com.omnidev.workspace.neural.probe;

import com.omnidev.workspace.domain.neural.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Host/ART public-data checkpoint parity. Passing this contract is not a model-quality gate. */
public final class PublicNeuralProbeContract {
    public static final int EXPECTED_PROBES = 699;
    public static int verify(Reader source) throws Exception {
        BufferedReader reader = new BufferedReader(source);
        require("id\ttext\tfeature_sha256\trefusal_score\tgate_status\tgate_choice\tdecision_status\tdecision_choice\tdecision_probabilities".equals(reader.readLine()), "Invalid public probe header");
        RichTextEncoder encoder = TrainedRichVocabulary.encoder();
        require(encoder.fingerprint().equals(TrainedPublicRefusal.VOCABULARY_SHA256) && encoder.fingerprint().equals(TrainedPublicDecision.VOCABULARY_SHA256), "Foreign public vocabulary");
        TinyNeuralRuntime dense = TrainedPublicRefusal.model(), sparse = dense.withSparseInput(), decisionDense = TrainedPublicDecision.model(), decisionSparse = decisionDense.withSparseInput();
        RichTextDecisionModel a = new RichTextDecisionModel(encoder, decisionDense, TrainedPublicDecision.TEMPERATURE, RichTextEncoder.VERSION, encoder.fingerprint()),
                b = new RichTextDecisionModel(encoder, decisionSparse, TrainedPublicDecision.TEMPERATURE, RichTextEncoder.VERSION, encoder.fingerprint());
        RichIntentRefusalModel gate = gate(dense), sparseGate = gate(sparse), prior = NeuralProbeContract.richModel(TrainedRichPresence.model().withSparseInput());
        int count = 0; String line;
        while ((line = reader.readLine()) != null) {
            require(count < EXPECTED_PROBES && line.length() < 12000, "Public probe bounds exceeded"); String[] c = line.split("\t", -1);
            require(c.length == 9 && c[0].equals(Integer.toString(count)) && c[1].length() <= 2048, "Public probe order/schema changed"); count++;
            float[] x = encoder.encode(c[1]); require(c[2].equals(hash(x)), "Public JVM/ART feature drift");
            require(Arrays.equals(dense.infer(x), sparse.infer(x)) && Arrays.equals(decisionDense.infer(x), decisionSparse.infer(x)), "Public dense/sparse logits differ");
            double score = Double.parseDouble(c[3]); require(Double.isFinite(score) && score >= 0 && score <= 1 && Math.abs(dense.infer(x)[0] - score) <= 2e-6, "Public gate score drift");
            TinyDecisionModel.Decision g = gate.classify(c[1], 0.6, 0.15), old = prior.classify(c[1], 0.6, 0.15);
            check(g, c[4], c[5]); exact(g, sparseGate.classify(c[1], 0.6, 0.15));
            require(Arrays.equals(g.probabilitiesCopy(), old.probabilitiesCopy()) && (g.status != TinyDecisionModel.Status.CHOICE || Objects.equals(g.choice, old.choice)) &&
                    (old.status != TinyDecisionModel.Status.ABSTAIN || g.status == old.status), "Public gate changed prior proposal");
            TinyDecisionModel.Decision d = a.classify(c[1], TrainedPublicDecision.MINIMUM_PROBABILITY, TrainedPublicDecision.MINIMUM_MARGIN);
            check(d, c[6], c[7]); exact(d, b.classify(c[1], TrainedPublicDecision.MINIMUM_PROBABILITY, TrainedPublicDecision.MINIMUM_MARGIN));
            require(!"__reject__".equals(d.choice), "Reject label escaped as a choice");
            String[] values = c[8].split(",", -1); require(values.length == 6, "Public decision probability schema changed");
            double[] probabilities = d.probabilitiesCopy();
            for (int i = 0; i < 6; i++) { double p = Double.parseDouble(values[i]); require(Double.isFinite(p) && p >= 0 && p <= 1 && Math.abs(p - probabilities[i]) <= 1e-6, "Public decision JVM/ART probability drift"); }
        }
        require(count == EXPECTED_PROBES, "Missing public probes"); return count;
    }
    private static RichIntentRefusalModel gate(TinyNeuralRuntime network) {
        return new RichIntentRefusalModel(NeuralProbeContract.gated(TrainedTextIntentCheckpoint.model().withSparseInput(), TrainedRefusalGateCheckpoint.model().withSparseInput()),
                TrainedRichVocabulary.encoder(), TrainedRichPresence.model().withSparseInput(), TrainedRichVocabulary.THRESHOLD, TrainedRichVocabulary.REQUIRE_MATCH,
                RichTextEncoder.VERSION, TrainedPublicRefusal.VOCABULARY_SHA256, network, TrainedPublicRefusal.CUTOFF);
    }
    private static void check(TinyDecisionModel.Decision d, String status, String choice) { require(d.status.name().equals(status) && Objects.equals(d.choice == null ? "-" : d.choice, choice), "Public JVM/ART typed decision drift"); }
    private static void exact(TinyDecisionModel.Decision a, TinyDecisionModel.Decision b) { require(a.status == b.status && Objects.equals(a.choice, b.choice) && Arrays.equals(a.probabilitiesCopy(), b.probabilitiesCopy()), "Public dense/sparse decision drift"); }
    private static String hash(float[] features) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256"); for (float f : features) { int v = Float.floatToIntBits(f); hash.update((byte) (v >>> 24)); hash.update((byte) (v >>> 16)); hash.update((byte) (v >>> 8)); hash.update((byte) v); }
        StringBuilder result = new StringBuilder(); for (byte v : hash.digest()) result.append(String.format(Locale.ROOT, "%02x", v & 255)); return result.toString();
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private PublicNeuralProbeContract() { }
}
