package com.omnidev.workspace.domain.neural;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Emit candidate facts from the actual frozen v10 checkpoint for offline fitting and replay. */
public final class TopicRerankFeatures {
  static void emit(
      PrintWriter out,
      SupportContrastiveModel model,
      String id,
      String split,
      String language,
      Set<String> primary,
      String query,
      Map<String, String> golden,
      boolean rescue) {
    SupportContrastiveModel.Result result = model.search(query, 10);
    if (golden != null && !ContrastiveSupportExperiment.probe(id, result).equals(golden.remove(id)))
      throw new AssertionError("Frozen v10 parity: " + id);
    List<String> fields =
        new ArrayList<>(
            Arrays.asList(
                id,
                split,
                language,
                String.join(",", new TreeSet<>(primary)),
                Boolean.toString(result.proposalAccepted),
                Boolean.toString(result.accepted)));
    for (SupportQueryMemory.Hit hit :
        result.matches.subList(0, Math.min(3, result.matches.size()))) {
      double probability =
          (result.accepted || (rescue && hit == result.matches.get(0)))
              ? model.score(query, hit.topic.id)
              : 0;
      fields.add(hit.topic.id + ":" + hit.score + ":" + hit.coverage + ":" + probability);
    }
    out.println(String.join("\t", fields));
  }

  public static void main(String[] args) throws Exception {
    boolean paired = args.length == 2 && args[1].equals("--paired");
    boolean rescue = paired || (args.length == 2 && args[1].equals("--rescue"));
    List<ProductSupportModel.Article> docs =
        ProductSupportExperiment.loadArticles(Paths.get("build/product-support/documents.tsv"));
    List<SupportQueryMemory.Exemplar> exemplars =
        QueryMemoryExperiment.examples(Paths.get("build/query-memory-v5/exemplars.tsv"));
    SupportContrastiveModel model = TrainedContrastiveSupport.createCandidate(docs, exemplars);
    HybridSupportRetriever base = QueryMemoryExperiment.base(docs);
    Map<String, String> roles = new HashMap<>();
    List<HybridSupportExperiment.Query> train = HybridSupportExperiment.old("train", base);
    train.addAll(ExpandedSupportExperiment.load("train", base));
    for (HybridSupportExperiment.Query q : train) roles.put(q.id, "train");
    for (HybridSupportExperiment.Query q : ExpandedSupportExperiment.load("development", base))
      if (roles.put(q.id, "development") != null) throw new AssertionError("Split overlap");
    Map<String, String> golden = new HashMap<>();
    for (String line :
        Files.readAllLines(
            Paths.get("neural-core/checkpoints/contrastive-support-v10/expected.tsv")))
      golden.put(line.substring(0, line.indexOf('\t')), line);
    Set<String> seen = new HashSet<>();
    for (ProductSupportModel.Article doc : docs)
      seen.add(HybridSupportRetriever.normalize(doc.question, true));
    Set<String> topics = new HashSet<>();
    for (ProductSupportModel.Article doc : docs) topics.add(doc.topic);
    try (PrintWriter out =
        new PrintWriter(Files.newBufferedWriter(Paths.get(args[0]), StandardCharsets.UTF_8))) {
      for (AnswerabilitySupportExperiment.Case c :
          ContrastiveSupportExperiment.all(docs, exemplars)) {
        emit(
            out,
            model,
            c.pair.id,
            roles.getOrDefault(c.pair.id, c.pair.split),
            c.pair.language,
            c.primary,
            c.query,
            golden,
            rescue);
        seen.add(HybridSupportRetriever.normalize(c.query, true));
      }
      if (!golden.isEmpty()) throw new AssertionError("Missing old predictions");
      fixture(out, model, topics, seen, "topic-rerank-v11", 16, "rerank11-", "reserved", rescue);
      if (rescue)
        fixture(out, model, topics, seen, "refusal-rescue-v12", 12, "rescue12-", "fresh12", true);
      if (paired) {
        for (String split : Arrays.asList("train", "dev", "test")) {
          int count = split.equals("train") ? 16 : split.equals("dev") ? 8 : 12;
          fixture(
              out,
              model,
              topics,
              seen,
              "paired-rescue-v13/" + split,
              count,
              "paired13-",
              "paired13-" + split,
              true);
        }
      }
      if (out.checkError()) throw new IOException("Feature output failed");
    }
  }

  static void fixture(
      PrintWriter out,
      SupportContrastiveModel model,
      Set<String> topics,
      Set<String> seen,
      String fixture,
      int count,
      String prefix,
      String split,
      boolean rescue)
      throws Exception {
    Set<String> pairs = new HashSet<>();
    List<String> rows =
        Files.readAllLines(Paths.get("neural-core/fixtures/" + fixture + "/pairs.tsv"));
    if (rows.size() != count + 1 || !rows.get(0).equals("topic\tlabel\ten\tar\tmixed"))
      throw new AssertionError("Fixture schema");
    for (String line : rows.subList(1, rows.size())) {
      String[] f = line.split("\t", -1);
      if (f.length != 5
          || !topics.contains(f[0])
          || !f[1].matches("[01]")
          || !pairs.add(f[0] + "/" + f[1])) throw new AssertionError("Fixture topic/label");
      for (int i = 0; i < 3; i++) {
        String lang = Arrays.asList("en", "ar", "mixed").get(i);
        String q = f[i + 2];
        if (q.trim().isEmpty() || !seen.add(HybridSupportRetriever.normalize(q, true)))
          throw new AssertionError("Repeated fixture");
        emit(
            out,
            model,
            prefix + f[0] + "-" + f[1] + "-" + lang,
            split,
            lang,
            f[1].equals("1") ? Collections.singleton(f[0]) : Collections.emptySet(),
            q,
            null,
            rescue);
      }
    }
    for (String key : pairs)
      if (!pairs.contains(key.substring(0, key.length() - 1) + (key.endsWith("0") ? "1" : "0")))
        throw new AssertionError("Missing pair");
  }
}
