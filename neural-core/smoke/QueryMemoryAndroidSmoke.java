import com.omnidev.workspace.neural.probe.QueryMemoryProbeContract;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class QueryMemoryAndroidSmoke {
  static String docs, queries, expected, exemplars;

  static void check(String d, String q, String p) throws Exception {
    QueryMemoryProbeContract.verify(
        new StringReader(d), new StringReader(q), new StringReader(p), new StringReader(exemplars));
  }

  static void reject(String d, String q, String p) throws Exception {
    boolean failed = false;
    try {
      check(d, q, p);
    } catch (AssertionError | IllegalArgumentException invalid) {
      failed = true;
    }
    if (!failed) throw new AssertionError("Corrupt memory fixture accepted");
  }

  public static void main(String[] args) throws Exception {
    Path root = Paths.get(args[0]);
    docs =
        new String(
            Files.readAllBytes(root.resolve("support-documents.tsv")), StandardCharsets.UTF_8);
    queries =
        new String(Files.readAllBytes(root.resolve("memory-queries.tsv")), StandardCharsets.UTF_8);
    expected =
        new String(Files.readAllBytes(root.resolve("memory-probes.tsv")), StandardCharsets.UTF_8);
    exemplars =
        new String(
            Files.readAllBytes(root.resolve("memory-exemplars.tsv")), StandardCharsets.UTF_8);
    check(docs, queries, expected);
    reject(docs, queries, expected.replaceFirst("\\t(?:true|false)\\t", "\tFORGED\t"));
    reject(docs, queries, expected.substring(expected.indexOf('\n') + 1));
    reject(docs, queries, expected + expected.substring(0, expected.indexOf('\n') + 1));
    reject(docs.replaceFirst("history-titles:en", "foreign:en"), queries, expected);
    reject(
        docs,
        queries,
        expected.replaceFirst("\\t(?:true|false)\\t[^\\n]*", "\ttrue\tforeign:NaN:1"));
    System.out.println(
        "Packaged query-memory contract: 1239 predictions/default checks and 5 corrupt-fixture rejections"
            + " passed; ART execution still required");
  }
}
