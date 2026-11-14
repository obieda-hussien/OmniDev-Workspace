public final class TrainedContractPolicy{
public static final boolean PRODUCTION_ELIGIBLE=false;
public static final String SOURCE="new_width0_seed97";
public static final String VOCABULARY_SHA256="e3edfb2fc11c8aa04dd96de68886bdf8e0a34a26baf2ee3ae5d1c7aa69579112";
public static final String RUNTIME_SHA256="f423ec428ab9e2bcaa69671279494c37b9f884057b0ee8ea61edc2c3197683f2";
public static final double TEMPERATURE=0.5;
public static double[] reject(){return new double[]{0.5, 0.7, 0.9, 0.8, 0.6};}
public static double[] change(){return new double[]{0.99, 0.8, 1.1, 1.1, 0.8};}
public static double[] support(){return new double[]{0.6, 1.1, 1.1, 1.1, 0.5};}
}
