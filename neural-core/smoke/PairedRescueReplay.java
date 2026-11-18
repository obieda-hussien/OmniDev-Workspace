import java.math.*;
import java.nio.file.*;
import java.util.*;

/** Independent Java 8 replay of the selected selector on exported frozen runtime features. */
public final class PairedRescueReplay {
  static double round(double value, int places) {
    return new BigDecimal(value).setScale(places, RoundingMode.HALF_EVEN).doubleValue();
  }

  static boolean accepts(String[] fields, double[] weights, double cutoff) {
    if (fields[5].equals("true")) return true;
    if (fields[4].equals("true") || fields.length < 7) return false;
    String[] first = fields[6].split(":");
    double retrieval = Double.parseDouble(first[1]);
    double coverage = Double.parseDouble(first[2]);
    double pair = Double.parseDouble(first[3]);
    double next = fields.length > 7 ? Double.parseDouble(fields[7].split(":")[1]) : 0;
    double[] x = {1, retrieval, coverage, retrieval - next, pair, coverage * pair};
    double sum = 0;
    for (int i = 0; i < x.length; i++) sum += weights[i] * round(x[i], 8);
    return round(1 / (1 + Math.exp(-sum)), 10) >= cutoff;
  }

  public static void main(String[] args) throws Exception {
    List<String> policy = Files.readAllLines(Paths.get(args[0]));
    double[] weights =
        Arrays.stream(policy.get(0).split("\t")).mapToDouble(Double::parseDouble).toArray();
    double cutoff = Double.parseDouble(policy.get(1));
    List<String> features = Files.readAllLines(Paths.get(args[1]));
    List<String> expected = Files.readAllLines(Paths.get(args[2]));
    if (weights.length != 6 || features.size() != 2160 || expected.size() != features.size())
      throw new AssertionError("Incomplete replay");
    for (int i = 0; i < features.size(); i++) {
      String[] f = features.get(i).split("\t", -1);
      String decision = f[0] + "\t" + (accepts(f, weights, cutoff) ? "1" : "0");
      if (!decision.equals(expected.get(i))) throw new AssertionError("Decision mismatch: " + f[0]);
    }
    System.out.println("Verified 2160 Java selector decisions");
  }
}
