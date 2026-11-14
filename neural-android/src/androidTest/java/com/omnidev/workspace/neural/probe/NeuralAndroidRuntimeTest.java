package com.omnidev.workspace.neural.probe;

import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.omnidev.workspace.domain.neural.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RunWith(AndroidJUnit4.class)
public final class NeuralAndroidRuntimeTest {
    private static volatile double sink;

    @Test public void trainedCheckpointsMatchJvmAndSparseRuntime() throws Exception {
        List<NeuralProbeContract.Probe> probes = probes();
        NeuralProbeContract.verify(probes);
        verifyCascade(probes);
        verifyRich(probes);
        int publicProbes, residualProbes, utilityProbes, prototypeProbes, trustProbes, contractProbes;
        try (Reader reader = new InputStreamReader(InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("public-probes.tsv"), StandardCharsets.UTF_8)) {
            publicProbes = PublicNeuralProbeContract.verify(reader);
        }
        try (Reader reader = new InputStreamReader(InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("residual-probes.tsv"), StandardCharsets.UTF_8)) {
            residualProbes = ResidualNeuralProbeContract.verify(reader);
        }
        try(Reader reader=new InputStreamReader(InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("utility-probes.tsv"),StandardCharsets.UTF_8)){
            utilityProbes=UtilityNeuralProbeContract.verify(reader);
        }
        try(Reader reader=new InputStreamReader(InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("prototype-probes.tsv"),StandardCharsets.UTF_8)){
            prototypeProbes=PrototypeNeuralProbeContract.verify(reader);
        }
        try(Reader reader=new InputStreamReader(InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("trust-probes.tsv"),StandardCharsets.UTF_8)){
            trustProbes=ExpertTrustNeuralProbeContract.verify(reader);
        }
        try(Reader reader=new InputStreamReader(InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("contract-probes.tsv"),StandardCharsets.UTF_8)){
            contractProbes=ContractIntentNeuralProbeContract.verify(reader);
        }
        writeReport("correctness.json", environment().put("parity_passed", true).put("probes", probes.size())
                .put("cascade_parity_passed", true).put("rich_parity_passed", true).put("public_parity_passed", true).put("public_probes", publicProbes)
                .put("residual_parity_passed", true).put("residual_probes", residualProbes)
                .put("utility_parity_passed", true).put("utility_probes", utilityProbes)
                .put("prototype_parity_passed", true).put("prototype_probes", prototypeProbes)
                .put("trust_parity_passed", true).put("trust_probes", trustProbes)
                .put("contract_parity_passed", true).put("contract_probes", contractProbes)
                .put("host_art_probability_tolerance", 1e-6).put("dense_sparse_exact", true));
    }

    @Test public void boundedContractsRejectInvalidInputsOnArt() {
        GatedTextIntentModel model = NeuralProbeContract.gated(TrainedTextIntentCheckpoint.model(), TrainedRefusalGateCheckpoint.model());
        char[] oversized = new char[2049]; Arrays.fill(oversized, 'a');
        char[] expanding = new char[683]; Arrays.fill(expanding, '\ufb03');
        rejects(() -> model.classify(null, 0.6, 0.15));
        rejects(() -> model.classify(new String(oversized), 0.6, 0.15));
        rejects(() -> model.classify(new String(expanding), 0.6, 0.15));
        rejects(() -> model.classify("source", Double.NaN, 0.15));
        UtilityExpertDecisionModel utility=UtilityNeuralProbeContract.model(true);
        rejects(() -> utility.classify(null,0.6,0.15));
        rejects(() -> utility.classify(new String(oversized),0.6,0.15));
        rejects(() -> utility.classify(new String(expanding),0.6,0.15));
        rejects(() -> utility.classify("source",Double.NaN,0.15));
        UtilityExpertDecisionModel prototype=PrototypeNeuralProbeContract.model(true);
        rejects(() -> prototype.classify(null,.6,.15));
        rejects(() -> prototype.classify(new String(oversized),.6,.15));
        rejects(() -> prototype.classify(new String(expanding),.6,.15));
        rejects(() -> prototype.classify("source",Double.NaN,.15));
        ExpertTrustDecisionModel trust=ExpertTrustNeuralProbeContract.model(true);
        rejects(() -> trust.classify(null,.6,.15));
        rejects(() -> trust.classify(new String(oversized),.6,.15));
        rejects(() -> trust.classify("source",Double.NaN,.15));
        UtilityExpertDecisionModel contract=ContractIntentNeuralProbeContract.model(true);
        rejects(() -> contract.classify(null,.6,.15));
        rejects(() -> contract.classify(new String(oversized),.6,.15));
        rejects(() -> contract.classify("source",Double.NaN,.15));
    }

