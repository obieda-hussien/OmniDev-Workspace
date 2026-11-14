package com.omnidev.workspace.domain.neural;

import com.omnidev.workspace.domain.neural.PublicRefusalCorpus.Row;
import java.util.*;

/** Training-only group-balanced centroids and calibration-only empirical support floors. */
public final class PrototypeSupportTrainer {
  private static final class Group {
    final double[] sum = new double[1024];
    int rows;
  }

  static final class Fit {
    final DomainPrototypeSupport bank;
    final float[] vectors;
    final int[] owners;
    final int[] groupCounts, rowCounts;

    Fit(
        DomainPrototypeSupport bank,
        float[] vectors,
        int[] owners,
        int[] groupCounts,
        int[] rowCounts) {
      this.bank = bank;
      this.vectors = vectors.clone();
      this.owners = owners.clone();
      this.groupCounts = groupCounts.clone();
      this.rowCounts = rowCounts.clone();
    }

    String javaSource() {
      StringBuilder out =
          new StringBuilder(
                  "import com.omnidev.workspace.domain.neural.*;\n"
                      + "public final class TrainedDomainPrototypes {\n"
                      + "public static final String VOCABULARY_SHA256=\"")
              .append(bank.vocabularyFingerprint())
              .append("\";\npublic static final String BANK_SHA256=\"")
              .append(bank.fingerprint())
              .append(
                  "\";\n"
                      + "public static DomainPrototypeSupport model(){return new"
                      + " DomainPrototypeSupport(vectors(),owners(),VOCABULARY_SHA256,DomainPrototypeSupport.VERSION);}\n"
                      + "private static int[] owners(){return new int[]{");
      for (int i = 0; i < owners.length; i++) {
        if (i > 0) out.append(',');
        out.append(owners[i]);
      }
      out.append("};}\n");
      OfflineDecisionTrainer.Model.appendArrayFactory(out, "vectors", vectors);
      return out.append("}\n").toString();
    }
  }

  static final class Calibration {
    final double[] cutoffs;
    final int[] counts;

    Calibration(double[] cutoffs, int[] counts) {
      this.cutoffs = cutoffs.clone();
      this.counts = counts.clone();
    }
  }

  static Fit fit(List<Row> rows, RichTextEncoder encoder, RichIntentRefusalModel teacher) {
    if (rows == null
        || rows.isEmpty()
        || rows.size() > 10_000
        || encoder == null
        || teacher == null
        || !encoder.fingerprint().equals(teacher.featureFingerprint()))
      throw new IllegalArgumentException("Invalid bounded prototype training");
    Map<String, Map<String, Group>> strata = new TreeMap<>();
    Map<String, String> identities = new HashMap<>();
    for (Row r : rows) {
      if (!r.split.equals("train"))
        throw new IllegalArgumentException("Prototypes fit training rows only");
      if (r.label != 0 || !Objects.equals(teacher.classify(r.text, .6, .15).choice, r.expected))
        continue;
      if (!Arrays.asList("ar", "en", "mixed").contains(r.language))
        throw new IllegalArgumentException("Invalid prototype language");
      int domain = UtilityExpertDecisionModel.domain(r.expected);
      String previous = identities.putIfAbsent(r.group, r.expected);
      if (previous != null && !previous.equals(r.expected))
        throw new IllegalArgumentException("Conflicting prototype group labels");
      Group group =
          strata
              .computeIfAbsent(domain + ":" + r.language, k -> new TreeMap<>())
              .computeIfAbsent(r.group, k -> new Group());
      float[] features = encoder.encode(r.text);
      double norm = 0;
      for (float value : features) norm += (double) value * value;
      if (norm == 0) throw new IllegalArgumentException("Zero prototype training signal");
      norm = Math.sqrt(norm);
      for (int i = 0; i < 1024; i++) group.sum[i] += features[i] / norm;
      group.rows++;
    }
    if (strata.size() != 15)
      throw new IllegalArgumentException("Missing prototype domain/language stratum");
    float[] vectors = new float[15360];
    int[] owners = new int[15], groups = new int[15], counts = new int[15];
    int index = 0;
    for (int domain = 0; domain < 5; domain++)
      for (String language : new String[] {"ar", "en", "mixed"}) {
        Map<String, Group> source = strata.get(domain + ":" + language);
        if (source == null || source.isEmpty())
          throw new IllegalArgumentException("Missing prototype stratum");
        double[] mean = new double[1024];
        for (Group group : source.values()) {
          double norm = norm(group.sum);
          if (norm == 0) throw new IllegalArgumentException("Zero prototype group");
          for (int i = 0; i < 1024; i++) mean[i] += group.sum[i] / norm;
          counts[index] += group.rows;
        }
        double norm = norm(mean);
        if (norm == 0) throw new IllegalArgumentException("Zero prototype centroid");
        for (int i = 0; i < 1024; i++) vectors[index * 1024 + i] = (float) (mean[i] / norm);
        owners[index] = domain;
        groups[index] = source.size();
        index++;
      }
    return new Fit(
        new DomainPrototypeSupport(
            vectors, owners, encoder.fingerprint(), DomainPrototypeSupport.VERSION),
        vectors,
        owners,
        groups,
        counts);
  }

  static Calibration calibrate(
      List<Row> rows,
      DomainPrototypeSupport bank,
      RichTextEncoder encoder,
      RichIntentRefusalModel teacher) {
    if (rows == null
        || rows.isEmpty()
        || rows.size() > 10_000
        || bank == null
        || encoder == null
        || teacher == null
        || !bank.vocabularyFingerprint().equals(encoder.fingerprint())
        || !teacher.featureFingerprint().equals(encoder.fingerprint()))
      throw new IllegalArgumentException("Invalid prototype calibration");
    double[] floors = new double[5];
    Arrays.fill(floors, Double.POSITIVE_INFINITY);
    int[] counts = new int[5];
    for (Row r : rows) {
      if (!r.split.equals("calibration"))
        throw new IllegalArgumentException("Support floors require calibration only");
      if (r.label == 0 && Objects.equals(teacher.classify(r.text, .6, .15).choice, r.expected)) {
        int d = UtilityExpertDecisionModel.domain(r.expected);
        floors[d] = Math.min(floors[d], bank.similarity(encoder.encode(r.text), d));
        counts[d]++;
      }
    }
    for (int d = 0; d < 5; d++) floors[d] = counts[d] == 0 ? 1.1 : Math.max(0, floors[d] - 1e-4);
    return new Calibration(floors, counts);
  }

  private static double norm(double[] values) {
    double sum = 0;
    for (double value : values) sum += value * value;
    return Math.sqrt(sum);
  }

  private PrototypeSupportTrainer() {}
}
