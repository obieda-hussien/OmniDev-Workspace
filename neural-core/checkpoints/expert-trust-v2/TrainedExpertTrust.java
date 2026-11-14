import com.omnidev.workspace.domain.neural.TinyNeuralRuntime;
public final class TrainedExpertTrust {
  public static final double TEMPERATURE = 1.5;
  public static TinyNeuralRuntime model() { return new TinyNeuralRuntime(
    new TinyNeuralRuntime.Dense(20,2,lastWeights(),lastBias(),TinyNeuralRuntime.Activation.LINEAR)); }
  private static float[] lastWeights() { float[] values = new float[40];
    lastWeights0(values);
    return values; }
  private static void lastWeights0(float[] values) {
    values[0] = -0.61639357f;
    values[1] = 0.22061734f;
    values[2] = 1.0913318f;
    values[3] = 0.04465479f;
    values[4] = 0.59099054f;
    values[5] = 0.020973997f;
    values[6] = 0.08336056f;
    values[7] = 0.17469478f;
    values[8] = -0.19010463f;
    values[9] = 0.5392339f;
    values[10] = 0.30582407f;
    values[11] = 0.20447242f;
    values[12] = 0.49932167f;
    values[13] = -0.26211014f;
    values[14] = 0.81826216f;
    values[15] = -0.23039581f;
    values[16] = -0.2302826f;
    values[17] = -0.4097502f;
    values[18] = 0.3999248f;
    values[19] = -3.376865f;
    values[20] = 0.6261215f;
    values[21] = -0.21249251f;
    values[22] = -0.7487823f;
    values[23] = -0.13274182f;
    values[24] = -0.4998969f;
    values[25] = -0.086061634f;
    values[26] = -0.22569473f;
    values[27] = -0.12879154f;
    values[28] = 0.018468842f;
    values[29] = -0.35203397f;
    values[30] = -0.45336202f;
    values[31] = -0.24473692f;
    values[32] = -0.566091f;
    values[33] = 0.5900008f;
    values[34] = -0.5674222f;
    values[35] = 0.28963926f;
    values[36] = 0.47576228f;
    values[37] = 0.21748406f;
    values[38] = -0.40171158f;
    values[39] = 3.223649f;
  }
  private static float[] lastBias() { float[] values = new float[2];
    lastBias0(values);
    return values; }
  private static void lastBias0(float[] values) {
    values[0] = 1.0289987f;
    values[1] = -1.0289987f;
  }
public static final boolean PRODUCTION_ELIGIBLE=false;
public static final String FEATURE_VERSION="teacher5-expert6-domain5-presence-reject-margins-v1";
public static final String BASE_SHA256="665d23ee255ff5dfb1714114d238c3a0fccc348adaeaffa15b83abfaf7a07a3e";
public static final String SOURCE="paired_width0_seed97";
public static double[] cutoffs(){return new double[]{0.0, 0.6, 0.8, 0.7, 0.0};}
}
