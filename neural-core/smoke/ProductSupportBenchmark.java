package com.omnidev.workspace.domain.neural;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Descriptive host batch means only; neither a phone benchmark nor a performance gate. */
public final class ProductSupportBenchmark {
  public static void main(String[] args) throws Exception {
    Path out = Paths.get(args[0]);
    List<ProductSupportModel.Article> corpus =
        ProductSupportExperiment.loadArticles(out.resolve("documents.tsv"));
    ProductSupportModel[] models = {
      TrainedProductSupport.baseline(corpus),
      TrainedProductSupport.createRaw(corpus),
      TrainedProductSupport.create(corpus)
    };
    List<ProductSupportExperiment.Frame> rows =
        ProductSupportExperiment.frames(
            Paths.get("neural-core/fixtures/product-support/queries.tsv"), "train", models[0]);
    for (ProductSupportModel m : models)
      for (int i = 0; i < 64; i++) m.search(rows.get(i % rows.size()).query, 3);
    double[][] times = new double[3][8];
    long checksum = 0;
    for (int batch = 0; batch < 8; batch++)
      for (int order = 0; order < 3; order++) {
        int mode = (batch + order) % 3;
        long start = System.nanoTime();
        for (int i = 0; i < 32; i++) {
          ProductSupportModel.Result r =
              models[mode].search(rows.get((batch * 32 + i) % rows.size()).query, 3);
          checksum += r.matches.size() + (r.accepted ? 1 : 0);
        }
        times[mode][batch] = (System.nanoTime() - start) / 32000.0;
      }
    String[] names = {"lexical", "raw_neural", "guarded_neural"};
    StringBuilder report =
        new StringBuilder(
            "{\"measurement\":\"host microseconds/request; median of eight batch means, 32"
                + " requests/batch, 64 warmups/mode; not mobile latency or peak"
                + " heap\",\"checksum\":"
                + checksum);
    for (int i = 0; i < 3; i++) {
      double[] sorted = times[i].clone();
      Arrays.sort(sorted);
      report
          .append(",\"")
          .append(names[i])
          .append("\":{\"median_batch_mean_us\":")
          .append((sorted[3] + sorted[4]) / 2)
          .append(",\"raw_batch_means_us\":")
          .append(Arrays.toString(times[i]))
          .append('}');
    }
    report.append('}');
    Files.write(
        out.resolve("host-timing.json"), report.toString().getBytes(StandardCharsets.UTF_8));
    System.out.println(report);
  }
}
