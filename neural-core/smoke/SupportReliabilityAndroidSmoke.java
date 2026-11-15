import com.omnidev.workspace.neural.probe.SupportReliabilityProbeContract;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class SupportReliabilityAndroidSmoke {
  static String docs, queries, expected, examples;

  static void check(String d, String q, String p, String e) throws Exception {
    SupportReliabilityProbeContract.verify(
        new StringReader(d), new StringReader(q), new StringReader(p), new StringReader(e));
  }

  static void reject(String d, String q, String p, String e) throws Exception {
    try {
      check(d, q, p, e);
    } catch (AssertionError | IllegalArgumentException invalid) {
      return;
    }
    throw new AssertionError("Corrupt reliability fixture accepted");
  }

  static String read(Path root, String name) throws Exception {
    return new String(Files.readAllBytes(root.resolve(name)), StandardCharsets.UTF_8);
  }

  public static void main(String[] args) throws Exception {
    Path root = Paths.get(args[0]);
    docs = read(root, "support-documents.tsv");
    queries = read(root, "reliability-queries.tsv");
    expected = read(root, "reliability-probes.tsv");
    examples = read(root, "memory-exemplars.tsv");
    check(docs, queries, expected, examples);
    reject(docs, queries, expected.replaceFirst("\\t(?:true|false)\\t", "\tFORGED\t"), examples);
    reject(docs, queries, expected.substring(expected.indexOf('\n') + 1), examples);
    reject(docs, queries, expected + expected.substring(0, expected.indexOf('\n') + 1), examples);
    reject(docs.replaceFirst("history-titles:en", "foreign:en"), queries, expected, examples);
    String[] lines = expected.split("\n", -1);
    String[] first = lines[0].split("\t", -1);
    first[4] = "NaN";
    lines[0] = String.join("\t", first);
    reject(docs, queries, String.join("\n", lines), examples);
    String[] memory = examples.split("\n", -1);
    String[] row = memory[1].split("\t", -1);
    row[3] += " altered";
    memory[1] = String.join("\t", row);
    reject(docs, queries, expected, String.join("\n", memory));
    System.out.println(
        "Packaged reliability contract: 1368 predictions/default checks and 6 corrupt-fixture"
            + " rejections passed; ART execution still required");
  }
}
