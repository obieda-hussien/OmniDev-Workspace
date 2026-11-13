import com.omnidev.workspace.domain.neural.*;
import java.util.*;

/** Verify snapshot retention and leakage guards on the actual desktop trainer. */
public final class EarlyStoppingSmoke {
    public static void main(String[] args) {
        List<OfflineDecisionTrainer.Example> train = Arrays.asList(
                new OfflineDecisionTrainer.Example("train-a", new float[] {-1}, 0),
                new OfflineDecisionTrainer.Example("train-b", new float[] {1}, 1));
        List<OfflineDecisionTrainer.Example> validation = Arrays.asList(
                new OfflineDecisionTrainer.Example("dev-a", new float[] {-1}, 1),
                new OfflineDecisionTrainer.Example("dev-b", new float[] {1}, 0));
        OfflineDecisionTrainer.TrainingResult result = OfflineDecisionTrainer.fitEarlyStopped(train, 2, 4, 100, 42, Collections.singletonList(validation));
        if (result.bestEpoch != 5 || result.epochsRun != 30) throw new AssertionError("Patience/snapshot selection changed");
        TinyNeuralRuntime expected = OfflineDecisionTrainer.fit(train, 2, 4, result.bestEpoch, 42).runtime();
        if (!Arrays.equals(expected.infer(new float[] {0.5f}), result.model.runtime().infer(new float[] {0.5f}))) throw new AssertionError("Best snapshot was modified by later updates");
        double lastLoss = OfflineDecisionTrainer.logLoss(OfflineDecisionTrainer.fit(train, 2, 4, result.epochsRun, 42), validation, 1);
        if (!(result.validationLoss < lastLoss)) throw new AssertionError("Did not retain the better checkpoint");
        OfflineDecisionTrainer.TrainingResult shortRun = OfflineDecisionTrainer.fitEarlyStopped(train, 2, 0, 3, 42, Collections.singletonList(validation));
        if (shortRun.bestEpoch != 3 || shortRun.epochsRun != 3) throw new AssertionError("Final non-multiple-of-five epoch not evaluated");
        rejects(() -> OfflineDecisionTrainer.fitEarlyStopped(train, 2, 4, 50, 42, Collections.singletonList(train)));
        rejects(() -> OfflineDecisionTrainer.fitEarlyStopped(train, 2, 4, 50, 42, Collections.emptyList()));
        rejects(() -> OfflineDecisionTrainer.fitEarlyStopped(train, 2, 4, 50, 42, Collections.singletonList(Collections.singletonList(
                new OfflineDecisionTrainer.Example("dev-shape", new float[] {0, 0}, 0)))));
        System.out.println("Early stopping: immutable best snapshot, patience, final short epoch and 3 invalid validation contracts passed.");
    }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid validation contract accepted");
    }
}
