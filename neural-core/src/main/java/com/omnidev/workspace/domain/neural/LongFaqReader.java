package com.omnidev.workspace.domain.neural;

import java.util.*;
import java.util.regex.Pattern;

/** Experimental bounded reader for one explicitly delimited product question. */
public final class LongFaqReader {
  public enum Status {
    ANSWER,
    CLARIFY,
    NO_MATCH
  }

  public static final class Reply {
    public final Status status;
    public final String text, sourceId;

    private Reply(Status status, String text, String sourceId) {
      this.status = status;
      this.text = text;
      this.sourceId = sourceId;
    }
  }

  private final SupportContrastiveModel model;
  private final Map<String, ProductSupportModel.Article> articles = new HashMap<>();
  private static final Pattern MARKER =
      Pattern.compile(
          "(?iu)(?:^|[\\s.!?؟؛])(?:my question is|my question|actually i mean|سؤالي"
              + " هو|سؤالي|قصدي)\\s*[:：]\\s*");
  private static final Pattern CONTEXT =
      Pattern.compile(
          "(?iu)(?:\\b(if|unless|except|only if|provided"
              + " that|instead|however)\\b|(?:^|\\s)(لو|بشرط|إلا|لكن|بس لو)(?:\\s|$))");
  private static final Pattern QUESTION =
      Pattern.compile(
          "(?iu)^(how|what|which|why|where|can|does|do|is|will|هل|إزاي|ازاي|فين|ليه|إيه|ايه|أنهي|كام)(?:\\s|$)");
  private static final Pattern ARABIC = Pattern.compile("[\\u0621-\\u064a]");

  public LongFaqReader(SupportContrastiveModel model, List<ProductSupportModel.Article> docs) {
    this.model = Objects.requireNonNull(model);
    for (ProductSupportModel.Article d : docs)
      if (articles.put(d.id, d) != null) throw new IllegalArgumentException("Duplicate article");
  }

  private static Reply stop(Status s) {
    return new Reply(s, "", "");
  }

  public Reply read(String message) {
    if (message == null || message.length() > 8192) return stop(Status.CLARIFY);
    String q = message.trim();
    if (q.isEmpty()) return stop(Status.CLARIFY);
    java.util.regex.Matcher marker = MARKER.matcher(q);
    int start = 0;
    if (marker.find()) {
      start = marker.end();
      if (marker.find()) return stop(Status.CLARIFY);
    } else if (q.length() > 512 && (q.endsWith("?") || q.endsWith("؟"))) {
      // Prefer a sentence boundary; accept a comma only when it opens an explicit question.
      for (int i = q.length() - 2; i >= 0; i--) {
        char c = q.charAt(i);
        if (".!،,؛;\n".indexOf(c) >= 0
            && q.length() - i <= 513
            && QUESTION.matcher(q.substring(i + 1).trim()).find()) {
          start = i + 1;
          break;
        }
      }
    }
    if (start > 0) {
      String prefix = q.substring(0, start);
      if (prefix.indexOf('?') >= 0 || prefix.indexOf('؟') >= 0 || CONTEXT.matcher(prefix).find())
        return stop(Status.CLARIFY);
      q = q.substring(start).trim();
    }
    if (q.isEmpty() || q.length() > 512) return stop(Status.CLARIFY);
    for (int i = 0; i < q.length() - 1; i++)
      if (q.charAt(i) == '?' || q.charAt(i) == '؟') return stop(Status.CLARIFY);
    SupportContrastiveModel.Result r = model.search(q, 3);
    if (!r.accepted || r.matches.isEmpty()) return stop(Status.NO_MATCH);
    String topic = r.matches.get(0).topic.id;
    QuantityEvidence.Result gate = QuantityEvidence.check(q, articles.get(topic + ":en"));
    if (gate.decision == QuantityEvidence.Decision.ABSTAIN) return stop(Status.NO_MATCH);
    if (gate.decision == QuantityEvidence.Decision.SUPPORTED)
      return new Reply(Status.ANSWER, gate.sentence, gate.sourceId);
    ProductSupportModel.Article a =
        articles.get(topic + (ARABIC.matcher(q).find() ? ":ar" : ":en"));
    if (a == null || !a.text.startsWith(a.question + "\n")) return stop(Status.NO_MATCH);
    String answer = a.text.substring(a.question.length() + 1).trim();
    return answer.isEmpty() ? stop(Status.NO_MATCH) : new Reply(Status.ANSWER, answer, a.id);
  }
}
