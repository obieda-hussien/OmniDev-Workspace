package com.omnidev.workspace.domain.neural;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Host-only before/after audit. Does not fit weights or change a policy. */
public final class QueryMemoryRuntimeAudit {
  private static void record(MessageDigest digest, String value) {
    digest.update((value + "\n").getBytes(StandardCharsets.UTF_8));
  }

  public static void main(String[] args) throws Exception {
    List<ProductSupportModel.Article> docs =
        ProductSupportExperiment.loadArticles(Paths.get("build/product-support/documents.tsv"));
    SupportQueryMemory model =
        TrainedQueryMemory.createCandidate(
            docs, QueryMemoryExperiment.examples(Paths.get("build/query-memory-v5/exemplars.tsv")));
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    Map<String, String> groups = QueryMemoryExperiment.trainingGroups();
    Set<String> seen = new HashSet<>();
    int rows = 0, queries = 0;
    for (String fixture :
        new String[] {
          "product-support/queries.tsv",
          "product-support-v2/fresh.tsv",
          "hybrid-support-v3/fresh.tsv",
          "support-data-v4/queries.tsv",
          "query-memory-v5/fresh.tsv"
        }) {
      List<String> lines =
          Files.readAllLines(Paths.get("neural-core/fixtures", fixture), StandardCharsets.UTF_8);
      List<String> header = Arrays.asList(lines.get(0).split("\t"));
      for (String line : lines.subList(1, lines.size())) {
        String[] fields = line.split("\t", -1);
        String id = fields[header.indexOf("id")], query = fields[header.indexOf("query")];
        if (!seen.add(id)) throw new AssertionError("Duplicate audit ID");
        queries++;
        record(digest, id);
        // Also cover whole-group exclusion on every TRAIN row, including its translations.
        for (String excluded : new String[] {null, groups.getOrDefault(id, "absent")}) {
          record(digest, String.valueOf(excluded));
          for (SupportQueryMemory.Row row : model.rows(query, excluded)) {
            rows++;
            record(digest, row.topic.id);
            for (float value : row.featuresCopy())
              record(digest, Integer.toHexString(Float.floatToIntBits(value)));
            record(digest, Long.toHexString(Double.doubleToLongBits(row.coverage)));
            record(digest, Long.toHexString(Double.doubleToLongBits(model.score(row))));
            for (ProductSupportModel.Article ref : row.topic.references)
              record(
                  digest,
                  ref.id
                      + "\t"
                      + ref.question
                      + "\t"
                      + ref.text
                      + "\t"
                      + ref.status
                      + "\t"
                      + ref.editions);
          }
        }
        for (int limit : new int[] {1, 3, 10}) {
          SupportQueryMemory.Result result = model.search(query, limit);
          record(digest, limit + ":" + result.accepted);
          for (SupportQueryMemory.Hit hit : result.matches)
            record(
                digest, hit.topic.id + ":" + Long.toHexString(Double.doubleToLongBits(hit.score)));
        }
      }
    }
    if (queries != 1239) throw new AssertionError("Missing audit queries");
    StringBuilder hex = new StringBuilder();
    for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 255));

    List<HybridSupportExperiment.Query> development =
        ExpandedSupportExperiment.load("development", QueryMemoryExperiment.base(docs));
    for (int i = 0; i < 198; i++) model.search(development.get(i % development.size()).text, 3);
    java.lang.management.ThreadMXBean generic = ManagementFactory.getThreadMXBean();
    ThreadMXBean bean = generic instanceof ThreadMXBean ? (ThreadMXBean) generic : null;
    if (bean != null && bean.isThreadAllocatedMemorySupported())
      bean.setThreadAllocatedMemoryEnabled(true);
    else bean = null;
    long[] nanos = new long[10], allocated = new long[10];
    int checksum = 0;
    for (int r = 0; r < 10; r++) {
      long bytes = bean == null ? -1 : bean.getThreadAllocatedBytes(Thread.currentThread().getId());
      long start = System.nanoTime();
      for (int i = 0; i < 64; i++) {
        SupportQueryMemory.Result result =
            model.search(development.get((r * 17 + i) % development.size()).text, 3);
        checksum += result.matches.size() + (result.accepted ? 1 : 0);
      }
      nanos[r] = (System.nanoTime() - start) / 64;
      allocated[r] =
          bean == null
              ? -1
              : (bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - bytes) / 64;
    }
    String json =
        "{\"host_only\":true,\"physical_device_tested\":false,\"queries\":"
            + queries
            + ",\"rows_including_group_exclusion\":"
            + rows
            + ",\"raw_bits_sha256\":\""
            + hex
            + "\",\"warmup_queries\":198,\"batch_mean_ns\":"
            + Arrays.toString(nanos)
            + ",\"allocated_bytes_per_query_batch_mean\":"
            + Arrays.toString(allocated)
            + ",\"allocation_supported\":"
            + (bean != null)
            + ",\"checksum\":"
            + checksum
            + ",\"retained_heap_measured\":false,\"timing_assertions\":false}";
    Files.write(Paths.get(args[0]), json.getBytes(StandardCharsets.UTF_8));
    System.out.println(json);
  }
}
