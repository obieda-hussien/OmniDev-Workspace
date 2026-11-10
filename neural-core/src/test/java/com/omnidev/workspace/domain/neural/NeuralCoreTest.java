package com.omnidev.workspace.domain.neural;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

public final class NeuralCoreTest {
    private static TinyNeuralRuntime network(int in, int out, float[] weights, float[] bias,
                                              TinyNeuralRuntime.Activation activation) {
        return new TinyNeuralRuntime(new TinyNeuralRuntime.Dense(in, out, weights, bias, activation));
    }
    private static TinyNeuralRuntime linear(float value) {
        return network(1, 1, new float[]{value}, new float[]{0}, TinyNeuralRuntime.Activation.LINEAR);
    }
    private static void rejects(Runnable call) {
        try {
            call.run();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void denseAndActivation() {
        TinyNeuralRuntime n = network(2, 1, new float[]{2, -1}, new float[]{1},
                TinyNeuralRuntime.Activation.RELU);
        assertEquals(3, n.parameterCount());
        assertEquals(4f, n.infer(new float[]{2, 1})[0], 1e-6);
        assertEquals(0f, n.infer(new float[]{0, 2})[0], 1e-6);
    }

    @Test public void layersAndSigmoidNumerics() {
        TinyNeuralRuntime n = new TinyNeuralRuntime(
                new TinyNeuralRuntime.Dense(1, 1, new float[]{-1}, new float[]{0},
                        TinyNeuralRuntime.Activation.TANH),
                new TinyNeuralRuntime.Dense(1, 1, new float[]{10}, new float[]{0},
                        TinyNeuralRuntime.Activation.SIGMOID));
        float y = n.infer(new float[]{2})[0];
        assertTrue(y > 0 && y < 0.5f);
        assertEquals(4, n.parameterCount());
    }

    @Test public void copiedWeightsAndBoundedInference() {
        float[] w = {1f};
        TinyNeuralRuntime n = linear(1f);
        TinyNeuralRuntime.Dense original = new TinyNeuralRuntime.Dense(1, 1, w,
                new float[]{0}, TinyNeuralRuntime.Activation.LINEAR);
        w[0] = 100;
        assertEquals(1f, original.weightsCopy()[0], 0f);
        assertEquals(2f, n.infer(new float[]{2})[0], 0f);
        rejects(() -> n.infer(new float[]{Float.NaN}));
        rejects(() -> network(1, 1, new float[]{Float.POSITIVE_INFINITY},
                new float[]{0}, TinyNeuralRuntime.Activation.LINEAR));
        rejects(() -> network(1, 1, new float[]{1}, new float[]{0, 1},
                TinyNeuralRuntime.Activation.LINEAR));
    }

    @Test public void tooLargeNetworkRejected() {
        rejects(() -> new TinyNeuralRuntime.Dense(500, 500, new float[250000],
                new float[500], TinyNeuralRuntime.Activation.LINEAR));
        rejects(() -> new TinyNeuralRuntime(new TinyNeuralRuntime.Dense(1, 2, new float[]{1, 1},
                new float[]{0, 0}, TinyNeuralRuntime.Activation.LINEAR),
                new TinyNeuralRuntime.Dense(3, 1, new float[]{1, 1, 1},
                        new float[]{0}, TinyNeuralRuntime.Activation.LINEAR)));
    }

    @Test public void arabicRetrievalAndCitationIntegrity() {
        List<OmniRamLite.Document> docs = Arrays.asList(
                new OmniRamLite.Document("source", "كيفية إصلاح أَخْطَاء الشَّبكة في التطبيق"),
                new OmniRamLite.Document("noise", "مشروع مختلف عن البرمجة"));
        OmniRamLite engine = new OmniRamLite(docs);
        List<OmniRamLite.Hit> hits = engine.search("اخطاء الشبكة", 5);
        assertFalse(hits.isEmpty());
        assertEquals("source", hits.get(0).documentId);
        assertTrue(EvidenceMetrics.citationsMatch(docs, hits));
        assertTrue(engine.search("elephant", 5).isEmpty());
        rejects(() -> engine.search("x", 0));
    }

    @Test public void rejectionAndCapacityGuard() {
        rejects(() -> new OmniRamLite(Arrays.asList(
                new OmniRamLite.Document("same", "one"), new OmniRamLite.Document("same", "two"))));
        rejects(() -> new OmniRamLite(Collections.singletonList(
                new OmniRamLite.Document("big", new String(new char[1_000_001])))));
    }

    @Test public void weightGateAcceptsOnlyVerifiedImprovement() {
        List<WeightEditGate.Example> dev = Arrays.asList(
                new WeightEditGate.Example(new float[]{1}, new float[]{1}),
                new WeightEditGate.Example(new float[]{2}, new float[]{2}));
        List<WeightEditGate.Example> gate = Arrays.asList(
                new WeightEditGate.Example(new float[]{3}, new float[]{3}),
                new WeightEditGate.Example(new float[]{4}, new float[]{4}));
        WeightEditGate.Result success = WeightEditGate.evaluate(linear(0), linear(1),
                dev, gate, 0.1, 0);
        assertTrue(success.accepted);
        assertEquals("Improved on both splits without excessive per-sample regression",
                success.reason);
        WeightEditGate.Result fail = WeightEditGate.evaluate(linear(1), linear(0),
                dev, gate, 0.1, 0);
        assertFalse(fail.accepted);
        assertEquals(0f, fail.selected.infer(new float[]{0})[0], 0f);
        rejects(() -> WeightEditGate.evaluate(linear(0), linear(1), dev, dev, 0, 0));
    }

    @Test public void rerankingNeverFabricatesEvidence() {
        List<OmniRamLite.Document> docs = Arrays.asList(
                new OmniRamLite.Document("a", "الاختبار البرمجي الجديد"),
                new OmniRamLite.Document("b", "الاختبار اليدوي القديم"));
        List<OmniRamLite.Hit> hits = new OmniRamLite(docs).search("الاختبار", 5);
        TinyNeuralRuntime ranker = network(5, 1,
                new float[]{1, 1, 1, 1, 1}, new float[]{0},
                TinyNeuralRuntime.Activation.SIGMOID);
        List<NeuralEvidenceReranker.Ranked> results =
                NeuralEvidenceReranker.rank("الاختبار", hits, ranker, 0.5);
        assertEquals(hits.size(), results.size());
        assertTrue(EvidenceMetrics.citationsMatch(docs, hits));
        Set<OmniRamLite.Hit> originals = new HashSet<>(hits);
        for (NeuralEvidenceReranker.Ranked result : results) {
            assertTrue(originals.contains(result.evidence));
            assertTrue(Double.isFinite(result.score));
            assertTrue(Math.abs(result.score - result.evidence.score) <= 0.25);
        }
        rejects(() -> NeuralEvidenceReranker.rank("x", hits, ranker, 1));
    }

    @Test public void retrievalMetricsUseDocumentLevelUniqueHits() {
        EvidenceMetrics.Summary perfect = EvidenceMetrics.evaluate(
                Collections.singletonList(new EvidenceMetrics.Case(
                        new HashSet<>(Arrays.asList("a", "b")), Arrays.asList("a", "b"))), 2);
        assertEquals(1, perfect.recallAtK, 1e-9);
        assertEquals(1, perfect.mrrAtK, 1e-9);
        assertEquals(1, perfect.ndcgAtK, 1e-9);
        EvidenceMetrics.Summary duplicate = EvidenceMetrics.evaluate(
                Collections.singletonList(new EvidenceMetrics.Case(
                        new HashSet<>(Arrays.asList("a", "b")), Arrays.asList("a", "a"))), 2);
        assertEquals(.5, duplicate.recallAtK, 1e-9);
        assertTrue(duplicate.ndcgAtK < 1);
    }
}