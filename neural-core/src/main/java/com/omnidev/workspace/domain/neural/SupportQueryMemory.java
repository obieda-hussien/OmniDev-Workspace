package com.omnidev.workspace.domain.neural;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/**
 * TRAIN wording is a retrieval hint. Only the original reviewed articles are returned as evidence.
 */
public final class SupportQueryMemory {
  public static final String VERSION = "train-query-tfidf-word12-char34-v1";

  public static final class Exemplar {
    public final String id, group, topic, text;

    public Exemplar(String id, String group, String topic, String text) {
      if (!label(id)
          || !label(group)
          || !label(topic)
          || text == null
          || text.length() > 512
          || text.trim().isEmpty()
          || text.indexOf('\t') >= 0
          || text.indexOf('\n') >= 0)
        throw new IllegalArgumentException("Invalid training exemplar");
      this.id = id;
      this.group = group;
      this.topic = topic;
      this.text = text;
    }
  }

  public static final class Topic {
    public final String id;
    public final List<ProductSupportModel.Article> references;

    Topic(String id, List<ProductSupportModel.Article> refs) {
      this.id = id;
      references = Collections.unmodifiableList(new ArrayList<>(refs));
    }
  }

  public static final class Row {
    public final Topic topic;
    public final double coverage;
    private final float[] x;

    Row(Topic topic, float[] x, double coverage) {
      this.topic = topic;
      this.x = x.clone();
      this.coverage = coverage;
    }

    public float[] featuresCopy() {
      return x.clone();
    }
  }

  public static final class Hit {
    public final Topic topic;
    public final double score, coverage;

    Hit(Row r, double score) {
      topic = r.topic;
      this.score = score;
      coverage = r.coverage;
    }
  }

  public static final class Result {
    public final List<Hit> matches;
    public final boolean accepted;

    Result(List<Hit> hits, boolean accepted) {
      matches = Collections.unmodifiableList(new ArrayList<>(hits));
      this.accepted = accepted;
    }
  }

  /** Exact index counts and primitive array payload only; not retained heap or APK size. */
  public static final class IndexStats {
    public final int wordTerms, characterTerms, similarityPostings, coveragePostings;
    public final long primitiveArrayPayloadBytes;

    private IndexStats(
        int wordTerms, int characterTerms, int similarity, int coverage, int examples) {
      this.wordTerms = wordTerms;
      this.characterTerms = characterTerms;
      similarityPostings = similarity;
      coveragePostings = coverage;
      // Coverage aliases word postings; it owns no additional arrays.
      primitiveArrayPayloadBytes = 12L * similarity + 4L * examples;
    }
  }

  private static final class View {
    static final class Term {
      final double idf;
      final int[] examples;
      final double[] values;

      Term(double idf, int count) {
        this.idf = idf;
        examples = new int[count];
        values = new double[count];
      }
    }

    final Map<String, Term> dictionary = new HashMap<>();
    final int size;
    final double unknownIdf;

    View(List<Exemplar> examples, boolean chars) {
      size = examples.size();
      unknownIdf = 1 + Math.log(size + 1.0);
      Map<String, Integer> df = new TreeMap<>();
      List<Map<String, Integer>> raw = new ArrayList<>();
      for (Exemplar e : examples) {
        Map<String, Integer> terms = terms(e.text, chars);
        raw.add(terms);
        for (String t : terms.keySet()) df.put(t, df.getOrDefault(t, 0) + 1);
      }
      if (df.size() > 100000) throw new IllegalArgumentException("Unbounded exemplar vocabulary");
      for (Map.Entry<String, Integer> e : df.entrySet())
        dictionary.put(
            e.getKey(), new Term(1 + Math.log((size + 1.0) / (e.getValue() + 1)), e.getValue()));
      Map<String, Integer> positions = new HashMap<>();
      for (int i = 0; i < raw.size(); i++) {
        Map<String, Integer> row = raw.get(i);
        double[] values = values(row);
        int j = 0;
        for (String key : row.keySet()) {
          Term term = dictionary.get(key);
          int offset = positions.getOrDefault(key, 0);
          term.examples[offset] = i;
          term.values[offset] = values[j++];
          positions.put(key, offset + 1);
        }
      }
    }

    double[] values(Map<String, Integer> terms) {
      double[] out = new double[terms.size()];
      double norm = 0;
      int i = 0;
      for (Map.Entry<String, Integer> e : terms.entrySet()) {
        Term term = dictionary.get(e.getKey());
        double value = (1 + Math.log(e.getValue())) * (term == null ? unknownIdf : term.idf);
        out[i++] = value;
        norm += value * value;
      }
      double divisor = Math.sqrt(norm);
      if (divisor > 0) for (i = 0; i < out.length; i++) out[i] /= divisor;
      return out;
    }

