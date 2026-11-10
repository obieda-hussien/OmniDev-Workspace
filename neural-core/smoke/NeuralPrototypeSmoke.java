import com.omnidev.workspace.domain.neural.*;
import java.util.*;

/** Dependency-free Java 8 smoke runner (also exercised in GitHub Actions). */
public final class NeuralPrototypeSmoke {
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
    }
    private static TinyNeuralRuntime linear(float weight) {
        return new TinyNeuralRuntime(new TinyNeuralRuntime.Dense(1, 1,
                new float[]{weight}, new float[]{0}, TinyNeuralRuntime.Activation.LINEAR));
    }
    private static TinyNeuralRuntime reranker() {
        return new TinyNeuralRuntime(new TinyNeuralRuntime.Dense(5, 1,
                new float[]{0.4f, 0.2f, 0.1f, 0f, 0f}, new float[]{0},
                TinyNeuralRuntime.Activation.LINEAR));
    }

    public static void main(String[] args) {
        int passed = 0;
        TinyNeuralRuntime network = new TinyNeuralRuntime(
                new TinyNeuralRuntime.Dense(2, 1, new float[]{1, 2},
                        new float[]{0}, TinyNeuralRuntime.Activation.RELU));
        check(network.parameterCount() == 3 && network.infer(new float[]{2, 3})[0] == 8,
                "forward"); passed++;

        float[] weights = {1};
        TinyNeuralRuntime immutable = new TinyNeuralRuntime(
                new TinyNeuralRuntime.Dense(1, 1, weights,
                        new float[]{0}, TinyNeuralRuntime.Activation.LINEAR));
        weights[0] = 100;
        check(immutable.infer(new float[]{2})[0] == 2, "immutability"); passed++;
        try {
            immutable.infer(new float[]{Float.NaN});
            throw new AssertionError("nonfinite input accepted");
        } catch (IllegalArgumentException expected) { passed++; }

        List<OmniRamLite.Document> docs = Arrays.asList(
                new OmniRamLite.Document("kotlin", "fun retry() { throw IllegalStateException() }"),
                new OmniRamLite.Document("arabic",
                        "هذا الملف يشرح إصلاح أخطاء الشبكة وكيفية إعادة المحاولة"));
        OmniRamLite index = new OmniRamLite(docs);
        List<OmniRamLite.Hit> hits = index.search("اخطاء الشبكة", 3);
        check(!hits.isEmpty() && hits.get(0).documentId.equals("arabic"), "arabic retrieval"); passed++;
        check(index.search("elephant", 5).isEmpty(), "abstain"); passed++;
        check(EvidenceMetrics.citationsMatch(docs, hits), "exact quote offsets"); passed++;

        TinyNeuralRuntime baseline = linear(0), candidate = linear(1);
        List<WeightEditGate.Example> dev = Arrays.asList(
                new WeightEditGate.Example(new float[]{1}, new float[]{1}),
                new WeightEditGate.Example(new float[]{2}, new float[]{2}));
        List<WeightEditGate.Example> gate = Arrays.asList(
                new WeightEditGate.Example(new float[]{3}, new float[]{3}),
                new WeightEditGate.Example(new float[]{4}, new float[]{4}));
        check(WeightEditGate.evaluate(baseline, candidate, dev, gate, 0.1, 0).accepted,
                "accept valid edit"); passed++;
        check(!WeightEditGate.evaluate(candidate, baseline, dev, gate, 0.1, 0).accepted,
                "reject regression"); passed++;
        try {
            WeightEditGate.evaluate(baseline, candidate, dev, dev, 0, 0);
            throw new AssertionError("leaked samples accepted");
        } catch (IllegalArgumentException expected) { passed++; }

        List<NeuralEvidenceReranker.Ranked> ranked =
                NeuralEvidenceReranker.rank("اخطاء الشبكة", hits, reranker(), 0.2);
        check(ranked.size() == hits.size() && ranked.get(0).evidence == hits.get(0),
                "only rescores evidence"); passed++;
        check(EvidenceMetrics.citationsMatch(docs,
                Collections.singletonList(ranked.get(0).evidence)), "rerank cites exact source"); passed++;

        EvidenceMetrics.Summary metric = EvidenceMetrics.evaluate(
                Collections.singletonList(new EvidenceMetrics.Case(
                        Collections.singleton("arabic"), Arrays.asList("arabic", "kotlin"))), 2);
        check(metric.recallAtK == 1 && metric.mrrAtK == 1 && metric.ndcgAtK == 1,
                "perfect retrieval metrics"); passed++;
        EvidenceMetrics.Summary missed = EvidenceMetrics.evaluate(
                Collections.singletonList(new EvidenceMetrics.Case(
                        Collections.singleton("kotlin"), Arrays.asList("arabic", "arabic"))), 2);
        check(missed.recallAtK == 0 && missed.mrrAtK == 0 && missed.ndcgAtK == 0,
                "duplicate irrelevant hit"); passed++;
        try {
            new OmniRamLite(Arrays.asList(docs.get(0), docs.get(0)));
            throw new AssertionError("duplicate document IDs accepted");
        } catch (IllegalArgumentException expected) { passed++; }
        try {
            NeuralEvidenceReranker.rank("query", hits, reranker(), 2.0);
            throw new AssertionError("oversized model influence accepted");
        } catch (IllegalArgumentException expected) { passed++; }
        System.out.println("PASS " + passed + "/" + passed + " isolated Java smoke checks");
    }
}