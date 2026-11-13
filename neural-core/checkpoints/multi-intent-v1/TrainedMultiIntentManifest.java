import com.omnidev.workspace.domain.neural.TinyNeuralRuntime;
public final class TrainedMultiIntentManifest {
public static final String ENCODER_VERSION = "ar-en-word-char-fnv256-v1";
public static final String CORPUS_SHA256 = "cfd303a0225c1d137c8b1744c298e133a553e9c41d5011e38fca61f1f000e57e";
public static String[] labels() {return new String[]{"repository","build","device","knowledge","chat"};}
public static final double THRESHOLD = 0.75;
public static final boolean REQUIRE_MATCH = false;
public static TinyNeuralRuntime[] models() {return new TinyNeuralRuntime[]{TrainedMultiIntent0.model(),TrainedMultiIntent1.model(),TrainedMultiIntent2.model(),TrainedMultiIntent3.model(),TrainedMultiIntent4.model()};}
 public static double[] temperatures() {return new double[]{1.0,0.5,0.75,2.0,0.5};}
}