    double[] scores(String normalized, boolean chars) {
      Map<String, Integer> q = terms(normalized, chars);
      double[] values = values(q);
      double[] scores = new double[size];
      // Query terms remain sorted, preserving the original dot-product accumulation order.
      int i = 0;
      for (String key : q.keySet()) {
        Term term = dictionary.get(key);
        double value = values[i++];
        if (term != null)
          for (int p = 0; p < term.examples.length; p++)
            scores[term.examples[p]] += value * term.values[p];
      }
      for (i = 0; i < size; i++) scores[i] = Math.min(1, Math.max(0, scores[i]));
      return scores;
    }
  }

  private final HybridSupportRetriever base;
  private final String[] exampleGroups;
  private final List<Topic> topics;
  private final int[] exampleTopics;
  private final Map<String, Integer> topicIndex = new TreeMap<>();
  private final View words, characters;
  private final TinyNeuralRuntime head;
  private final double minimum, margin, coverage;
  private final String fingerprint;

  public SupportQueryMemory(
      List<ProductSupportModel.Article> corpus,
      HybridSupportRetriever base,
      List<Exemplar> examples,
      float[] weights,
      double minimum,
      double margin,
      double coverage,
      String expectedFingerprint,
      String expectedVersion) {
    if (!VERSION.equals(expectedVersion)
        || base == null
        || examples == null
        || examples.isEmpty()
        || examples.size() > 512
        || weights == null
        || weights.length != 3
        || !unit(minimum)
        || !unit(margin)
        || !unit(coverage)) throw new IllegalArgumentException("Invalid query-memory contract");
    ProductSupportModel original = new ProductSupportModel(corpus, null, 0, 0, null);
    if (!original.fingerprint().equals(base.corpusFingerprint()))
      throw new IllegalArgumentException("Foreign base corpus");
    double sum = 0;
    for (float w : weights) {
      if (!unit(w)) throw new IllegalArgumentException("Invalid query-memory weights");
      sum += w;
    }
    if (Math.abs(sum - 1) > 1e-5)
      throw new IllegalArgumentException("Non-convex query-memory weights");
    this.base = base;
    this.minimum = minimum;
    this.margin = margin;
    this.coverage = coverage;
    head =
        new TinyNeuralRuntime(
            new TinyNeuralRuntime.Dense(
                3, 1, weights, new float[] {0}, TinyNeuralRuntime.Activation.LINEAR));
    Map<String, List<ProductSupportModel.Article>> grouped = new TreeMap<>();
    for (ProductSupportModel.Article a : corpus)
      grouped.computeIfAbsent(a.topic, k -> new ArrayList<>()).add(a);
    List<Topic> ts = new ArrayList<>();
    for (Map.Entry<String, List<ProductSupportModel.Article>> e : grouped.entrySet()) {
      e.getValue().sort(Comparator.comparing(a -> a.id));
      topicIndex.put(e.getKey(), ts.size());
      ts.add(new Topic(e.getKey(), e.getValue()));
    }
    topics = Collections.unmodifiableList(ts);
    List<Exemplar> copy = new ArrayList<>(examples);
    for (Exemplar e : copy) if (e == null) throw new IllegalArgumentException("Null exemplar");
    copy.sort(Comparator.comparing(e -> e.id));
    Set<String> ids = new HashSet<>();
    Map<String, String> groups = new HashMap<>();
    exampleTopics = new int[copy.size()];
    exampleGroups = new String[copy.size()];
    for (Exemplar e : copy) {
      if (e == null || !ids.add(e.id) || !topicIndex.containsKey(e.topic))
        throw new IllegalArgumentException("Duplicate/foreign exemplar");
      String old = groups.put(e.group, e.topic);
      if (old != null && !old.equals(e.topic))
        throw new IllegalArgumentException("Conflicting exemplar group");
      int index = ids.size() - 1;
      exampleTopics[index] = topicIndex.get(e.topic);
      exampleGroups[index] = e.group;
    }
    fingerprint = fingerprint(original.fingerprint(), copy);
    if (expectedFingerprint != null && !expectedFingerprint.equals(fingerprint))
      throw new IllegalArgumentException("Query-memory fingerprint mismatch");
    words = new View(copy, false);
    characters = new View(copy, true);
  }

  public String fingerprint() {
    return fingerprint;
  }

  String contractFingerprint() {
    return new CheckpointFingerprint(VERSION)
        .text(fingerprint)
        .text(base.rankingFingerprint())
        .text(head.checkpointFingerprint())
        .number(minimum)
        .number(margin)
        .number(coverage)
        .finish();
  }

  public int parameterCount() {
    return head.parameterCount();
  }

  public IndexStats indexStats() {
    int similarity = 0, coverage = 0;
    for (Map.Entry<String, View.Term> entry : words.dictionary.entrySet()) {
      similarity += entry.getValue().examples.length;
      if (entry.getKey().startsWith("w:")) coverage += entry.getValue().examples.length;
    }
    for (View.Term term : characters.dictionary.values()) similarity += term.examples.length;
    return new IndexStats(
        words.dictionary.size(),
        characters.dictionary.size(),
        similarity,
        coverage,
        exampleGroups.length);
  }

