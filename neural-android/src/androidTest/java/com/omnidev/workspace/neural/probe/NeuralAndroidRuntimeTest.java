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
        writeReport("correctness.json", environment().put("parity_passed", true).put("probes", probes.size())
                .put("cascade_parity_passed", true).put("host_art_probability_tolerance", 1e-6).put("dense_sparse_exact", true));
    }

    @Test public void boundedContractsRejectInvalidInputsOnArt() {
        GatedTextIntentModel model = NeuralProbeContract.gated(TrainedTextIntentCheckpoint.model(), TrainedRefusalGateCheckpoint.model());
        char[] oversized = new char[2049]; Arrays.fill(oversized, 'a');
        char[] expanding = new char[683]; Arrays.fill(expanding, '\ufb03');
        rejects(() -> model.classify(null, 0.6, 0.15));
        rejects(() -> model.classify(new String(oversized), 0.6, 0.15));
        rejects(() -> model.classify(new String(expanding), 0.6, 0.15));
        rejects(() -> model.classify("source", Double.NaN, 0.15));
    }

    @Test public void optInWholeTextBenchmark() throws Exception {
        Assume.assumeTrue("Pass -e neuralBenchmark true to measure this Android runtime",
                "true".equals(InstrumentationRegistry.getArguments().getString("neuralBenchmark")));
        List<NeuralProbeContract.Probe> probes = probes();
        NeuralProbeContract.verify(probes);
        // Construction, correctness assertions and file I/O are outside timed batches.
        TinyNeuralRuntime domain = TrainedTextIntentCheckpoint.model(), gate = TrainedRefusalGateCheckpoint.model();
        TextIntentModel dense = NeuralProbeContract.baseline(domain), sparse = NeuralProbeContract.baseline(domain.withSparseInput());
        GatedTextIntentModel gatedDense = NeuralProbeContract.gated(domain, gate);
        GatedTextIntentModel gatedSparse = NeuralProbeContract.gated(domain.withSparseInput(), gate.withSparseInput());
        for (int mode = 0; mode < 4; mode++) for (int i = 0; i < 256; i++)
            consume(classify(mode, probes.get(i % probes.size()).text, dense, sparse, gatedDense, gatedSparse));
        int calls = probes.size();
        long[][] samples = new long[4][8];
        for (int round = 0; round < 8; round++) for (int position = 0; position < 4; position++) {
            int mode = (round + position) % 4;
            long start = System.nanoTime();
            for (int i = 0; i < calls; i++) consume(classify(mode, probes.get(i).text, dense, sparse, gatedDense, gatedSparse));
            samples[mode][round] = (System.nanoTime() - start) / calls;
        }
        JSONObject report = environment().put("encoding_included", true).put("cold_start_included", false)
                .put("warmup_calls_per_mode", 256).put("calls_per_batch", calls).put("batches_per_mode", 8)
                .put("input_probe_count", probes.size())
                .put("timing_assertions", false).put("checksum", sink);
        String[] names = {"single_dense", "single_sparse", "gated_dense", "gated_sparse"};
        for (int mode = 0; mode < 4; mode++) {
            JSONArray raw = new JSONArray(); for (long value : samples[mode]) raw.put(value);
            Arrays.sort(samples[mode]);
            report.put(names[mode], new JSONObject().put("batch_average_ns", raw)
                    .put("median_batch_average_ns", (samples[mode][3] + samples[mode][4]) / 2));
        }
        writeReport("benchmark.json", report);
    }
    private static TinyDecisionModel.Decision classify(int mode, String text, TextIntentModel dense,
            TextIntentModel sparse, GatedTextIntentModel gatedDense, GatedTextIntentModel gatedSparse) {
        if (mode == 0) return dense.classify(text, 0.6, 0.15);
        if (mode == 1) return sparse.classify(text, 0.6, 0.15);
        if (mode == 2) return gatedDense.classify(text, 0.6, 0.15);
        return gatedSparse.classify(text, 0.6, 0.15);
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
    private static void writeReport(String name, JSONObject json) throws Exception {
        File output = new File(InstrumentationRegistry.getInstrumentation().getContext().getFilesDir(), name);
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(output), StandardCharsets.UTF_8)) { writer.write(json.toString(2)); }
        Log.i("OmniNeuralProbe", json.toString());
        Bundle status = new Bundle(); status.putString("stream", "Neural test report: " + output.getAbsolutePath() + "\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
    }
}
