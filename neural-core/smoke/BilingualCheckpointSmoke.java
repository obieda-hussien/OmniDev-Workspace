import com.omnidev.workspace.domain.neural.*;
import java.nio.file.Paths;
import java.util.*;

/** Compare the versioned candidate with the newly fitted replica without requiring identical JDK math bytes. */
public final class BilingualCheckpointSmoke {
    public static void main(String[] args) throws Exception {
        if (!TrainedBilingualRefusalCheckpoint.CORPUS_SHA256.equals(TrainedBilingualRefusalReplica.CORPUS_SHA256) ||
                !TrainedBilingualRefusalCheckpoint.ENCODER_VERSION.equals(TrainedBilingualRefusalReplica.ENCODER_VERSION) ||
                TrainedBilingualRefusalCheckpoint.TEMPERATURE != TrainedBilingualRefusalReplica.TEMPERATURE ||
                TrainedBilingualRefusalCheckpoint.CUTOFF != TrainedBilingualRefusalReplica.CUTOFF ||
                !Arrays.equals(TrainedBilingualRefusalCheckpoint.labels(), TrainedBilingualRefusalReplica.labels())) throw new AssertionError("Versioned metadata changed");
        TinyNeuralRuntime stored = TrainedBilingualRefusalCheckpoint.model(), fresh = TrainedBilingualRefusalReplica.model();
        TinyDecisionModel a = new TinyDecisionModel(stored, TrainedBilingualRefusalCheckpoint.labels(), TrainedBilingualRefusalCheckpoint.TEMPERATURE);
        TinyDecisionModel b = new TinyDecisionModel(fresh, TrainedBilingualRefusalReplica.labels(), TrainedBilingualRefusalReplica.TEMPERATURE);
        int count = 0;
        for (BilingualRefusalCorpus.Row row : BilingualRefusalCorpus.load(Paths.get(args[0]))) {
            float[] features = BilingualTextEncoder.encode(row.text); float[] x = stored.infer(features), y = fresh.infer(features);
            for (int i = 0; i < x.length; i++) if (Math.abs(x[i] - y[i]) > 1e-6) throw new AssertionError("Versioned checkpoint logit drift");
            boolean oldRefuse = a.decide(features, 0, 0).probabilitiesCopy()[1] >= TrainedBilingualRefusalCheckpoint.CUTOFF;
            boolean newRefuse = b.decide(features, 0, 0).probabilitiesCopy()[1] >= TrainedBilingualRefusalReplica.CUTOFF;
            if (oldRefuse != newRefuse) throw new AssertionError("Versioned refusal decision drift");
            count++;
        }
        System.out.println("Versioned checkpoint/replica parity passed on " + count + " observations (logits <=1e-6, identical refusals).");
    }
}
