package com.omnidev.workspace.neural.probe;

import com.omnidev.workspace.domain.neural.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Selected and forced-active residual JVM/ART parity; forced activation is a numerical test, never promotion. */
public final class ResidualNeuralProbeContract {
    public static final int EXPECTED_PROBES = 859;
    public static int verify(Reader source) throws Exception {
        BufferedReader reader = new BufferedReader(source);
        require("id\ttext\tfeature_sha256\tselected_status\tselected_choice\tselected_probabilities\tactive_status\tactive_choice\tactive_probabilities\tresidual".equals(reader.readLine()), "Invalid residual probe header");
        RichTextEncoder encoder = TrainedRichVocabulary.encoder(); require(encoder.fingerprint().equals(TrainedResidualAdapter.VOCABULARY_SHA256), "Residual vocabulary changed");
        TinyNeuralRuntime dense = TrainedResidualAdapter.model(), sparse = dense.withSparseInput(); RichIntentRefusalModel teacher = teacher();
        BoundedResidualDecisionModel[] modes = {
            new BoundedResidualDecisionModel(teacher, encoder, dense, TrainedResidualAdapter.GAIN, encoder.fingerprint()),
            new BoundedResidualDecisionModel(teacher, encoder, sparse, TrainedResidualAdapter.GAIN, encoder.fingerprint()),
            new BoundedResidualDecisionModel(teacher, encoder, dense, 1, encoder.fingerprint()),
            new BoundedResidualDecisionModel(teacher, encoder, sparse, 1, encoder.fingerprint())};
        String line; int count = 0;
        while ((line = reader.readLine()) != null) {
            require(count < EXPECTED_PROBES && line.length() < 12000, "Residual probe bounds exceeded"); String[] c = line.split("\t", -1);
            require(c.length == 10 && c[0].equals(Integer.toString(count)) && c[1].length() <= 2048, "Residual order/schema changed"); count++;
            float[] x = encoder.encode(c[1]), values = dense.infer(x); require(c[2].equals(hash(x)), "Residual JVM/ART feature drift");
            require(Arrays.equals(values, sparse.infer(x)), "Residual dense/sparse numeric drift");
            String[] ref = c[9].split(",", -1); require(ref.length == 5, "Residual correction schema changed");
            for (int j = 0; j < 5; j++) { double value = Double.parseDouble(ref[j]); require(Double.isFinite(value) && Math.abs(value) <= 1 && Math.abs(value - values[j]) <= 2e-6, "Residual JVM/ART correction drift"); }
            TinyDecisionModel.Decision before = teacher.classify(c[1], 0.6, 0.15);
            for (int mode = 0; mode < 2; mode++) {
                TinyDecisionModel.Decision d = modes[mode * 2].classify(c[1], 0.6, 0.15), s = modes[mode * 2 + 1].classify(c[1], 0.6, 0.15); int offset = mode == 0 ? 3 : 6;
                require(d.status.name().equals(c[offset]) && Objects.equals(d.choice == null ? "-" : d.choice, c[offset + 1]), "Residual JVM/ART typed drift"); exact(d, s);
                String[] p = c[offset + 2].split(",", -1); require(p.length == 5, "Residual probability schema changed"); double[] scores = d.probabilitiesCopy();
                for (int j = 0; j < 5; j++) { double reference = Double.parseDouble(p[j]); require(Double.isFinite(reference) && reference >= 0 && reference <= 1 && Math.abs(reference - scores[j]) <= 1e-6, "Residual JVM/ART probability drift"); }
                if (before.status == TinyDecisionModel.Status.ABSTAIN || mode == 0 && TrainedResidualAdapter.GAIN == 0) exact(before, d);
            }
        }
        require(count == EXPECTED_PROBES, "Missing residual probes"); return count;
    }
    private static RichIntentRefusalModel teacher() {
        return new RichIntentRefusalModel(NeuralProbeContract.gated(TrainedTextIntentCheckpoint.model().withSparseInput(), TrainedRefusalGateCheckpoint.model().withSparseInput()),
                TrainedRichVocabulary.encoder(), TrainedRichPresence.model().withSparseInput(), TrainedRichVocabulary.THRESHOLD, TrainedRichVocabulary.REQUIRE_MATCH,
                RichTextEncoder.VERSION, TrainedPublicRefusal.VOCABULARY_SHA256, TrainedPublicRefusal.model().withSparseInput(), TrainedPublicRefusal.CUTOFF);
    }
    private static void exact(TinyDecisionModel.Decision a, TinyDecisionModel.Decision b) { require(a.status == b.status && Objects.equals(a.choice, b.choice) && Arrays.equals(a.probabilitiesCopy(), b.probabilitiesCopy()), "Residual no-change/sparse/refusal contract changed"); }
    private static String hash(float[] x) throws Exception { MessageDigest digest = MessageDigest.getInstance("SHA-256"); for (float f : x) { int b = Float.floatToIntBits(f); digest.update((byte) (b >>> 24)); digest.update((byte) (b >>> 16)); digest.update((byte) (b >>> 8)); digest.update((byte) b); } StringBuilder out = new StringBuilder(); for (byte b : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", b & 255)); return out.toString(); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private ResidualNeuralProbeContract() { }
}
