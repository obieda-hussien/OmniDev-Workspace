package com.omnidev.workspace.domain.neural;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Descriptive host end-to-end means, excluded from reproducibility equality gates. */
public final class SubwordSupportBenchmark {
  public static void main(String[] args) throws Exception {
    List<ProductSupportModel.Article> docs =
        ProductSupportExperiment.loadArticles(Paths.get("build/product-support/documents.tsv"));
    List<SupportQueryMemory.Exemplar> examples =
        QueryMemoryExperiment.examples(Paths.get("build/query-memory-v5/exemplars.tsv"));
    SupportQueryMemory old = TrainedQueryMemory.createCandidate(docs, examples);
    SupportSubwordModel model = TrainedSubwordSupport.createCandidate(docs, examples);
    List<HybridSupportExperiment.Query> queries =
        ExpandedSupportExperiment.load("development", QueryMemoryExperiment.base(docs));
    for (int i = 0; i < 99; i++) {
      String q = queries.get(i).text;
      old.search(q, 3);
      model.search(q, 3);
    }
    long[] baseline = new long[8], candidate = new long[8];
    int checksum = 0;
    for (int r = 0; r < 8; r++)
      for (int arm = 0; arm < 2; arm++) {
        boolean learned = (r + arm) % 2 == 1;
        long start = System.nanoTime();
        for (int i = 0; i < 32; i++) {
          String q = queries.get((r * 17 + i) % queries.size()).text;
          checksum += learned ? model.search(q, 3).matches.size() : old.search(q, 3).matches.size();
        }
        long elapsed = (System.nanoTime() - start) / 32;
        if (learned) candidate[r] = elapsed;
        else baseline[r] = elapsed;
      }
    String json =
        "{\"host_only\":true,\"physical_device_tested\":false,\"warmup_queries_per_arm\":99,\"rounds\":8,\"queries_per_round\":32,\"baseline_batch_mean_ns\":"
            + Arrays.toString(baseline)
            + ",\"selector_batch_mean_ns\":"
            + Arrays.toString(candidate)
            + ",\"head_parameters\":"
            + model.parameterCount()
            + ",\"checksum\":"
            + checksum
            + ",\"peak_heap_measured\":false,\"timing_assertions\":false}";
    Files.write(
        Paths.get(args[0]).resolve("host-timings.json"), json.getBytes(StandardCharsets.UTF_8));
    System.out.println(json);
  }
}
