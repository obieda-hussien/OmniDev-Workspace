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
    public static GatedTextIntentModel cascade(TinyNeuralRuntime domain, TinyNeuralRuntime prior, TinyNeuralRuntime extra) {
        require(BilingualTextEncoder.VERSION.equals(TrainedBilingualRefusalCheckpoint.ENCODER_VERSION), "New gate encoder mismatch");
        return new GatedTextIntentModel(domain, TrainedTextIntentCheckpoint.labels(), TrainedTextIntentCheckpoint.TEMPERATURE, BilingualTextEncoder.VERSION,
                new GatedTextIntentModel.RefusalHead(prior, TrainedRefusalGateCheckpoint.TEMPERATURE, TrainedRefusalGateCheckpoint.CUTOFF),
                new GatedTextIntentModel.RefusalHead(extra, TrainedBilingualRefusalCheckpoint.TEMPERATURE, TrainedBilingualRefusalCheckpoint.CUTOFF));
    }
    public static void verifyCascade(List<Probe> probes, Reader reader) throws Exception {
        require(probes.size() == EXPECTED_PROBES, "Missing regression probes");
        BufferedReader input = new BufferedReader(reader); require("id\tstatus\tchoice".equals(input.readLine()), "Invalid cascade probe header");
        TinyNeuralRuntime domain = TrainedTextIntentCheckpoint.model(), prior = TrainedRefusalGateCheckpoint.model(), extra = TrainedBilingualRefusalCheckpoint.model();
        GatedTextIntentModel dense = cascade(domain, prior, extra), sparse = cascade(domain.withSparseInput(), prior.withSparseInput(), extra.withSparseInput());
        for (int i = 0; i < probes.size(); i++) {
            String line = input.readLine(); require(line != null && line.length() <= 256, "Missing cascade probe");
            String[] cells = line.split("\t", -1); require(cells.length == 3 && cells[0].equals(Integer.toString(i)), "Invalid cascade probe order");
            Probe probe = probes.get(i); TinyDecisionModel.Decision actual = dense.classify(probe.text, 0.6, 0.15);
            checkDecision(actual, cells[1], cells[2], probe.probabilities);
            exact(actual, sparse.classify(probe.text, 0.6, 0.15));
            require(!probe.gateStatus.equals("ABSTAIN") || actual.status == TinyDecisionModel.Status.ABSTAIN, "Cascade undid prior refusal");
            require(actual.status != TinyDecisionModel.Status.CHOICE || Objects.equals(actual.choice, probe.baseChoice), "Cascade invented choice");
        }
        require(input.readLine() == null, "Unexpected extra cascade probes");
    }
    public static RichIntentRefusalModel richModel(TinyNeuralRuntime network) {
        return new RichIntentRefusalModel(gated(TrainedTextIntentCheckpoint.model().withSparseInput(), TrainedRefusalGateCheckpoint.model().withSparseInput()),
                TrainedRichVocabulary.encoder(), network, TrainedRichVocabulary.THRESHOLD, TrainedRichVocabulary.REQUIRE_MATCH,
                TrainedRichVocabulary.VERSION, TrainedRichPresence.VOCABULARY_SHA256);
    }
    public static void verifyRich(List<Probe> probes, Reader reader) throws Exception {
        require(probes.size() == EXPECTED_PROBES, "Missing rich regression probes");
        BufferedReader input = new BufferedReader(reader); require("id\tfeature_sha256\tstatus\tchoice\tscores".equals(input.readLine()), "Invalid rich probe header");
        RichTextEncoder encoder = TrainedRichVocabulary.encoder(); TinyNeuralRuntime dense = TrainedRichPresence.model(), sparse = dense.withSparseInput();
        RichIntentRefusalModel a = richModel(dense), b = richModel(sparse);
        for (int i = 0; i < probes.size(); i++) {
            String line = input.readLine(); require(line != null && line.length() < 512, "Invalid rich probe bounds");
            String[] c = line.split("\t", -1); require(c.length == 5 && c[0].equals(Integer.toString(i)), "Invalid rich probe order");
            Probe probe = probes.get(i); float[] features = encoder.encode(probe.text);
            require(c[1].equals(featureHash(features)), "Rich JVM/ART feature mismatch");
            float[] scores = dense.infer(features); require(Arrays.equals(scores, sparse.infer(features)), "Rich dense/sparse score mismatch");
            String[] expected = c[4].split(",", -1); require(expected.length == 5, "Rich score schema mismatch");
            for (int j = 0; j < 5; j++) {
                double reference = Double.parseDouble(expected[j]);
                require(Double.isFinite(reference) && reference >= 0 && reference <= 1 && Math.abs(scores[j] - reference) <= 2e-6, "Rich JVM/ART score mismatch");
            }
            TinyDecisionModel.Decision actual = a.classify(probe.text, 0.6, 0.15);
            checkDecision(actual, c[2], c[3], probe.probabilities); exact(actual, b.classify(probe.text, 0.6, 0.15));
            require(!probe.gateStatus.equals("ABSTAIN") || actual.status == TinyDecisionModel.Status.ABSTAIN, "Rich guard undid a prior refusal");
            require(actual.status != TinyDecisionModel.Status.CHOICE || Objects.equals(actual.choice, probe.baseChoice), "Rich guard invented a choice");
        }
        require(input.readLine() == null, "Unexpected extra rich probes");
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
