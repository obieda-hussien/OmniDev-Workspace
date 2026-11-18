package com.omnidev.workspace.domain.neural;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Offline selective FAQ check. SUPPORTED carries the exact sentence licensing the value. */
public final class QuantityEvidence {
  public enum Decision {
    PASS,
    SUPPORTED,
    ABSTAIN
  }

  public static final class Result {
    public final Decision decision;
    public final String sourceId, sentence, value;

    private Result(Decision decision, String sourceId, String sentence, String value) {
      this.decision = decision;
      this.sourceId = sourceId;
      this.sentence = sentence;
      this.value = value;
    }
  }

  private static final Result PASS = new Result(Decision.PASS, "", "", "");
  private static final Result ABSTAIN = new Result(Decision.ABSTAIN, "", "", "");
  private static final Pattern ASK =
      Pattern.compile(
          "(?<![\\p{L}\\p{N}_])(how many|how much|what number|which port|what"
              + " port|exact|maximum|upper bound|number of|port number|listening"
              + " port|limit|كام|بكام|اقصي|الاعلي|رقم|عدد)(?![\\p{L}\\p{N}_])");
  private static final Pattern PORT =
      Pattern.compile(
          "(?<![\\p{L}\\p{N}_])(ports?|port number|البورت|بورت|المنفذ|منفذ)(?![\\p{L}\\p{N}_])");
  private static final Pattern INPUT =
      Pattern.compile(
          "(?<![\\p{L}\\p{N}_])(inputs?|messages?|رسائل|الرسائل|رسايل|رسايلي|رسالة|الرسالة)(?![\\p{L}\\p{N}_])");
  private static final Pattern OTHER_UNIT =
      Pattern.compile(
          "(?<![\\p{L}\\p{N}_])(characters?|letters?|bytes?|kilobytes?|megabytes?|mb|gb|ms|milliseconds?|seconds?|minutes?|hours?|days?|digits?|percent(age)?|حروف|الحروف|حرف|بايت|ميجا|ميجابايت|دقيقة|دقايق|ثانية|ثواني|ساعة|ساعات|يوم|ايام|رقم"
              + " الكود)(?![\\p{L}\\p{N}_])");
  private static final Pattern OTHER_SCOPE =
      Pattern.compile(
          "(?<![\\p{L}\\p{N}_])(second|secondary|parallel|remote|hosted|public|separate|per"
              + " account|لكل"
              + " حساب|التاني|تاني|التانية|موازي|المتوازية|العام|المنفصل|المستضاف|بره)(?![\\p{L}\\p{N}_])");
  // Whole affirmative sentences from the reviewed English FAQ. Any unrecognized rewrite abstains.
  private static final Pattern HISTORY_FACT =
      Pattern.compile(
          "The current history engine uses bounded local rules and at most"
              + " (one|two|three|four|five|six|seven|eight|nine|ten|[1-9][0-9]{0,3}) short user"
              + " inputs\\.");
  private static final Pattern BRIDGE_FACT =
      Pattern.compile("The bridge binds to 127\\.0\\.0\\.1:([1-9][0-9]{0,4})\\.");
  private static final Pattern SENTENCES = Pattern.compile("(?<=[.!?])\\s+");
  private static final Pattern MARKS = Pattern.compile("\\p{M}+");

  private QuantityEvidence() {}

  public static Result check(String query, ProductSupportModel.Article article) {
    if (query == null || query.length() > 512) return ABSTAIN;
    String q =
        MARKS
            .matcher(Normalizer.normalize(query, Normalizer.Form.NFKD))
            .replaceAll("")
            .replace('ى', 'ي')
            .replace("ـ", "")
            .toLowerCase(Locale.ROOT);
    if (!ASK.matcher(q).find()) return PASS;
    boolean port = PORT.matcher(q).find(), input = INPUT.matcher(q).find();
    if (OTHER_UNIT.matcher(q).find()) return ABSTAIN;
    if (!port && !input) return PASS;
    if (port == input || OTHER_SCOPE.matcher(q).find() || article == null) return ABSTAIN;
    if (!(port ? "whatsapp-bridge:en" : "local-model-size:en").equals(article.id)) return ABSTAIN;
    String prefix = article.question + "\n";
    if (!article.text.startsWith(prefix)) return ABSTAIN;
    Result found = ABSTAIN;
    for (String sentence : SENTENCES.split(article.text.substring(prefix.length()))) {
      Matcher m = (port ? BRIDGE_FACT : HISTORY_FACT).matcher(sentence);
      if (!m.matches()) continue;
      if (found != ABSTAIN || (port && Integer.parseInt(m.group(1)) > 65535)) return ABSTAIN;
      found = new Result(Decision.SUPPORTED, article.id, sentence, m.group(1));
    }
    return found;
  }
}
