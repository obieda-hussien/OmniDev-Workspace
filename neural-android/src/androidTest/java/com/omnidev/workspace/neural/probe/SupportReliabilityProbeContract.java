package com.omnidev.workspace.neural.probe;

import com.omnidev.workspace.domain.neural.*;
import java.io.*;
import java.util.*;

/** Same standalone learned-selector/source/default contract on host and ART. */
public final class SupportReliabilityProbeContract {
  public static int verify(Reader documents, Reader questions, Reader predictions, Reader exemplars)
      throws Exception {
    List<ProductSupportModel.Article> docs = new ArrayList<>();
    Map<String, ProductSupportModel.Article> original = new HashMap<>();
    BufferedReader input = new BufferedReader(documents);
    String line;
    while ((line = input.readLine()) != null) {
      String[] r = line.split("\t", -1);
      if (r.length != 7) throw new AssertionError("Reliability document columns");
      ProductSupportModel.Article article =
          new ProductSupportModel.Article(
              r[0], r[1], r[2], r[3], r[4].replace("\\n", "\n"), r[5], r[6]);
      if (original.put(article.id, article) != null)
        throw new AssertionError("Duplicate reliability source");
      docs.add(article);
    }
    if (docs.size() != 58) throw new AssertionError("Incomplete reliability corpus");
    input = new BufferedReader(exemplars);
    if (!"id\tgroup\ttopic\ttext".equals(input.readLine()))
      throw new AssertionError("Reliability exemplar schema");
    List<SupportQueryMemory.Exemplar> examples = new ArrayList<>();
    while ((line = input.readLine()) != null) {
      String[] r = line.split("\t", -1);
      if (r.length != 4) throw new AssertionError("Reliability exemplar columns");
      examples.add(new SupportQueryMemory.Exemplar(r[0], r[1], r[2], r[3]));
    }
    if (examples.size() != 351) throw new AssertionError("Incomplete reliability exemplars");
    SupportReliabilityModel model = TrainedSupportReliability.createCandidate(docs, examples);
    SupportQueryMemory frozen = TrainedQueryMemory.createCandidate(docs, examples);
    ProductSupportModel retained = TrainedSupportReliability.retainedDefault(docs),
        prior = TrainedProductSupport.create(docs);
    Map<String, String> queries = new LinkedHashMap<>();
    input = new BufferedReader(questions);
    if (!"id\tsplit\tlanguage\tprimary\tsecondary\tquery".equals(input.readLine()))
      throw new AssertionError("Reliability query schema");
    while ((line = input.readLine()) != null) {
      String[] r = line.split("\t", -1);
      if (r.length != 6 || queries.put(r[0], r[5]) != null)
        throw new AssertionError("Duplicate reliability query");
    }
    if (queries.size() != 1368) throw new AssertionError("Incomplete reliability queries");
    Map<String, String[]> expected = new HashMap<>();
    input = new BufferedReader(predictions);
    while ((line = input.readLine()) != null) {
      String[] r = line.split("\t", -1);
      if (r.length < 5
          || !bool(r[1])
          || !bool(r[2])
          || !bool(r[3])
          || !queries.containsKey(r[0])
          || expected.put(r[0], r) != null)
        throw new AssertionError("Reliability prediction schema");
    }
    if (expected.size() != 1368) throw new AssertionError("Incomplete reliability predictions");
    int count = 0;
    for (Map.Entry<String, String> query : queries.entrySet()) {
      String[] e = expected.remove(query.getKey());
      SupportReliabilityModel.Result r = model.search(query.getValue(), 10);
      SupportQueryMemory.Result old = frozen.search(query.getValue(), 10);
      double probability = Double.parseDouble(e[4]);
      if (!unit(probability)
          || Math.abs(probability - r.reliability) > 2e-6
          || r.accepted != Boolean.parseBoolean(e[1])
          || r.learnedAccepted != Boolean.parseBoolean(e[2])
          || r.baselineAccepted != Boolean.parseBoolean(e[3])
          || r.matches.size() != e.length - 5
          || old.accepted != r.baselineAccepted
          || old.matches.size() != r.matches.size())
        throw new AssertionError("Reliability numeric/typed decision drift");
      for (int i = 0; i < r.matches.size(); i++) {
        SupportQueryMemory.Hit hit = r.matches.get(i), before = old.matches.get(i);
        String[] p = e[i + 5].split(":");
        if (p.length != 3) throw new AssertionError("Reliability hit schema");
        double score = Double.parseDouble(p[1]), coverage = Double.parseDouble(p[2]);
        if (!unit(score)
            || !unit(coverage)
            || !hit.topic.id.equals(p[0])
            || Math.abs(hit.score - score) > 1e-6
            || Math.abs(hit.coverage - coverage) > 1e-6
            || !before.topic.id.equals(hit.topic.id)
            || before.score != hit.score
            || before.coverage != hit.coverage
            || hit.topic.references.isEmpty()
            || hit.topic.references.size() > 2)
          throw new AssertionError("Reliability changed frozen rank");
        for (ProductSupportModel.Article a : hit.topic.references) {
          ProductSupportModel.Article real = original.get(a.id);
          if (real == null
              || !a.topic.equals(hit.topic.id)
              || !a.question.equals(real.question)
              || !a.text.equals(real.text)
              || !a.status.equals(real.status)
              || !a.editions.equals(real.editions))
            throw new AssertionError("Reliability invented original reference");
        }
      }
      ProductSupportModel.Result a = prior.search(query.getValue(), 3),
          b = retained.search(query.getValue(), 3);
      if (a.accepted != b.accepted || a.matches.size() != b.matches.size())
        throw new AssertionError("Reliability changed retained policy");
      for (int i = 0; i < a.matches.size(); i++)
        if (!a.matches.get(i).article.id.equals(b.matches.get(i).article.id)
            || a.matches.get(i).score != b.matches.get(i).score
            || a.matches.get(i).evidence.startOffset != b.matches.get(i).evidence.startOffset
            || a.matches.get(i).evidence.endOffset != b.matches.get(i).evidence.endOffset)
          throw new AssertionError("Reliability changed retained evidence");
      count++;
    }
    if (!expected.isEmpty()) throw new AssertionError("Unused reliability probes");
    return count;
  }

  private static boolean bool(String value) {
    return value.equals("true") || value.equals("false");
  }

  private static boolean unit(double x) {
    return Double.isFinite(x) && x >= 0 && x <= 1;
  }
}