  public List<Row> rows(String query, String excludedGroup) {
    if (query == null || query.length() > 512 || excludedGroup != null && !label(excludedGroup))
      throw new IllegalArgumentException("Invalid query-memory request");
    Set<String> q = wordTokens(query);
    if (q.isEmpty()) return Collections.emptyList();
    Map<String, HybridSupportRetriever.Row> old = new TreeMap<>();
    for (HybridSupportRetriever.Row r : base.rows(query)) old.put(r.topic.id, r);
    double[] w = words.scores(query, false),
        c = characters.scores(query, true),
        wc = new double[topics.size()],
        cc = new double[topics.size()],
        coverage = new double[topics.size()];
    int[] overlap = new int[exampleGroups.length];
    for (String token : q) {
      View.Term matching = words.dictionary.get("w:" + token);
      if (matching != null) for (int index : matching.examples) overlap[index]++;
    }
    for (int i = 0; i < exampleGroups.length; i++) {
      if (exampleGroups[i].equals(excludedGroup)) continue;
      int topic = exampleTopics[i];
      wc[topic] = Math.max(wc[topic], w[i]);
      cc[topic] = Math.max(cc[topic], c[i]);
      coverage[topic] = Math.max(coverage[topic], overlap[i] / (double) q.size());
    }
    List<Row> rows = new ArrayList<>();
    for (int i = 0; i < topics.size(); i++) {
      Topic t = topics.get(i);
      HybridSupportRetriever.Row b = old.get(t.id);
      float score = b == null ? 0 : (float) base.score(b);
      double cov = Math.max(coverage[i], b == null ? 0 : b.coverage);
      if (score > 0 || wc[i] > 0 || cc[i] > 0)
        rows.add(new Row(t, new float[] {score, (float) wc[i], (float) cc[i]}, cov));
    }
    return Collections.unmodifiableList(rows);
  }

  public double score(Row r) {
    return Math.max(0, Math.min(1, head.infer(r.x)[0]));
  }

  public Result search(String query, int limit) {
    if (limit < 1 || limit > 10) throw new IllegalArgumentException("Invalid memory result bound");
    List<Hit> hits = new ArrayList<>();
    for (Row r : rows(query, null)) {
      double score = score(r);
      if (score > 0) hits.add(new Hit(r, score));
    }
    hits.sort(
        Comparator.comparingDouble((Hit h) -> h.score).reversed().thenComparing(h -> h.topic.id));
    return new Result(hits.subList(0, Math.min(limit, hits.size())), accepts(hits));
  }

  boolean accepts(List<Hit> hits) {
    return !hits.isEmpty()
        && hits.get(0).score >= minimum
        && hits.get(0).coverage >= coverage
        && (hits.size() == 1 || hits.get(0).score - hits.get(1).score >= margin);
  }

  private static boolean label(String s) {
    return s != null && s.matches("[A-Za-z0-9_.:-]{1,120}");
  }

  private static boolean unit(double x) {
    return Double.isFinite(x) && x >= 0 && x <= 1;
  }

  private static Set<String> wordTokens(String text) {
    String n = HybridSupportRetriever.normalize(text, true);
    return n.isEmpty() ? new TreeSet<>() : new TreeSet<>(Arrays.asList(n.split(" ")));
  }

  private static Map<String, Integer> terms(String text, boolean chars) {
    String normalized = HybridSupportRetriever.normalize(text, true);
    Map<String, Integer> out = new TreeMap<>();
    if (normalized.isEmpty()) return out;
    String[] tokens = normalized.split(" ");
    for (int i = 0; i < tokens.length; i++) {
      if (chars) {
        String s = "^" + tokens[i] + "$";
        for (int n = 3; n <= 4; n++)
          for (int j = 0; j + n <= s.length(); j++) {
            String term = n + ":" + s.substring(j, j + n);
            out.put(term, out.getOrDefault(term, 0) + 1);
          }
      } else {
        out.put("w:" + tokens[i], out.getOrDefault("w:" + tokens[i], 0) + 1);
        if (i > 0) {
          String t = "b:" + tokens[i - 1] + " " + tokens[i];
          out.put(t, out.getOrDefault(t, 0) + 1);
        }
      }
    }
    return out;
  }

  private static String fingerprint(String corpus, List<Exemplar> examples) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update((VERSION + "\n" + corpus + "\n").getBytes(StandardCharsets.UTF_8));
      for (Exemplar e : examples)
        digest.update(
            (e.id + "\t" + e.group + "\t" + e.topic + "\t" + e.text + "\n")
                .getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder();
      for (byte b : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", b & 255));
      return out.toString();
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
