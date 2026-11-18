import com.omnidev.workspace.neural.probe.ExpandedSupportProbeContract;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class ExpandedSupportAndroidSmoke {
  static String docs, queries, expected;

  static void check(String d, String q, String p) throws Exception {
    ExpandedSupportProbeContract.verify(
        new StringReader(d), new StringReader(q), new StringReader(p));
  }

  static void reject(String d, String q, String p) throws Exception {
    boolean failed = false;
    try {
      check(d, q, p);
    } catch (AssertionError | IllegalArgumentException invalid) {
      failed = true;
    }
    if (!failed) throw new AssertionError("Corrupt hybrid fixture accepted");
  }

  public static void main(String[] args) throws Exception {
    Path root = Paths.get(args[0]);
    docs =
        new String(
            Files.readAllBytes(root.resolve("support-documents.tsv")), StandardCharsets.UTF_8);
    queries =
        new String(
            Files.readAllBytes(root.resolve("expanded-queries.tsv")), StandardCharsets.UTF_8);
    expected =
        new String(Files.readAllBytes(root.resolve("expanded-probes.tsv")), StandardCharsets.UTF_8);
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
        "Packaged hybrid contract: 1101 predictions/default checks and 5 corrupt-fixture rejections"
            + " passed; ART execution still required");
  }
}
