package com.omnidev.workspace.neural.probe;

import com.omnidev.workspace.domain.neural.*;
import java.io.*;
import java.util.*;

/** The same evidence/decision contract is exercised on the host and in the opt-in ART test APK. */
public final class SupportRetrievalProbeContract {
  public static int verify(Reader documents, Reader queries, Reader predictions) throws Exception {
    List<ProductSupportModel.Article> corpus = new ArrayList<>();
    BufferedReader input = new BufferedReader(documents);
    String line;
    while ((line = input.readLine()) != null) {
      String[] r = line.split("\t", -1);
      if (r.length != 7) throw new AssertionError("Document schema");
      corpus.add(
          new ProductSupportModel.Article(
              r[0], r[1], r[2], r[3], r[4].replace("\\n", "\n"), r[5], r[6]));
    }
    if (corpus.size() != 58) throw new AssertionError("Missing support corpus");
    Map<String, String> questions = new LinkedHashMap<>();
    input = new BufferedReader(queries);
    if (!"id\tgroup\tsplit\tlanguage\texpected_topic\tquery".equals(input.readLine()))
      throw new AssertionError("Question schema");
    while ((line = input.readLine()) != null) {
      String[] r = line.split("\t", -1);
      if (r.length != 6 || questions.put(r[0], r[5]) != null)
        throw new AssertionError("Question identity");
    }
    if (questions.size() != 351) throw new AssertionError("Missing support questions");
    Map<String, ProductSupportModel> models = new LinkedHashMap<>();
    models.put("lexical", TrainedRankedSupport.baseline(corpus));
    models.put("v1_raw", TrainedProductSupport.createRaw(corpus));
    models.put("v1_guarded", TrainedProductSupport.create(corpus));
    models.put("v2_raw", TrainedRankedSupport.createRaw(corpus));
    models.put("v2_guarded", TrainedRankedSupport.createCandidateGuarded(corpus));
    ProductSupportModel retained = TrainedRankedSupport.create(corpus);
    Map<String, String[]> expected = new HashMap<>();
    input = new BufferedReader(predictions);
    while ((line = input.readLine()) != null) {
      String[] r = line.split("\t", -1);
      if (r.length < 3
          || !questions.containsKey(r[0])
          || !models.containsKey(r[1])
          || !(r[2].equals("true") || r[2].equals("false"))
          || expected.put(r[0] + "\t" + r[1], r) != null)
        throw new AssertionError("Prediction schema/identity");
    }
    if (expected.size() != 1755) throw new AssertionError("Missing support predictions");
    int checked = 0;
    for (Map.Entry<String, String> q : questions.entrySet()) {
      ProductSupportModel.Result before = models.get("v1_guarded").search(q.getValue(), 3),
          after = retained.search(q.getValue(), 3);
      if (before.accepted != after.accepted || before.matches.size() != after.matches.size())
        throw new AssertionError("Default decision changed");
      for (int i = 0; i < before.matches.size(); i++) {
        ProductSupportModel.Match a = before.matches.get(i), b = after.matches.get(i);
        if (!a.article.id.equals(b.article.id)
            || a.score != b.score
            || a.evidence.startOffset != b.evidence.startOffset
            || a.evidence.endOffset != b.evidence.endOffset)
          throw new AssertionError("Default evidence changed");
      }
      for (Map.Entry<String, ProductSupportModel> m : models.entrySet()) {
        String[] reference = expected.remove(q.getKey() + "\t" + m.getKey());
        ProductSupportModel.Result actual = m.getValue().search(q.getValue(), 3);
        if (reference == null
            || actual.accepted != Boolean.parseBoolean(reference[2])
            || actual.matches.size() != reference.length - 3)
          throw new AssertionError("Support decision drift");
        for (int i = 0; i < actual.matches.size(); i++) {
          ProductSupportModel.Match hit = actual.matches.get(i);
          String[] r = reference[i + 3].split(":");
          if (r.length != 5) throw new AssertionError("Quote schema");
          double score = Double.parseDouble(r[4]);
          if (!Double.isFinite(score)
              || score < 0
              || score > 1
              || Math.abs(hit.score - score) > 1e-6
              || !hit.article.id.equals(r[0] + ":" + r[1])
              || !hit.evidence.documentId.equals(hit.article.id)
              || hit.evidence.startOffset != Integer.parseInt(r[2])
              || hit.evidence.endOffset != Integer.parseInt(r[3])
              || !hit.article
                  .text
                  .substring(hit.evidence.startOffset, hit.evidence.endOffset)
                  .equals(hit.evidence.quote))
            throw new AssertionError("Support quote/score drift");
        }
        if (m.getKey().equals("v2_guarded") && actual.accepted) {
          ProductSupportModel.Result baseline = models.get("lexical").search(q.getValue(), 3);
          if (!baseline.accepted
              || !baseline.matches.get(0).article.topic.equals(actual.matches.get(0).article.topic))
            throw new AssertionError("Lexical guard bypass");
        }
        checked++;
      }
    }
    if (checked != 1755 || !expected.isEmpty())
      throw new AssertionError("Unconsumed support predictions");
    return checked;
  }
}
