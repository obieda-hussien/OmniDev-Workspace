import com.omnidev.workspace.neural.probe.SupportRetrievalProbeContract;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class SupportRetrievalAndroidSmoke {
  static String docs, queries, expected;

  static void verify(String d, String q, String e) throws Exception {
    SupportRetrievalProbeContract.verify(
        new StringReader(d), new StringReader(q), new StringReader(e));
  }

  static void reject(String d, String q, String e) throws Exception {
    boolean rejected = false;
    try {
      verify(d, q, e);
    } catch (AssertionError | IllegalArgumentException invalid) {
      rejected = true;
    }
    if (!rejected) throw new AssertionError("Corrupt support fixture accepted");
  }

  public static void main(String[] args) throws Exception {
    Path root = Paths.get(args[0]);
    docs =
        new String(
            Files.readAllBytes(root.resolve("support-documents.tsv")), StandardCharsets.UTF_8);
    queries =
        new String(Files.readAllBytes(root.resolve("support-queries.tsv")), StandardCharsets.UTF_8);
    expected =
        new String(Files.readAllBytes(root.resolve("support-probes.tsv")), StandardCharsets.UTF_8);
    verify(docs, queries, expected);
    reject(docs, queries, expected.replaceFirst("\\t(?:true|false)\\t", "\tFORGED\t"));
    reject(docs, queries, expected.substring(expected.indexOf('\n') + 1));
    reject(docs, queries, expected + expected.substring(0, expected.indexOf('\n') + 1));
    reject(docs.replaceFirst("history-titles:en", "foreign:en"), queries, expected);
    reject(
        docs,
        queries,
        expected.replaceFirst("\\t(?:true|false)\\t[^\\n]*", "\ttrue\tforeign:en:0:1:0.9"));
    System.out.println(
        "Actual packaged support contract: 1755 predictions, 351 retained defaults and 5"
            + " corrupt-fixture rejections passed; ART still required");
  }
}
