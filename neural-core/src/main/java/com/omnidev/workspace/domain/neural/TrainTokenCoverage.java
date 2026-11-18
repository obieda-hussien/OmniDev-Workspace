package com.omnidev.workspace.domain.neural;

import java.util.*;

/** TRAIN-only tolerant token coverage. Matches are hints and never replace original references. */
public final class TrainTokenCoverage {
  public static final String VERSION = "train-token-dice34-idf-top128-v1";

  private static final class Term {
    final int[] owners;
    final int grams;
    final double idf;

    Term(int[] owners, int grams, int size) {
      this.owners = owners;
      this.grams = grams;
      idf = 1 + Math.log((size + 1.0) / (owners.length + 1));
    }
  }

  private final String[] words, groups, ownerTopics;
  private final Term[] terms;
  private final Map<String, Integer> lookup = new HashMap<>();
  private final Map<String, int[]> postings = new HashMap<>();
  private final Set<String> topics = new TreeSet<>();
  private final String fingerprint;
  private final double unknownIdf;
  private final int postingCount;

  public TrainTokenCoverage(List<SupportQueryMemory.Exemplar> examples) {
    if (examples == null || examples.isEmpty() || examples.size() > 512)
      throw new IllegalArgumentException("Invalid bounded token corpus");
    List<SupportQueryMemory.Exemplar> sorted = new ArrayList<>(examples);
    for (SupportQueryMemory.Exemplar e : sorted)
      if (e == null) throw new IllegalArgumentException("Null token exemplar");
    sorted.sort(Comparator.comparing(e -> e.id));
    Set<String> ids = new HashSet<>();
    Map<String, String> groupTopics = new HashMap<>();
    Map<String, List<Integer>> ownership = new TreeMap<>();
    groups = new String[sorted.size()];
    ownerTopics = new String[sorted.size()];
    CheckpointFingerprint digest = new CheckpointFingerprint(VERSION);
    for (int i = 0; i < sorted.size(); i++) {
      SupportQueryMemory.Exemplar e = sorted.get(i);
      String previous = groupTopics.put(e.group, e.topic);
      if (!ids.add(e.id) || previous != null && !previous.equals(e.topic))
        throw new IllegalArgumentException("Conflicting token ownership");
      groups[i] = e.group;
      ownerTopics[i] = e.topic;
      topics.add(e.topic);
      digest.text(e.id).text(e.group).text(e.topic).text(e.text);
      for (String word : tokens(e.text)) {
        if (word.length() > 64)
          throw new IllegalArgumentException("Token exceeds bounded vocabulary word length");
        ownership.computeIfAbsent(word, k -> new ArrayList<>()).add(i);
        if (ownership.size() > 8192)
          throw new IllegalArgumentException("Unbounded token vocabulary");
      }
    }
    if (topics.size() > 64) throw new IllegalArgumentException("Unbounded token topics");
    words = ownership.keySet().toArray(new String[0]);
    terms = new Term[words.length];
    Map<String, List<Integer>> pending = new TreeMap<>();
    int count = 0;
    for (int i = 0; i < words.length; i++) {
      List<Integer> list = ownership.get(words[i]);
      int[] owners = new int[list.size()];
      for (int j = 0; j < owners.length; j++) owners[j] = list.get(j);
      Set<String> grams = grams(words[i]);
      terms[i] = new Term(owners, grams.size(), sorted.size());
      lookup.put(words[i], i);
      for (String gram : grams) {
        pending.computeIfAbsent(gram, k -> new ArrayList<>()).add(i);
        if (++count > 400000) throw new IllegalArgumentException("Unbounded gram postings");
      }
    }
    for (Map.Entry<String, List<Integer>> entry : pending.entrySet()) {
      int[] indexes = new int[entry.getValue().size()];
      for (int i = 0; i < indexes.length; i++) indexes[i] = entry.getValue().get(i);
      postings.put(entry.getKey(), indexes);
    }
    fingerprint = digest.finish();
    unknownIdf = 1 + Math.log(sorted.size() + 1.0);
    postingCount = count;
  }

  public String fingerprint() {
    return fingerprint;
  }

  public int wordCount() {
    return words.length;
  }

  public int gramCount() {
    return postings.size();
  }

  public int postingCount() {
    return postingCount;
  }

  public Map<String, Double> coverage(String query, String excludedGroup) {
    if (query == null
        || query.length() > 512
        || excludedGroup != null && !excludedGroup.matches("[A-Za-z0-9_.:-]{1,120}"))
      throw new IllegalArgumentException("Invalid token query");
    Map<String, Double> sums = new TreeMap<>();
    for (String topic : topics) sums.put(topic, 0.0);
    double total = 0;
    for (String word : tokens(query)) {
      Integer exact = lookup.get(word);
      double weight = exact == null ? unknownIdf : terms[exact].idf;
      total += weight;
      if (word.length() > 64) continue;
      Set<String> grams = grams(word);
      int[] common = new int[terms.length];
      List<Integer> touched = new ArrayList<>();
      for (String gram : grams) {
        int[] candidates = postings.get(gram);
        if (candidates != null)
          for (int index : candidates) {
            if (common[index]++ == 0) touched.add(index);
          }
      }
      touched.sort(
          Comparator.comparingInt((Integer i) -> common[i])
              .reversed()
              .thenComparingInt(i -> words[i].equals(word) ? 0 : 1)
              .thenComparing(i -> words[i]));
      Map<String, Double> best = new TreeMap<>();
      for (int j = 0; j < Math.min(128, touched.size()); j++) {
        int i = touched.get(j);
        double dice = 2.0 * common[i] / (grams.size() + terms[i].grams);
        if (dice < .6) continue;
        for (int owner : terms[i].owners)
          if (!groups[owner].equals(excludedGroup))
            best.put(
                ownerTopics[owner], Math.max(best.getOrDefault(ownerTopics[owner], 0.0), dice));
      }
      for (Map.Entry<String, Double> entry : best.entrySet())
        sums.put(entry.getKey(), sums.get(entry.getKey()) + weight * entry.getValue());
    }
    for (Map.Entry<String, Double> entry : sums.entrySet())
      entry.setValue(total == 0 ? 0 : Math.max(0, Math.min(1, entry.getValue() / total)));
    return Collections.unmodifiableMap(sums);
  }

  static Set<String> tokens(String text) {
    String normalized = HybridSupportRetriever.normalize(text, true);
    return normalized.isEmpty()
        ? new TreeSet<>()
        : new TreeSet<>(Arrays.asList(normalized.split(" ")));
  }

  static Set<String> grams(String word) {
    Set<String> out = new TreeSet<>();
    String bounded = "^" + word + "$";
    for (int n = 3; n <= 4; n++)
      for (int j = 0; j + n <= bounded.length(); j++)
        out.add(n + ":" + bounded.substring(j, j + n));
    return out;
  }
}
