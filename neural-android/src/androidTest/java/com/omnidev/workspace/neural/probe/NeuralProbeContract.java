package com.omnidev.workspace.neural.probe;

import com.omnidev.workspace.domain.neural.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Shared JVM/ART regression contract. No Android dependencies, training or timing assertions. */
public final class NeuralProbeContract {
    public static final int EXPECTED_PROBES = 139;
    public static final class Probe {
        public final String text, featureHash, baseStatus, baseChoice, gateStatus, gateChoice;
        public final double[] probabilities;
        private Probe(String[] c) {
            text = c[1]; featureHash = c[2]; baseStatus = c[3]; baseChoice = c[4]; gateStatus = c[5]; gateChoice = c[6];
            require(text.length() <= 2048 && featureHash.matches("[0-9a-f]{64}"), "Invalid feature contract");
            String[] values = c[7].split(",", -1); require(values.length == 5, "Invalid distribution size");
            probabilities = new double[5]; double sum = 0;
            for (int i = 0; i < 5; i++) {
                probabilities[i] = Double.parseDouble(values[i]);
                require(Double.isFinite(probabilities[i]) && probabilities[i] >= 0 && probabilities[i] <= 1, "Invalid probability");
                sum += probabilities[i];
            }
            require(Math.abs(sum - 1) < 1e-9, "Unnormalized distribution");
        }
    }
    public static List<Probe> read(Reader reader) throws IOException {
        BufferedReader input = new BufferedReader(reader);
        require("id\ttext\tfeature_sha256\tbase_status\tbase_choice\tgate_status\tgate_choice\tprobabilities".equals(input.readLine()), "Invalid probe header");
        List<Probe> probes = new ArrayList<>(); String line;
        while ((line = input.readLine()) != null) {
            require(probes.size() < EXPECTED_PROBES && line.length() <= 8192, "Probe bounds exceeded");
            String[] c = line.split("\t", -1);
            require(c.length == 8 && c[0].equals(Integer.toString(probes.size())), "Invalid or reordered probe");
            probes.add(new Probe(c));
        }
        require(probes.size() == EXPECTED_PROBES, "Missing regression probes");
        return Collections.unmodifiableList(probes);
    }
    public static TextIntentModel baseline(TinyNeuralRuntime domain) {
        return new TextIntentModel(domain, TrainedTextIntentCheckpoint.labels(), TrainedTextIntentCheckpoint.TEMPERATURE, BilingualTextEncoder.VERSION);
    }
    public static GatedTextIntentModel gated(TinyNeuralRuntime domain, TinyNeuralRuntime gate) {
        return new GatedTextIntentModel(domain, TrainedTextIntentCheckpoint.labels(), TrainedTextIntentCheckpoint.TEMPERATURE,
                gate, TrainedRefusalGateCheckpoint.TEMPERATURE, TrainedRefusalGateCheckpoint.CUTOFF, BilingualTextEncoder.VERSION);
    }
    public static void verify(List<Probe> probes) throws Exception {
        require(probes.size() == EXPECTED_PROBES, "Missing regression probes");
        require(BilingualTextEncoder.VERSION.equals(TrainedTextIntentCheckpoint.ENCODER_VERSION) &&
                BilingualTextEncoder.VERSION.equals(TrainedRefusalGateCheckpoint.ENCODER_VERSION), "Encoder mismatch");
        TinyNeuralRuntime domain = TrainedTextIntentCheckpoint.model(), gate = TrainedRefusalGateCheckpoint.model();
        TinyNeuralRuntime sparseDomain = domain.withSparseInput(), sparseGate = gate.withSparseInput();
        TextIntentModel base = baseline(domain), sparseBase = baseline(sparseDomain);
        GatedTextIntentModel candidate = gated(domain, gate), sparseCandidate = gated(sparseDomain, sparseGate);
        for (Probe probe : probes) {
            float[] features = BilingualTextEncoder.encode(probe.text);
            require(probe.featureHash.equals(featureHash(features)), "JVM/ART encoding mismatch");
            require(Arrays.equals(domain.infer(features), sparseDomain.infer(features)) &&
                    Arrays.equals(gate.infer(features), sparseGate.infer(features)), "Dense/sparse logits mismatch");
            TinyDecisionModel.Decision a = base.classify(probe.text, 0.6, 0.15), b = candidate.classify(probe.text, 0.6, 0.15);
            checkDecision(a, probe.baseStatus, probe.baseChoice, probe.probabilities);
            checkDecision(b, probe.gateStatus, probe.gateChoice, probe.probabilities);
            require(Arrays.equals(a.probabilitiesCopy(), b.probabilitiesCopy()), "Gate changed domain scores");
            require(b.status != TinyDecisionModel.Status.CHOICE || Objects.equals(a.choice, b.choice), "Gate invented a choice");
            exact(a, sparseBase.classify(probe.text, 0.6, 0.15));
            exact(b, sparseCandidate.classify(probe.text, 0.6, 0.15));
        }
    }
    private static void checkDecision(TinyDecisionModel.Decision d, String status, String choice, double[] expected) {
        require(d.status.name().equals(status) && Objects.equals(d.choice == null ? "-" : d.choice, choice), "JVM/ART decision mismatch");
        double[] actual = d.probabilitiesCopy();
        for (int i = 0; i < actual.length; i++) require(Math.abs(actual[i] - expected[i]) <= 1e-6, "JVM/ART probability mismatch");
    }
    private static void exact(TinyDecisionModel.Decision a, TinyDecisionModel.Decision b) {
        require(a.status == b.status && Objects.equals(a.choice, b.choice) &&
                Arrays.equals(a.probabilitiesCopy(), b.probabilitiesCopy()), "Dense/sparse decision mismatch");
    }
    private static String featureHash(float[] features) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (float f : features) { int bits = Float.floatToIntBits(f);
            digest.update((byte) (bits >>> 24)); digest.update((byte) (bits >>> 16)); digest.update((byte) (bits >>> 8)); digest.update((byte) bits); }
        StringBuilder result = new StringBuilder();
        for (byte b : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private NeuralProbeContract() { }
}
