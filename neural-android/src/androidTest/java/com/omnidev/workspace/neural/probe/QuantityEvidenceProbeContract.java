package com.omnidev.workspace.neural.probe;

import com.omnidev.workspace.domain.neural.ProductSupportModel.Article;
import com.omnidev.workspace.domain.neural.QuantityEvidence;
import java.io.*;
import java.util.*;

public final class QuantityEvidenceProbeContract {
  public static int verify(Reader documents, Reader cases) throws IOException {
    BufferedReader input = new BufferedReader(documents);
    Map<String, Article> corpus = new HashMap<>();
    String line;
    while ((line = input.readLine()) != null) {
      String[] f = line.split("\t", -1);
      if (f.length != 7) throw new AssertionError("Document columns");
      if (f[2].equals("en"))
        corpus.put(
            f[1], new Article(f[0], f[1], f[2], f[3], f[4].replace("\\n", "\n"), f[5], f[6]));
    }
    if (corpus.size() != 29) throw new AssertionError("Document count");
    input = new BufferedReader(cases);
    Set<String> ids = new HashSet<>();
    while ((line = input.readLine()) != null) {
      String[] f = line.split("\t", -1);
      if (f.length != 7 || !ids.add(f[0]) || !corpus.containsKey(f[1]))
        throw new AssertionError("Invalid case");
      for (int i = 4; i <= 6; i++) if (f[i].equals("-")) f[i] = "";
      QuantityEvidence.Result r = QuantityEvidence.check(f[2], corpus.get(f[1]));
      if (!r.decision.name().equals(f[3])
          || !r.sourceId.equals(f[4])
          || !r.sentence.equals(f[5])
          || !r.value.equals(f[6])) throw new AssertionError("Quantity evidence mismatch: " + f[0]);
    }
    if (ids.size() != 36) throw new AssertionError("Case count");
    return ids.size();
  }
}
