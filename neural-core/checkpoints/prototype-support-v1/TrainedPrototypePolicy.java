public final class TrainedPrototypePolicy {
public static final boolean PRODUCTION_ELIGIBLE=false;
public static final String BANK_SHA256="14ff918a93491b4155220faa6e49e59e681feab38a986b2d8a39e284af7df4d3";
public static final String VOCABULARY_SHA256="e3edfb2fc11c8aa04dd96de68886bdf8e0a34a26baf2ee3ae5d1c7aa69579112";
public static double[] reject(){return new double[]{0.6, 0.6, 1.1, 1.1, 0.7};}
public static double[] change(){return new double[]{0.99, 0.7, 1.1, 1.1, 0.6};}
public static double[] cutoffs(){return new double[]{0.26723553409576417, 0.36132513155937196, 0.08877441456317901, 0.24914424290657045, 0.08219652047157287};}
}
