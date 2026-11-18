package com.omnidev.workspace.domain.neural;

import static com.omnidev.workspace.domain.neural.AnswerabilitySupportExperiment.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Reload all exported heads and compare typed decisions, original sources and retained defaults.
 */
public final class AnswerabilityCheckpointSmoke {
  static Map<String, String> expected(Path path) throws Exception {
    Map<String, String> out = new HashMap<>();
    for (String line : Files.readAllLines(path, StandardCharsets.UTF_8))
      if (out.put(line.split("\t", 2)[0], line) != null)
        throw new AssertionError("Duplicate answerability probe");
    return out;
  }

  public static void main(String[] args) throws Exception {
    Path out = Paths.get(args[0]);
    List<ProductSupportModel.Article> docs =
        ProductSupportExperiment.loadArticles(Paths.get("build/product-support/documents.tsv"));
    List<SupportQueryMemory.Exemplar> examples =
        QueryMemoryExperiment.examples(Paths.get("build/query-memory-v5/exemplars.tsv"));
    SupportAnswerabilityModel
        original = TrainedAnswerabilitySupport.createCandidate(docs, examples),
        oldArm = TrainedAnswerabilitySupport.createMatchedOriginal(docs, examples),
        pairArm = TrainedAnswerabilitySupport.createMatchedContrast(docs, examples),
        preservation = TrainedAnswerabilityPreservation.createCandidate(docs, examples);
    ProductSupportModel retained = TrainedAnswerabilityPreservation.retainedDefault(docs),
        prior = TrainedProductSupport.create(docs);
    Map<String, String> e = expected(out.resolve("expected.tsv")),
        arms = expected(out.resolve("arm-expected.tsv")),
        p = expected(out.resolve("preservation-expected.tsv"));
    int defaults = 0, sourceChecks = 0, matched = 0;
    for (Case c : AnswerabilityPreservationExperiment.all(docs, examples)) {
      SupportAnswerabilityModel.Result r = preservation.search(c.query, 10),
          before = original.search(c.query, 10);
      if (!probe(c.pair.id, r).equals(p.remove(c.pair.id)))
        throw new AssertionError("Preservation reload drift " + c.pair.id);
      if (!c.pair.split.equals("followup")) {
        if (!probe(c.pair.id, before).equals(e.remove(c.pair.id)))
          throw new AssertionError("Original reload drift");
        SupportAnswerabilityModel.Result a = oldArm.search(c.query, 10),
            b = pairArm.search(c.query, 10);
        if (!armProbe(c.pair.id, a, b).equals(arms.remove(c.pair.id)))
          throw new AssertionError("Matched reload drift");
        matched += 2;
      }
      if (before.accepted != c.proposal.accepted || r.accepted && !c.proposal.accepted)
        throw new AssertionError("Veto upgraded refusal");
      if (r.matches.size() != c.proposal.matches.size())
        throw new AssertionError("Veto rank drift");
      for (int i = 0; i < r.matches.size(); i++) {
        SupportQueryMemory.Hit a = r.matches.get(i), b = c.proposal.matches.get(i);
        if (!a.topic.id.equals(b.topic.id) || a.score != b.score || a.coverage != b.coverage)
          throw new AssertionError("Veto changed ranks");
        for (ProductSupportModel.Article ref : a.topic.references) {
          boolean found = false;
          for (ProductSupportModel.Article real : docs) if (ref == real) found = true;
          if (!found) throw new AssertionError("Veto invented references");
          sourceChecks++;
        }
      }
      if (!RankedSupportExperiment.probe(c.pair.id, "default", prior.search(c.query, 3))
          .equals(RankedSupportExperiment.probe(c.pair.id, "default", retained.search(c.query, 3))))
        throw new AssertionError("Veto changed default");
      defaults++;
    }
    if (defaults != 1782 || matched != 3468 || !e.isEmpty() || !arms.isEmpty() || !p.isEmpty())
      throw new AssertionError("Incomplete veto reload");
    String report =
        "{\"original_predictions\":1734,\"matched_arm_predictions\":3468,\"preservation_predictions\":1782,\"retained_default_checks\":1782,\"original_reference_checks\":"
            + sourceChecks
            + ",\"production_eligible\":false}";
    Files.write(out.resolve("parity-retention.json"), report.getBytes(StandardCharsets.UTF_8));
    System.out.println(report);
  }
}
