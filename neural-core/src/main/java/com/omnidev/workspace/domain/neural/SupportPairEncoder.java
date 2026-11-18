package com.omnidev.workspace.domain.neural;

import java.util.*;
import java.util.regex.*;

/**
 * Fixed bounded lexical query/reference interactions; not semantic entailment or a fitted
 * vocabulary.
 */
public final class SupportPairEncoder {
  public static final String VERSION = "support-pair-fnv4x256-word-char-body-title-v1";
  public static final int DIMENSIONS = 1024;
  private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}_]+");

  private static final class Document {
    final Set<String> body, title;

    Document(Set<String> body, Set<String> title) {
      this.body = Collections.unmodifiableSet(body);
      this.title = Collections.unmodifiableSet(title);
    }
  }

  private final Map<String, Document> documents;
  private final String fingerprint;

  public SupportPairEncoder(List<ProductSupportModel.Article> corpus) {
    ProductSupportModel identity = new ProductSupportModel(corpus, null, 0, 0, null);
    Map<String, Set<String>> bodies = new TreeMap<>(), titles = new TreeMap<>();
    for (ProductSupportModel.Article article : corpus) {
      bodies
          .computeIfAbsent(article.topic, k -> new HashSet<>())
          .addAll(words(article.text, 2048, 2048));
      titles
          .computeIfAbsent(article.topic, k -> new HashSet<>())
          .addAll(words(article.question, 400, 512));
    }
    Map<String, Document> stored = new TreeMap<>();
    for (String topic : bodies.keySet())
      stored.put(topic, new Document(bodies.get(topic), titles.get(topic)));
    documents = Collections.unmodifiableMap(stored);
    fingerprint = new CheckpointFingerprint(VERSION).text(identity.fingerprint()).finish();
  }

  public String fingerprint() {
    return fingerprint;
  }

  public float[] encode(String query, String topic) {
    Document document = documents.get(topic);
    if (document == null) throw new IllegalArgumentException("Unknown support-pair topic");
    List<String> tokens = words(query, 512, 256);
    float[] x = new float[DIMENSIONS];
    String previous = null;
    for (String token : tokens) {
      add(x, 0, "w:" + token);
      if (previous != null) add(x, 0, "b:" + previous + "|" + token);
      String bounded = "^" + token + "$";
      for (int n = 3; n <= 4; n++)
        for (int j = 0; j + n <= bounded.length(); j++)
          add(x, 256, n + ":" + bounded.substring(j, j + n));
      add(x, 512, (document.body.contains(token) ? "present:" : "missing:") + token);
      add(x, 768, (document.title.contains(token) ? "present:" : "missing:") + token);
      previous = token;
    }
    for (int block = 0; block < 4; block++) {
      double norm = 0;
      int start = block * 256;
      for (int i = start; i < start + 256; i++) {
        x[i] = (float) Math.copySign(Math.log1p(Math.abs(x[i])), x[i]);
        norm += (double) x[i] * x[i];
      }
      if (norm > 0) {
        double divisor = 2 * Math.sqrt(norm);
        for (int i = start; i < start + 256; i++) x[i] /= divisor;
      }
    }
    return x;
  }

  private static List<String> words(String text, int bound, int maximum) {
    if (text == null || text.length() > bound)
      throw new IllegalArgumentException("Support-pair text bound");
    String normalized = HybridSupportRetriever.normalize(text, false);
    Matcher matcher = WORD.matcher(normalized);
    List<String> out = new ArrayList<>();
    while (matcher.find()) {
      String word = matcher.group();
      if (word.length() > 64) continue;
      if (out.size() == maximum) throw new IllegalArgumentException("Support-pair token bound");
      out.add(word);
    }
    return out;
  }

  private static void add(float[] x, int offset, String token) {
    int hash = 0x811c9dc5;
    for (int i = 0; i < token.length(); i++) hash = (hash ^ token.charAt(i)) * 0x01000193;
    x[offset + (hash & 255)] += (hash & 256) == 0 ? 1 : -1;
  }
}
