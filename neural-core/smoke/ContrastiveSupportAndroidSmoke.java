import com.omnidev.workspace.neural.probe.ContrastiveSupportProbeContract;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class ContrastiveSupportAndroidSmoke {
  static String docs, queries, expected, examples, matched;

  static void check(String d, String q, String p, String e) throws Exception {
    ContrastiveSupportProbeContract.verify(
        new StringReader(d),
        new StringReader(q),
        new StringReader(p),
        new StringReader(e),
        new StringReader(matched));
  }

  static void reject(String d, String q, String p, String e) throws Exception {
    try {
      check(d, q, p, e);
    } catch (AssertionError | IllegalArgumentException invalid) {
      return;
    }
    throw new AssertionError("Corrupt contrastive fixture accepted");
  }

  static String read(Path root, String name) throws Exception {
    return new String(Files.readAllBytes(root.resolve(name)), StandardCharsets.UTF_8);
  }

  public static void main(String[] args) throws Exception {
    Path root = Paths.get(args[0]);
    docs = read(root, "support-documents.tsv");
    queries = read(root, "contrastive-queries.tsv");
    expected = read(root, "contrastive-probes.tsv");
    examples = read(root, "memory-exemplars.tsv");
    matched = read(root, "contrastive-arms.tsv");
    check(docs, queries, expected, examples);
    reject(docs, queries, expected.replaceFirst("\\t(?:true|false)\\t", "\tFORGED\t"), examples);
    reject(docs, queries, expected.substring(expected.indexOf('\n') + 1), examples);
    reject(docs, queries, expected + expected.substring(0, expected.indexOf('\n') + 1), examples);
    reject(docs.replaceFirst("history-titles:en", "foreign:en"), queries, expected, examples);
    String[] lines = expected.split("\n", -1);
    String[] first = lines[0].split("\t", -1);
    String originalProbability = first[4];
    first[4] = "NaN";
    lines[0] = String.join("\t", first);
    reject(docs, queries, String.join("\n", lines), examples);
    first[4] = originalProbability;
    first[5] = "forged:NaN:0.5";
    lines[0] = String.join("\t", first);
    reject(docs, queries, String.join("\n", lines), examples);
    String[] memory = examples.split("\n", -1);
    String[] row = memory[1].split("\t", -1);
    row[3] += " altered";
    memory[1] = String.join("\t", row);
    reject(docs, queries, expected, String.join("\n", memory));
    String saved = matched;
    matched = matched.substring(matched.indexOf('\n') + 1);
    reject(docs, queries, expected, examples);
    String[] armLines = saved.split("\n", -1);
    String[] arm = armLines[0].split("\t", -1);
    arm[3] = "NaN";
    armLines[0] = String.join("\t", arm);
    matched = String.join("\n", armLines);
    reject(docs, queries, expected, examples);
    matched = saved;
    System.out.println(
        "Packaged contrastive contract: 1968 predictions/default checks and 9 corrupt-fixture"
            + " rejections passed; ART execution still required");
  }
}
