package com.omnidev.workspace.domain.neural;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** Experimental bilingual field fusion. Original documentation remains the only returned text. */
public final class HybridSupportRetriever {
  public enum Mode {
    LEGACY,
    TITLE,
    RRF,
    UNIFORM,
    LEARNED
  }

  public static final String VERSION = "bilingual-fields-light-ar-8-v1";
  public static final int FEATURES = 8;
  private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}_]+");
  private static final Set<String> STOP =
      new HashSet<>(
          Arrays.asList(
              ("a an the is are do does how what where can i my me in on to for of and or with from"
                   + " it this that omni omnidev اي ايه هل ازاي ما من في علي على الي الى انا ده دي"
                   + " هو هي عايز عايزه عاوز عاوزه اومني بتاعي ممكن يعني انا عندي عشان")
                  .split(" ")));

  public static final class Topic {
    public final String id;
    public final List<ProductSupportModel.Article> references;

    private Topic(String id, List<ProductSupportModel.Article> references) {
      this.id = id;
      this.references = Collections.unmodifiableList(new ArrayList<>(references));
    }
  }

  public static final class Hit {
    public final Topic topic;
    public final double score, coverage;

    private Hit(Topic topic, double score, double coverage) {
      this.topic = topic;
      this.score = score;
      this.coverage = coverage;
    }
  }

  public static final class Result {
    public final List<Hit> matches;
    public final boolean accepted;

    private Result(List<Hit> matches, boolean accepted) {
      this.matches = Collections.unmodifiableList(new ArrayList<>(matches));
      this.accepted = accepted;
    }
  }

  public static final class Row {
    public final Topic topic;
    private final float[] features;
    public final double rrf, legacy, coverage;

    private Row(Topic topic, float[] features, double rrf, double legacy) {
      this.topic = topic;
      this.features = features.clone();
      this.rrf = rrf;
      this.legacy = legacy;
      this.coverage = features[5];
    }

    public float[] featuresCopy() {
      return features.clone();
    }
  }

  private static final class Field {
    final List<Map<String, Integer>> counts = new ArrayList<>();
    final Map<String, Integer> df = new HashMap<>();
    final double average;

    Field(List<String> texts, boolean light) {
      int total = 0;
      for (String text : texts) {
        Map<String, Integer> map = new HashMap<>();
        for (String word : tokens(text, light)) map.put(word, map.getOrDefault(word, 0) + 1);
        counts.add(Collections.unmodifiableMap(map));
        for (String word : map.keySet()) df.put(word, df.getOrDefault(word, 0) + 1);
        for (int n : map.values()) total += n;
      }
      average = Math.max(1, total / (double) texts.size());
    }

    double[] score(List<String> query) {
      Set<String> q = new TreeSet<>(query);
      double[] out = new double[counts.size()];
      for (int i = 0; i < out.length; i++) {
        Map<String, Integer> map = counts.get(i);
        int length = 0;
        for (int n : map.values()) length += n;
        for (String word : q) {
          Integer tf = map.get(word);
          if (tf == null) continue;
          double idf = Math.log1p((counts.size() - df.get(word) + .5) / (df.get(word) + .5));
          out[i] += idf * tf * 2.2 / (tf + 1.2 * (.25 + .75 * length / average));
        }
      }
      return out;
    }
  }

  private final ProductSupportModel legacy;
  private final List<Topic> topics;
  private final Map<String, Topic> byId;
  private final List<Set<String>> titleGrams = new ArrayList<>();
  private final Field[] fields;
  private final Mode mode;
  private final TinyNeuralRuntime network;
  private final double minimum, margin, minimumCoverage;

  public HybridSupportRetriever(
      List<ProductSupportModel.Article> corpus,
      Mode mode,
      float[] weights,
      double minimum,
      double margin,
      double minimumCoverage,
      String expectedCorpus) {
    this(corpus, mode, weights, minimum, margin, minimumCoverage, expectedCorpus, VERSION);
  }

  public HybridSupportRetriever(
      List<ProductSupportModel.Article> corpus,
      Mode mode,
      float[] weights,
      double minimum,
      double margin,
      double minimumCoverage,
      String expectedCorpus,
      String expectedVersion) {
    if (!VERSION.equals(expectedVersion))
      throw new IllegalArgumentException("Hybrid feature/checkpoint mismatch");
    if (mode == null || !unit(minimum) || !unit(margin) || !unit(minimumCoverage))
      throw new IllegalArgumentException("Invalid fusion policy");
    legacy = new ProductSupportModel(corpus, null, 0, 0, expectedCorpus);
    this.mode = mode;
    this.minimum = minimum;
    this.margin = margin;
    this.minimumCoverage = minimumCoverage;
    if (mode == Mode.LEARNED) {
      if (weights == null || weights.length != 8)
        throw new IllegalArgumentException("Invalid fusion weights");
      double sum = 0;
      for (float w : weights) {
        if (!Float.isFinite(w) || w < 0 || w > 1)
          throw new IllegalArgumentException("Non-convex fusion");
        sum += w;
      }
      if (Math.abs(sum - 1) > 1e-5)
        throw new IllegalArgumentException("Unnormalized fusion weights");
      network =
          new TinyNeuralRuntime(
              new TinyNeuralRuntime.Dense(
                  8, 1, weights, new float[] {0}, TinyNeuralRuntime.Activation.LINEAR));
    } else network = null;
    Map<String, List<ProductSupportModel.Article>> grouped = new TreeMap<>();
    for (ProductSupportModel.Article article : corpus)
      grouped.computeIfAbsent(article.topic, k -> new ArrayList<>()).add(article);
    List<Topic> ts = new ArrayList<>();
    Map<String, Topic> lookup = new TreeMap<>();
    List<String> bodies = new ArrayList<>(), titles = new ArrayList<>();
    for (Map.Entry<String, List<ProductSupportModel.Article>> e : grouped.entrySet()) {
      List<ProductSupportModel.Article> a = e.getValue();
      a.sort(Comparator.comparing(x -> x.id));
      if (a.size() > 2) throw new IllegalArgumentException("Unbounded translations");
      for (ProductSupportModel.Article item : a)
        if (!item.status.equals(a.get(0).status) || !item.editions.equals(a.get(0).editions))
          throw new IllegalArgumentException("Conflicting translation metadata");
      Topic topic = new Topic(e.getKey(), a);
      ts.add(topic);
      lookup.put(topic.id, topic);
      StringBuilder body = new StringBuilder(), title = new StringBuilder();
      for (ProductSupportModel.Article item : a) {
        body.append(item.text).append('\n');
        title.append(item.question).append('\n');
      }
      bodies.add(body.toString());
      titles.add(title.toString());
      titleGrams.add(Collections.unmodifiableSet(grams(normalize(title.toString(), true))));
    }
    topics = Collections.unmodifiableList(ts);
    byId = Collections.unmodifiableMap(lookup);
    fields =
        new Field[] {
          new Field(bodies, false),
          new Field(titles, false),
          new Field(bodies, true),
          new Field(titles, true)
        };
  }

  public String corpusFingerprint() {
    return legacy.fingerprint();
  }

  public int parameterCount() {
    return network == null ? 0 : network.parameterCount();
  }

  public List<Row> rows(String query) {
    if (query == null || query.length() > 512)
      throw new IllegalArgumentException("Invalid hybrid query");
    List<String> raw = tokens(query, false), light = tokens(query, true);
    if (raw.isEmpty()) return Collections.emptyList();
    Set<String> unique = new TreeSet<>(light), grams = grams(normalize(query, true));
    double[][] scores = new double[4][], ranks = new double[4][];
    for (int f = 0; f < 4; f++) {
      scores[f] = fields[f].score(f < 2 ? raw : light);
      ranks[f] = ranks(scores[f]);
    }
    double[] max = new double[4];
    for (int f = 0; f < 4; f++) for (double score : scores[f]) max[f] = Math.max(max[f], score);
    Map<String, Double> old = new HashMap<>();
    for (ProductSupportModel.Match hit : legacy.candidates(query))
      old.put(hit.article.topic, hit.score);
    List<Row> out = new ArrayList<>();
    for (int i = 0; i < topics.size(); i++) {
      boolean found = false;
      for (int f = 0; f < 4; f++) found |= scores[f][i] > 0;
      if (!found && !old.containsKey(topics.get(i).id)) continue;
      float[] x = new float[8];
      double rrf = 0;
      for (int f = 0; f < 4; f++) {
        x[f] = (float) (max[f] > 0 ? scores[f][i] / max[f] : 0);
        if (ranks[f][i] > 0) rrf += 1 / (60 + ranks[f][i]);
      }
      x[4] = coverage(unique, fields[3].counts.get(i).keySet());
      x[5] = coverage(unique, fields[2].counts.get(i).keySet());
      x[6] = dice(grams, titleGrams.get(i));
      double overlap = 0, total = 0;
      for (String word : unique) {
        double idf = 1 + Math.log((topics.size() + 1.0) / (fields[2].df.getOrDefault(word, 0) + 1));
        total += idf;
        if (fields[2].counts.get(i).containsKey(word)) overlap += idf;
      }
      x[7] = (float) (total == 0 ? 0 : overlap / total);
      out.add(new Row(topics.get(i), x, rrf / (4.0 / 61), old.getOrDefault(topics.get(i).id, 0.0)));
    }
    return Collections.unmodifiableList(out);
  }

  public double score(Row row) {
    double score;
    switch (mode) {
      case LEGACY:
        return row.legacy;
      case TITLE:
        return row.features[3];
      case RRF:
        return row.rrf;
      case UNIFORM:
        score = 0;
        for (float x : row.features) score += x / 8.0;
        return score;
      default:
        score = network.infer(row.features)[0];
        return Math.max(0, Math.min(1, score));
    }
  }

  public Result search(String query, int limit) {
    if (query == null || query.length() > 512 || limit < 1 || limit > 10)
      throw new IllegalArgumentException("Invalid hybrid result bound");
    if (mode != Mode.LEGACY && normalize(query, true).isEmpty())
      return new Result(Collections.emptyList(), false);
    List<Hit> hits = new ArrayList<>();
    for (Row row : rows(query))
      if (mode != Mode.LEGACY || row.legacy > 0)
        hits.add(new Hit(row.topic, score(row), row.coverage));
    hits.sort(
        Comparator.comparingDouble((Hit h) -> h.score).reversed().thenComparing(h -> h.topic.id));
    boolean accepted =
        !hits.isEmpty()
            && hits.get(0).score >= minimum
            && hits.get(0).coverage >= minimumCoverage
            && (hits.size() == 1 || hits.get(0).score - hits.get(1).score >= margin);
    return new Result(hits.subList(0, Math.min(limit, hits.size())), accepted);
  }

  private static boolean unit(double n) {
    return Double.isFinite(n) && n >= 0 && n <= 1;
  }

  private static double[] ranks(double[] scores) {
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < scores.length; i++) if (scores[i] > 0) order.add(i);
    order.sort(
        Comparator.comparingDouble((Integer i) -> scores[i]).reversed().thenComparingInt(i -> i));
    double[] out = new double[scores.length];
    for (int i = 0; i < order.size(); i++) out[order.get(i)] = i + 1;
    return out;
  }

  private static float coverage(Set<String> q, Set<String> doc) {
    int found = 0;
    for (String word : q) if (doc.contains(word)) found++;
    return q.isEmpty() ? 0 : (float) found / q.size();
  }

  private static float dice(Set<String> a, Set<String> b) {
    int found = 0;
    for (String gram : a) if (b.contains(gram)) found++;
    return a.isEmpty() && b.isEmpty() ? 0 : (float) (2.0 * found / (a.size() + b.size()));
  }

  private static Set<String> grams(String s) {
    Set<String> out = new HashSet<>();
    for (int i = 0; i + 3 <= s.length(); i++) out.add(s.substring(i, i + 3));
    return out;
  }

  static String normalize(String text, boolean light) {
    String s =
        Normalizer.normalize(text, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT)
            .replaceAll("[\\u064B-\\u065F\\u0670\\u0640]", "")
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ٱ', 'ا')
            .replace('ى', 'ي');
    if (light) s = s.replace('ة', 'ه');
    if (s.length() > 16384) throw new IllegalArgumentException("Expanded support field bound");
    Matcher words = WORD.matcher(s);
    StringBuilder out = new StringBuilder();
    while (words.find()) {
      String word = words.group();
      if (light) {
        if (STOP.contains(word)) continue;
        if (word.length() >= 6
            && (word.startsWith("وال")
                || word.startsWith("فال")
                || word.startsWith("بال")
                || word.startsWith("كال"))) word = word.substring(3);
        else if (word.length() >= 5 && word.startsWith("لل")) word = word.substring(2);
        else if (word.length() >= 5 && word.startsWith("ال")) word = word.substring(2);
      }
      if (word.length() > 1) {
        if (out.length() > 0) out.append(' ');
        out.append(word);
      }
    }
    return out.toString();
  }

  private static List<String> tokens(String s, boolean light) {
    String n = normalize(s, light);
    return n.isEmpty() ? Collections.emptyList() : Arrays.asList(n.split(" "));
  }
}