    @Test public void optInWholeTextBenchmark() throws Exception {
        Assume.assumeTrue("Pass -e neuralBenchmark true to measure this Android runtime",
                "true".equals(InstrumentationRegistry.getArguments().getString("neuralBenchmark")));
        List<NeuralProbeContract.Probe> probes = probes();
        NeuralProbeContract.verify(probes);
        verifyRich(probes);
        // Construction, correctness assertions and file I/O are outside timed batches.
        TinyNeuralRuntime domain = TrainedTextIntentCheckpoint.model(), gate = TrainedRefusalGateCheckpoint.model();
        TextIntentModel dense = NeuralProbeContract.baseline(domain), sparse = NeuralProbeContract.baseline(domain.withSparseInput());
        GatedTextIntentModel gatedDense = NeuralProbeContract.gated(domain, gate);
        GatedTextIntentModel gatedSparse = NeuralProbeContract.gated(domain.withSparseInput(), gate.withSparseInput());
        RichIntentRefusalModel richDense = NeuralProbeContract.richModel(TrainedRichPresence.model());
        RichIntentRefusalModel richSparse = NeuralProbeContract.richModel(TrainedRichPresence.model().withSparseInput());
        for (int mode = 0; mode < 6; mode++) for (int i = 0; i < 256; i++)
            consume(classify(mode, probes.get(i % probes.size()).text, dense, sparse, gatedDense, gatedSparse, richDense, richSparse));
        int calls = probes.size();
        long[][] samples = new long[6][12];
        for (int round = 0; round < 12; round++) for (int position = 0; position < 6; position++) {
            int mode = (round + position) % 6;
            long start = System.nanoTime();
            for (int i = 0; i < calls; i++) consume(classify(mode, probes.get(i).text, dense, sparse, gatedDense, gatedSparse, richDense, richSparse));
            samples[mode][round] = (System.nanoTime() - start) / calls;
        }
        JSONObject report = environment().put("encoding_included", true).put("cold_start_included", false)
                .put("warmup_calls_per_mode", 256).put("calls_per_batch", calls).put("batches_per_mode", 12)
                .put("input_probe_count", probes.size())
                .put("timing_assertions", false).put("checksum", sink);
        String[] names = {"single_dense", "single_sparse", "gated_dense", "gated_sparse", "rich_dense", "rich_sparse"};
        for (int mode = 0; mode < 6; mode++) {
            JSONArray raw = new JSONArray(); for (long value : samples[mode]) raw.put(value);
            Arrays.sort(samples[mode]);
            report.put(names[mode], new JSONObject().put("batch_average_ns", raw)
                    .put("median_batch_average_ns", (samples[mode][5] + samples[mode][6]) / 2));
        }
        writeReport("benchmark.json", report);
    }
    private static TinyDecisionModel.Decision classify(int mode, String text, TextIntentModel dense,
            TextIntentModel sparse, GatedTextIntentModel gatedDense, GatedTextIntentModel gatedSparse,
            RichIntentRefusalModel richDense, RichIntentRefusalModel richSparse) {
        if (mode == 0) return dense.classify(text, 0.6, 0.15);
        if (mode == 1) return sparse.classify(text, 0.6, 0.15);
        if (mode == 2) return gatedDense.classify(text, 0.6, 0.15);
        if (mode == 3) return gatedSparse.classify(text, 0.6, 0.15);
        return (mode == 4 ? richDense : richSparse).classify(text, 0.6, 0.15);
    }
    private static void consume(TinyDecisionModel.Decision d) { double total = 0; for (double p : d.probabilitiesCopy()) total += p * p; sink = total; }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        Assert.fail("Invalid bounded input accepted on ART");
    }
    private static List<NeuralProbeContract.Probe> probes() throws Exception {
        try (Reader reader = new InputStreamReader(InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("probes.tsv"), StandardCharsets.UTF_8)) {
            return NeuralProbeContract.read(reader);
        }
    }
    private static JSONObject environment() throws Exception {
        boolean emulator = Build.FINGERPRINT.startsWith("generic") || Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish");
        return new JSONObject().put("run_id", InstrumentationRegistry.getArguments().getString("neuralProbeRun", "manual_untracked"))
                .put("sdk", Build.VERSION.SDK_INT).put("model", Build.MODEL).put("manufacturer", Build.MANUFACTURER)
                .put("abis", new JSONArray(Arrays.asList(Build.SUPPORTED_ABIS))).put("hardware", Build.HARDWARE)
                .put("emulator_detected", emulator).put("physical_device_verified", false)
                .put("production_eligible", false).put("encoder", BilingualTextEncoder.VERSION)
                .put("parameters", TrainedTextIntentCheckpoint.model().parameterCount() + TrainedRefusalGateCheckpoint.model().parameterCount());
    }
    private static void verifyCascade(List<NeuralProbeContract.Probe> probes) throws Exception {
        try (Reader reader = new InputStreamReader(InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("bilingual-cascade.tsv"), StandardCharsets.UTF_8)) {
            NeuralProbeContract.verifyCascade(probes, reader);
        }
    }
    private static void verifyRich(List<NeuralProbeContract.Probe> probes) throws Exception {
        try (Reader reader = new InputStreamReader(InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("rich-presence.tsv"), StandardCharsets.UTF_8)) {
            NeuralProbeContract.verifyRich(probes, reader);
        }
    }
    private static void writeReport(String name, JSONObject json) throws Exception {
        File output = new File(InstrumentationRegistry.getInstrumentation().getContext().getFilesDir(), name);
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(output), StandardCharsets.UTF_8)) { writer.write(json.toString(2)); }
        Log.i("OmniNeuralProbe", json.toString());
        Bundle status = new Bundle(); status.putString("stream", "Neural test report: " + output.getAbsolutePath() + "\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
    }
}
