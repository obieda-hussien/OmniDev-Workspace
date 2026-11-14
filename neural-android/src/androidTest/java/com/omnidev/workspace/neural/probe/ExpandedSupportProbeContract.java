package com.omnidev.workspace.neural.probe;

import com.omnidev.workspace.domain.neural.*;
import java.io.*;
import java.util.*;

/** Original-reference and score parity for the separate bilingual field-fusion candidate. */
public final class ExpandedSupportProbeContract {
  public static int verify(Reader documents, Reader questions, Reader predictions)
      throws Exception {
    List<ProductSupportModel.Article> corpus = new ArrayList<>();
    Map<String, ProductSupportModel.Article> original = new HashMap<>();
    BufferedReader input = new BufferedReader(documents);
    String line;
    while ((line = input.readLine()) != null) {
      String[] r = line.split("\t", -1);
      if (r.length != 7) throw new AssertionError("Hybrid document schema");
      ProductSupportModel.Article a =
          new ProductSupportModel.Article(
              r[0], r[1], r[2], r[3], r[4].replace("\\n", "\n"), r[5], r[6]);
      if (original.put(a.id, a) != null) throw new AssertionError("Duplicate source");
      corpus.add(a);
    }
    if (corpus.size() != 58) throw new AssertionError("Incomplete hybrid corpus");
    HybridSupportRetriever candidate = TrainedExpandedSupport.createCandidate(corpus);
    ProductSupportModel retained = TrainedExpandedSupport.retainedDefault(corpus),
        prior = TrainedProductSupport.create(corpus);
    Map<String, String> queries = new LinkedHashMap<>();
    input = new BufferedReader(questions);
    if (!"id\tsplit\tlanguage\tprimary\tsecondary\tquery".equals(input.readLine()))
      throw new AssertionError("Hybrid question schema");
    while ((line = input.readLine()) != null) {
      String[] r = line.split("\t", -1);
      if (r.length != 6 || queries.put(r[0], r[5]) != null)
        throw new AssertionError("Duplicate hybrid query");
    }
    if (queries.size() != 1101) throw new AssertionError("Incomplete hybrid queries");
    Map<String, String[]> expected = new HashMap<>();
    input = new BufferedReader(predictions);
    while ((line = input.readLine()) != null) {
      String[] r = line.split("\t", -1);
      if (r.length < 2
          || !(r[1].equals("true") || r[1].equals("false"))
          || !queries.containsKey(r[0])
          || expected.put(r[0], r) != null) throw new AssertionError("Hybrid prediction schema");
    }
    if (expected.size() != 1101) throw new AssertionError("Missing hybrid predictions");
    int probes = 0;
    for (Map.Entry<String, String> q : queries.entrySet()) {
      String[] reference = expected.remove(q.getKey());
      HybridSupportRetriever.Result r = candidate.search(q.getValue(), 10);
      if (reference == null
          || r.accepted != Boolean.parseBoolean(reference[1])
          || r.matches.size() != reference.length - 2)
        throw new AssertionError("Hybrid decision changed");
      for (int i = 0; i < r.matches.size(); i++) {
        HybridSupportRetriever.Hit hit = r.matches.get(i);
        String[] p = reference[i + 2].split(":");
        if (p.length != 3) throw new AssertionError("Hybrid hit schema");
        double score = Double.parseDouble(p[1]), coverage = Double.parseDouble(p[2]);
        if (!Double.isFinite(score)
            || !Double.isFinite(coverage)
            || score < 0
            || score > 1
            || coverage < 0
            || coverage > 1
            || !hit.topic.id.equals(p[0])
            || Math.abs(hit.score - score) > 1e-6
            || Math.abs(hit.coverage - coverage) > 1e-6
            || hit.topic.references.isEmpty()
            || hit.topic.references.size() > 2)
          throw new AssertionError("Hybrid score/range changed");
        for (ProductSupportModel.Article a : hit.topic.references) {
          ProductSupportModel.Article source = original.get(a.id);
          if (source == null
              || !a.topic.equals(hit.topic.id)
              || !a.text.equals(source.text)
              || !a.question.equals(source.question)
              || !a.status.equals(source.status)
              || !a.editions.equals(source.editions))
            throw new AssertionError("Synthetic/changed hybrid source");
        }
      }
      ProductSupportModel.Result a = prior.search(q.getValue(), 3),
          b = retained.search(q.getValue(), 3);
      if (a.accepted != b.accepted || a.matches.size() != b.matches.size())
        throw new AssertionError("Default acceptance changed");
      for (int i = 0; i < a.matches.size(); i++)
        if (!a.matches.get(i).article.id.equals(b.matches.get(i).article.id)
            || a.matches.get(i).score != b.matches.get(i).score
            || a.matches.get(i).evidence.startOffset != b.matches.get(i).evidence.startOffset
            || a.matches.get(i).evidence.endOffset != b.matches.get(i).evidence.endOffset)
          throw new AssertionError("Default source changed");
      probes++;
    }
    if (!expected.isEmpty()) throw new AssertionError("Unused hybrid probes");
    return probes;
  }
}
