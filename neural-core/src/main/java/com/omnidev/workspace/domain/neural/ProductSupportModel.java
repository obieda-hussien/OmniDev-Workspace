package com.omnidev.workspace.domain.neural;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.*;

/**
 * Experimental documentation retrieval. Scores existing quotes; never generates an answer or
 * permission.
 */
public final class ProductSupportModel {
  public static final String FEATURES = "support-pair12-v1";
  public static final int INPUTS = 12, MAX_QUERY = 512;
  private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}_]+");
  private static final Set<String> STOP =
      new HashSet<>(
          Arrays.asList(
              "a an the is are do does how what where can i my me in on to for of and or with from it this that omni omnidev"
                  .split(" ")));

  static {
    STOP.addAll(
        Arrays.asList(
            "اي ايه هل ازاي إزاي ما من في علي على الي الى انا ده دي هو هي عايز عايزه اومني أومني بتاعي"
                .split(" ")));
  }

  public static final class Article {
    public final String id, topic, language, question, text, status, editions;

    public Article(
        String id,
        String topic,
        String language,
        String question,
        String text,
        String status,
        String editions) {
      if (id == null
          || topic == null
          || language == null
          || question == null
          || text == null
          || status == null
          || editions == null
          || !id.matches("[a-z0-9-]{1,80}:(en|ar)")
          || !id.equals(topic + ":" + language)
          || question.isEmpty()
          || question.length() > 400
          || text.isEmpty()
          || text.length() > 2048
          || !Arrays.asList("implemented", "experimental", "planned").contains(status)
          || !Arrays.asList("all", "advanced").contains(editions))
        throw new IllegalArgumentException("Invalid reviewed support article");
      this.id = id;
      this.topic = topic;
      this.language = language;
      this.question = question;
      this.text = text;
      this.status = status;
      this.editions = editions;
    }
  }

  public static final class Match {
    public final Article article;
    public final OmniRamLite.Hit evidence;
    public final double score;

    Match(Article article, OmniRamLite.Hit hit, double score) {
      this.article = article;
      this.evidence = hit;
      this.score = score;
    }
  }

  public static final class Result {
    public final List<Match> matches;
    public final boolean accepted;

    Result(List<Match> matches, boolean accepted) {
      this.matches = Collections.unmodifiableList(new ArrayList<>(matches));
      this.accepted = accepted;
    }
  }

  /**
   * A veto only: a learned proposal cannot upgrade a baseline refusal or change its accepted topic.
   */
  public static final class Guard {
    public final double minimum, margin;

    public Guard(double minimum, double margin) {
      if (!Double.isFinite(minimum)
          || minimum < 0
          || minimum > 1
          || !Double.isFinite(margin)
          || margin < 0
          || margin > 1) throw new IllegalArgumentException("Invalid baseline guard");
      this.minimum = minimum;
      this.margin = margin;
    }
  }

  private static final class Profile {
    final String normalized;
    final Set<String> words, grams;
    final double arabic;

    Profile(String s) {
      normalized = normalize(s);
      words = terms(s);
      grams = trigrams(s);
      arabic = arabicRatio(s);
    }
  }

  private final Map<String, Profile> titleProfiles = new HashMap<>();
  // At most three 900-character passages per bounded article; contains documentation, never
  // queries.
  private final Map<String, Profile> passageProfiles = new HashMap<>();
  private final Guard guard;
  private final Map<String, Article> articles;
  private final Map<String, Integer> frequency;
  private final OmniRamLite index;
  private final TinyNeuralRuntime network;
  private final double minimum, margin;
  private final String fingerprint;

  public ProductSupportModel(
      List<Article> corpus,
      TinyNeuralRuntime network,
      double minimum,
      double margin,
      String expectedFingerprint) {
    this(corpus, network, minimum, margin, expectedFingerprint, null);
  }

  public ProductSupportModel(
      List<Article> corpus,
      TinyNeuralRuntime network,
      double minimum,
      double margin,
      String expectedFingerprint,
      Guard guard) {
    if (corpus == null
        || corpus.isEmpty()
        || corpus.size() > 128
        || network != null && (network.inputSize() != INPUTS || network.outputSize() != 1)
        || !Double.isFinite(minimum)
        || minimum < 0
        || minimum > 1
        || !Double.isFinite(margin)
        || margin < 0
        || margin > 1) throw new IllegalArgumentException("Invalid support model contract");
    Map<String, Article> byId = new TreeMap<>();
    Map<String, Integer> df = new HashMap<>();
    List<OmniRamLite.Document> docs = new ArrayList<>();
    for (Article a : corpus) {
      if (a == null || byId.put(a.id, a) != null)
        throw new IllegalArgumentException("Duplicate article identity");
      docs.add(new OmniRamLite.Document(a.id, a.text));
      titleProfiles.put(a.id, new Profile(a.question));
      for (String word : terms(a.text)) df.put(word, df.getOrDefault(word, 0) + 1);
    }
    this.articles = Collections.unmodifiableMap(byId);
    this.frequency = Collections.unmodifiableMap(df);
    this.index = new OmniRamLite(docs);
    this.network = network;
    this.guard = guard;
    this.minimum = minimum;
    this.margin = margin;
    this.fingerprint = fingerprint(byId);
    if (expectedFingerprint != null && !expectedFingerprint.equals(this.fingerprint))
      throw new IllegalArgumentException("Support corpus/checkpoint mismatch");
  }

  public String fingerprint() {
    return fingerprint;
  }

  public Result search(String query, int limit) {
    if (limit < 1 || limit > 10) throw new IllegalArgumentException("Invalid support result limit");
    List<Match> pool = evidencePool(query), ranked = rank(query, pool, true);
    boolean accept = eligible(ranked, minimum, margin);
    if (guard != null) {
      List<Match> baseline = rank(query, pool, false);
      accept =
          accept
              && eligible(baseline, guard.minimum, guard.margin)
              && ranked.get(0).article.topic.equals(baseline.get(0).article.topic);
    }
    return new Result(ranked.subList(0, Math.min(limit, ranked.size())), accept);
  }

  private static boolean eligible(List<Match> ranked, double minimum, double margin) {
    return !ranked.isEmpty()
        && ranked.get(0).score >= minimum
        && (ranked.size() == 1 || ranked.get(0).score - ranked.get(1).score >= margin);
  }

  /** Existing lexical candidates only; service/topic labels never grant execution. */
  public List<Match> candidates(String query) {
    return rank(query, evidencePool(query), true);
  }

  private List<Match> rank(String query, List<Match> pool, boolean learned) {
    Map<String, Match> topics = new HashMap<>();
    Profile q = new Profile(query);
    for (Match candidate : pool) {
      OmniRamLite.Hit h = candidate.evidence;
      Article a = candidate.article;
      double score =
          !learned || network == null ? candidate.score : sigmoid(network.infer(features(q, h))[0]);
      Match m = new Match(a, h, score), old = topics.get(a.topic);
      if (old == null || order().compare(m, old) < 0) topics.put(a.topic, m);
    }
    List<Match> result = new ArrayList<>(topics.values());
    result.sort(order());
    return Collections.unmodifiableList(result);
  }

  public List<Match> evidencePool(String query) {
    if (query == null || query.length() > MAX_QUERY)
      throw new IllegalArgumentException("Invalid support query");
    List<Match> result = new ArrayList<>();
    for (OmniRamLite.Hit h : index.search(query, 20))
      result.add(new Match(articles.get(h.documentId), h, h.score / (1 + h.score)));
    return Collections.unmodifiableList(result);
  }

  private static Comparator<Match> order() {
    return Comparator.comparingDouble((Match m) -> m.score)
        .reversed()
        .thenComparing(m -> m.evidence.documentId)
        .thenComparingInt(m -> m.evidence.startOffset);
  }

  /** Generic lexical pair features; no query ID, expected topic, split or permission input. */
  public float[] features(String query, OmniRamLite.Hit h) {
    if (query == null
        || query.length() > MAX_QUERY
        || h == null
        || !articles.containsKey(h.documentId))
      throw new IllegalArgumentException("Foreign support evidence");
    return features(new Profile(query), h);
  }

  private float[] features(Profile profile, OmniRamLite.Hit h) {
    Article a = articles.get(h.documentId);
    if (h.startOffset < 0
        || h.endOffset < h.startOffset
        || h.endOffset > a.text.length()
        || !a.text.substring(h.startOffset, h.endOffset).equals(h.quote))
      throw new IllegalArgumentException("Altered support evidence");
    String cacheKey = h.documentId + ":" + h.startOffset + ":" + h.endOffset;
    Profile passage = passage(cacheKey, h.quote), heading = titleProfiles.get(a.id);
    Set<String> q = profile.words,
        body = passage.words,
        title = heading.words,
        intersection = new HashSet<>(q);
    intersection.retainAll(body);
    double weighted = 0, total = 0;
    for (String word : q) {
      double idf = 1 + Math.log((articles.size() + 1.0) / (frequency.getOrDefault(word, 0) + 1));
      total += idf;
      if (body.contains(word)) weighted += idf;
    }
    Set<String> qt = profile.grams, bt = passage.grams, tt = heading.grams;
    String n = profile.normalized, t = heading.normalized;
    double ar = profile.arabic;
    return new float[] {
      (float) Math.min(1, Math.log1p(h.score) / 5),
      ratio(intersection.size(), q.size()),
      (float) (total == 0 ? 0 : weighted / total),
      dice(qt, bt),
      overlap(q, title),
      dice(qt, tt),
      (float) (a.language.equals("ar") ? ar : 1 - ar),
      ratio(q.size(), 32),
      ratio(intersection.size(), body.size()),
      n.length() > 3 && t.contains(n) ? 1 : 0,
      overlap(serviceTerms(q), serviceTerms(body)),
      (float) Math.min(1, h.quote.length() / 900.0)
    };
  }

  private Profile passage(String key, String quote) {
    synchronized (passageProfiles) {
      Profile existing = passageProfiles.get(key);
      if (existing != null) return existing;
      Profile created = new Profile(quote);
      if (passageProfiles.size() < articles.size() * 3) passageProfiles.put(key, created);
      return created;
    }
  }

  int cachedPassages() {
    synchronized (passageProfiles) {
      return passageProfiles.size();
    }
  }

  private static Set<String> serviceTerms(Set<String> words) {
    Set<String> s = new HashSet<>(words);
    s.retainAll(
        new HashSet<>(
            Arrays.asList(
                "whatsapp telegram discord github mcp shizuku rish lite provider api neural local voice memory واتساب تيليجرام تليجرام ديسكورد جيتهوب شيزوكو"
                    .split(" "))));
    return s;
  }

  private static float overlap(Set<String> q, Set<String> d) {
    Set<String> x = new HashSet<>(q);
    x.retainAll(d);
    return ratio(x.size(), q.size());
  }

  private static float ratio(int a, int b) {
    return b == 0 ? 0 : (float) Math.min(1, (double) a / b);
  }

  private static float dice(Set<String> a, Set<String> b) {
    Set<String> x = new HashSet<>(a);
    x.retainAll(b);
    return a.isEmpty() && b.isEmpty() ? 0 : (float) (2.0 * x.size() / (a.size() + b.size()));
  }

  private static double arabicRatio(String s) {
    int ar = 0, letters = 0;
    for (char c : s.toCharArray())
      if (Character.isLetter(c)) {
        letters++;
        if (c >= '\u0600' && c <= '\u06ff') ar++;
      }
    return letters == 0 ? 0 : (double) ar / letters;
  }

  private static String normalize(String s) {
    return BilingualTextEncoder.normalize(s)
        .replace('ة', 'ه')
        .replaceAll("[^\\p{L}\\p{N}_]+", " ")
        .trim();
  }

  private static Set<String> terms(String s) {
    Set<String> result = new HashSet<>();
    Matcher m = WORD.matcher(normalize(s));
    while (m.find()) if (m.group().length() > 1 && !STOP.contains(m.group())) result.add(m.group());
    return result;
  }

  private static Set<String> trigrams(String s) {
    String n = normalize(s);
    Set<String> result = new HashSet<>();
    for (int i = 0; i + 3 <= n.length(); i++) result.add(n.substring(i, i + 3));
    return result;
  }

  static double sigmoid(double x) {
    return x >= 0 ? 1 / (1 + Math.exp(-x)) : Math.exp(x) / (1 + Math.exp(x));
  }

  private static String fingerprint(Map<String, Article> articles) {
    try {
      MessageDigest hash = MessageDigest.getInstance("SHA-256");
      hash.update(FEATURES.getBytes(StandardCharsets.UTF_8));
      for (Article a : articles.values())
        for (String s :
            new String[] {a.id, a.topic, a.language, a.question, a.text, a.status, a.editions}) {
          hash.update((byte) 0);
          hash.update(s.getBytes(StandardCharsets.UTF_8));
        }
      StringBuilder out = new StringBuilder();
      for (byte b : hash.digest()) out.append(String.format(Locale.ROOT, "%02x", b & 255));
      return out.toString();
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
