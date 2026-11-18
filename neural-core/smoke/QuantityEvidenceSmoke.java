package com.omnidev.workspace.domain.neural;

import com.omnidev.workspace.neural.probe.QuantityEvidenceProbeContract;
import java.io.*;
import java.nio.file.*;

public final class QuantityEvidenceSmoke {
  static void witness(String answer, String value) {
    String q = "Which port does the local bridge use?";
    ProductSupportModel.Article d =
        new ProductSupportModel.Article(
            "whatsapp-bridge:en",
            "whatsapp-bridge",
            "en",
            q,
            q + "\n" + answer,
            "implemented",
            "all");
    QuantityEvidence.Result r = QuantityEvidence.check(q, d);
    if (value.isEmpty()
        ? r.decision != QuantityEvidence.Decision.ABSTAIN
        : r.decision != QuantityEvidence.Decision.SUPPORTED
            || !value.equals(r.value)
            || !answer.contains(r.sentence)) throw new AssertionError("Invalid witness: " + answer);
  }

  public static void main(String[] args) throws Exception {
    witness("The bridge binds to 127.0.0.1:4001.", "4001");
    witness("The bridge binds to 127.0.0.1:65536.", "");
    witness("The bridge does not bind to 127.0.0.1:3000.", "");
    witness("The bridge binds to 127.0.0.1:3000. The bridge binds to 127.0.0.1:4001.", "");
    witness("Do we know that?", "");
    try (Reader docs = Files.newBufferedReader(Paths.get(args[0]));
        Reader cases = Files.newBufferedReader(Paths.get(args[1]))) {
      System.out.println(
          "Verified "
              + QuantityEvidenceProbeContract.verify(docs, cases)
              + " reference-conditioned decisions");
    }
  }
}
