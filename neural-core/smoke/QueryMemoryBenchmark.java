package com.omnidev.workspace.domain.neural;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Descriptive host end-to-end latency; deliberately excluded from numerical reproduction gates. */
public final class QueryMemoryBenchmark {
  public static void main(String[] args) throws Exception {
    Path out = Paths.get(args[0]);
    List<ProductSupportModel.Article> docs =
        ProductSupportExperiment.loadArticles(Paths.get("build/product-support/documents.tsv"));
    HybridSupportRetriever base = QueryMemoryExperiment.base(docs);
    long start = System.nanoTime();
    SupportQueryMemory m =
        TrainedQueryMemory.createCandidate(
            docs, QueryMemoryExperiment.examples(out.resolve("exemplars.tsv")));
    long init = System.nanoTime() - start;
    List<HybridSupportExperiment.Query> qs = ExpandedSupportExperiment.load("development", base);
    for (int i = 0; i < 20; i++) {
      String q = qs.get(i % qs.size()).text;
      base.search(q, 3);
      m.search(q, 3);
    }
    long[] a = new long[5], b = new long[5];
    int checksum = 0;
    for (int r = 0; r < 5; r++) {
      start = System.nanoTime();
      for (int i = 0; i < 32; i++)
        checksum += base.search(qs.get((r * 17 + i) % qs.size()).text, 3).matches.size();
      a[r] = (System.nanoTime() - start) / 32;
      start = System.nanoTime();
      for (int i = 0; i < 32; i++)
        checksum += m.search(qs.get((r * 17 + i) % qs.size()).text, 3).matches.size();
      b[r] = (System.nanoTime() - start) / 32;
    }
    Arrays.sort(a);
    Arrays.sort(b);
    String json =
        "{\"host_only\":true,\"physical_device_tested\":false,\"whole_retrieval_included\":true,\"warmup_queries\":20,\"rounds\":5,\"queries_per_round\":32,\"initialization_ns\":"
            + init
            + ",\"base_median_batch_mean_ns\":"
            + a[2]
            + ",\"memory_median_batch_mean_ns\":"
            + b[2]
            + ",\"checksum\":"
            + checksum
            + ",\"peak_heap_measured\":false,\"timing_assertions\":false}";
    Files.write(out.resolve("benchmark.json"), json.getBytes(StandardCharsets.UTF_8));
    System.out.println(json);
  }
}
