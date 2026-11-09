package android.util;
public class Log {
    public static final int VERBOSE = 2;
    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;
    public static final int ASSERT = 7;

    // OkHttp's Android artifact initializes logging even in plain JVM tests.
    public static boolean isLoggable(String tag, int priority) { return false; }
    public static int println(int priority, String tag, String msg) { return 0; }
    public static String getStackTraceString(Throwable throwable) {
        if (throwable == null) return "";
        java.io.StringWriter writer = new java.io.StringWriter();
        throwable.printStackTrace(new java.io.PrintWriter(writer));
        return writer.toString();
    }

    public static int i(String tag, String msg) { return 0; }
    public static int e(String tag, String msg) { return 0; }
    public static int d(String tag, String msg) { return 0; }
    public static int w(String tag, String msg) { return 0; }
    public static int v(String tag, String msg) { return 0; }
}
