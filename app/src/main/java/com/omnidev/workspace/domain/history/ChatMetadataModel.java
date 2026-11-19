package com.omnidev.workspace.domain.history;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded local extractive baseline. It neither trains on chats nor authorizes tools. */
public final class ChatMetadataModel {
  public static final int VERSION = 8,
      MAX_TEXTS = 12,
      MAX_TEXT_CHARS = 3072,
      MAX_REPLY_CHARS = 24000,
      MAX_TITLE_CODEPOINTS = 64;

  public enum Topic {
    UNCATEGORIZED("uncategorized", "Uncategorized", "غير مصنف"),
    DEVELOPMENT("development", "Development", "برمجة"),
    DEVICE("device", "Device", "الجهاز"),
    RESEARCH("research", "Research", "بحث ومعرفة"),
    OMNI_HELP("omni_help", "Omni help", "مساعدة Omni"),
    MESSAGING("messaging", "Messaging", "مراسلة"),
    CONNECTED_APPS("connected_apps", "Connected apps", "تطبيقات متصلة"),
    WRITING("writing", "Writing", "كتابة"),
    PERSONAL("personal", "Personal", "شخصي"),
    GENERAL("general", "General", "عام");
    public final String id, label, arabic;

    Topic(String id, String label, String arabic) {
      this.id = id;
      this.label = label;
      this.arabic = arabic;
    }

    public static Topic fromId(String id) {
      for (Topic t : values()) if (t.id.equals(id)) return t;
      return UNCATEGORIZED;
    }
  }

  public enum Service {
    WHATSAPP("whatsapp", "WhatsApp", "واتساب واتس whatsapp"),
    TELEGRAM("telegram", "Telegram", "تليجرام تيليجرام تلجرام telegram"),
    DISCORD("discord", "Discord", "ديسكورد discord"),
    CONNECTED("connected_apps", "Connected apps", "التطبيقات المتصلة تطبيقات متصله connected apps"),
    GITHUB("github", "GitHub", "جيتهوب جتهب github"),
    MCP("mcp", "MCP", "mcp"),
    OMNILINK("omnilink", "OmniLink", "omnilink"),
    SLACK("slack", "Slack", "سلاك slack"),
    NOTION("notion", "Notion", "نوشن notion"),
    GMAIL("gmail", "Gmail", "جيميل gmail"),
    ANDROIDIDE("androidide", "AndroidIDE", "androidide");
    public final String id, label, aliases;

    Service(String id, String label, String aliases) {
      this.id = id;
      this.label = label;
      this.aliases = aliases;
    }
  }

  public static final class Result {
    public final String title, topic, serviceTags;

    Result(String title, Topic topic, Set<Service> services) {
      this.title = title;
      this.topic = topic.id;
      StringBuilder s = new StringBuilder();
      for (Service v : Service.values()) if (services.contains(v)) s.append('|').append(v.id);
      this.serviceTags = s.length() == 0 ? "" : s.append('|').toString();
    }
  }

  private static final Pattern FENCES = Pattern.compile("(?s)```.*?(?:```|$)");
  private static final Pattern URL = Pattern.compile("(?i)https?://\\S+");
  private static final Pattern SECRET =
      Pattern.compile(
          "(?i)(?:bearer\\s+\\S+|\\b(?:api[_"
              + " -]?key|password|token|secret|authorization|otp|pin)\\b\\s*(?:is|=|:)\\s*\\S+|\\bsk-[A-Za-z0-9_-]{8,}|\\bAIza[A-Za-z0-9_-]{15,}|(?:كلمه"
              + " السر|كلمة السر|الرمز السري)\\s*[:=]\\s*\\S+)");
  private static final Pattern IDENTIFIER =
      Pattern.compile(
          "[A-Za-z0-9_./+=-]{28,}|[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}|\\+?[0-9٠-٩][0-9٠-٩"
              + " ()-]{8,}[0-9٠-٩]");
  private static final Pattern GREETING =
      Pattern.compile(
          "(?i)^(?:hi|hello|hey|thanks|thank you|good morning|good"
              + " evening|مرحبا|اهلا|أهلا|ازيك|شكرا|السلام عليكم|صباح الخير|مساء"
              + " الخير)(?:\\s+(?:omni|friend|there|اومني|يا صاحبي))?[!؟?،,.\\s]*$");

  public static Result analyze(List<String> texts, String source, String packageName) {
    StringBuilder body = new StringBuilder();
    String chosen = "";
    boolean arabic = false;
    List<String> turns = new ArrayList<>(MAX_TEXTS);
    int count = 0;
    if (texts != null)
      for (String raw : texts) {
        if (count++ == MAX_TEXTS) break;
        if (raw == null) continue;
        String text = clipChars(raw, MAX_TEXT_CHARS);
        arabic |= hasArabic(text);
        String safe = clean(text);
        String evidence = URL.matcher(SECRET.matcher(FENCES.matcher(text).replaceAll(" ")).replaceAll(" "))
            .replaceAll(" ");
        String normalizedTurn = normalize(evidence);
        turns.add(normalizedTurn);
        if (!normalizedTurn.isEmpty()) {
          if (body.length() > 0) body.append(' ');
          body.append(normalizedTurn);
        }
        if (chosen.isEmpty() && !safe.isEmpty() && !GREETING.matcher(safe).matches())
          chosen = title(safe);
      }
    String normalized = body.toString();
    Set<String> words = new HashSet<>(Arrays.asList(normalized.split("\\s+")));
    Set<Service> services = EnumSet.noneOf(Service.class);
    for (Service service : Service.values()) {
      boolean matched = false;
      if (service == Service.CONNECTED)
        matched =
            normalized.contains("connected apps")
                || normalized.contains("التطبيقات المتصله")
                || normalized.contains("تطبيقات متصله");
      else
        for (String alias : normalize(service.aliases).split("\\s+"))
          if (!alias.isEmpty() && words.contains(alias)) {
            matched = true;
            break;
          }
      if (matched) services.add(service);
    }
    if ("whatsapp_bridge".equals(source)
        || "com.whatsapp".equals(packageName)
        || "com.whatsapp.w4b".equals(packageName)) services.add(Service.WHATSAPP);
    if ("telegram".equals(source) || "org.telegram.messenger".equals(packageName))
      services.add(Service.TELEGRAM);
    if ("discord".equals(source) || "com.discord".equals(packageName))
      services.add(Service.DISCORD);
    if ("external_app".equals(source)) services.add(Service.CONNECTED);
    Topic topic = Topic.GENERAL;
    int best = 0;
    Topic[] order = {
      Topic.OMNI_HELP,
      Topic.DEVELOPMENT,
      Topic.DEVICE,
      Topic.RESEARCH,
      Topic.WRITING,
      Topic.PERSONAL
    };
    String[] terms = {
      "omnihelp",
      "gradle kotlin java git github code compile build test tests apk debug برمجه كود جرادل كوتلن"
          + " باج مشروع اختبارات",
      "android brightness battery volume bluetooth wifi جهاز اندرويد سطوع بطاريه شيزوكو shizuku",
      "research documentation paper sources citation search بحث مصادر توثيق ابحاث ورقه",
      "write draft translate essay writing اكتب صياغه ترجم مقاله",
      "study studying exam fitness gym sleep مذاكره امتحان جيم نوم شخصي"
    };
    for (int i = 0; i < order.length; i++) {
      int score = 0;
      for (String term : terms[i].split(" ")) if (words.contains(term)) score += 2;
      if (order[i] == Topic.OMNI_HELP
          && (words.contains("omni") || words.contains("اومني"))
          && hasAny(
              words,
              "enable activate setup configure settings feature help connect pair تفعيل افعل ازاي"
                  + " اعداد اعدادات كيف استخدم مشكله مساعده")) score = 8;
      if (score > best) {
        best = score;
        topic = order[i];
      }
    }
    if (best == 0) {
      if (services.contains(Service.WHATSAPP)
          || services.contains(Service.TELEGRAM)
          || services.contains(Service.DISCORD)
          || services.contains(Service.SLACK)
          || services.contains(Service.GMAIL)) topic = Topic.MESSAGING;
      else if (!services.isEmpty()) topic = Topic.CONNECTED_APPS;
    }
    if (chosen.isEmpty()) chosen = arabic ? "محادثة جديدة" : "New conversation";
    Topic inferred = learnedTopic(turns);
    if (inferred != Topic.GENERAL) topic = inferred;
    return new Result(chosen, topic, services);
  }

  private static final Pattern TOPIC_CHANGE = Pattern.compile(
      "^(?:new topic|switching topics|change of topic|موضوع (?:تاني|جديد)|خلينا نغير الموضوع)(?: |$)");

  private static Topic learnedTopic(List<String> turns) {
    int[] votes = new int[HistoryTopicWeights.LABELS.length];
    for (int i = 0; i < turns.size(); i++) {
      String turn = turns.get(i);
      if (turn.isEmpty() || GREETING.matcher(turn).matches() || GENERIC_OPENING.matcher(turn).matches()) continue;
      int[] scores = HistoryTopicWeights.scores(new HashSet<>(Arrays.asList(turn.split(" +"))));
      int winner = strongest(scores, 700);
      if (winner < 0 || "general".equals(HistoryTopicWeights.LABELS[winner])) continue;
      if (TOPIC_CHANGE.matcher(turn).find()) Arrays.fill(votes, 0);
      int recentWeight = i >= turns.size() - 2 ? 2 : 1;
      votes[winner] += recentWeight * Math.min(1800, margin(scores, winner));
    }
    int winner = strongest(votes, 700);
    return winner < 0 ? Topic.GENERAL : Topic.fromId(HistoryTopicWeights.LABELS[winner]);
  }

  private static int margin(int[] scores, int winner) {
    int second = Integer.MIN_VALUE;
    for (int i = 0; i < scores.length; i++) {
      if (i != winner) second = Math.max(second, scores[i]);
    }
    return scores[winner] - second;
  }

  private static int strongest(int[] scores, int minimumMargin) {
    int winner = 0;
    for (int i = 1; i < scores.length; i++) if (scores[i] > scores[winner]) winner = i;
    return margin(scores, winner) >= minimumMargin ? winner : -1;
  }

  // Generic openings can use reply evidence alone.
  private static final Pattern GENERIC_OPENING = Pattern.compile(
      "(?:حل المشكله دي|شوف(?:لي)? (?:ده|دي|المشكله دي)|ممكن تبص علي دي|بص هنا|ايه (?:الحل|اللي حاصل)|"
      + "وضحلي السبب|ده حصل ليه|مش شغال|تساعدني|ساعدني(?: في الموضوع)?|رتبلي ده|محتاج اظبط الدنيا|"
      + "can you (?:help|check|explain this)|any (?:idea|ideas|suggestions|thoughts|fix)|"
      + "what (?:happened here|went wrong|about this)|what s (?:the issue|missing)|"
      + "please help|help me(?: organize this| plan)?|need a hand|look at this|inspect this|why is this happening|"
      + "ممكن (?:توضح|تشرح ده|تساعدني في دي)|معلش بص علي ده|بص(?:لي)? علي (?:ده|دي)|شوفلي الحوار ده|"
      + "محتاج مساعده|مش فاهم الحته دي|انا مش فاهم|ايه سبب ده|المشكله دي حلها ايه|ينفع تشوف دي|"
      + "can you take a look|could you (?:check|look at) this|what should i do here|"
      + "is there a fix|i am stuck|what am i missing|what s causing this|do you see the problem|what can i do)");

  private static final Pattern CONCLUSION = Pattern.compile(
      "(?imu)^\\h*(?:conclusion|root cause|diagnosis|الخلاصة|الخلاصه|التشخيص|السبب الرئيسي)\\h*[:：]\\h*");

  public static String openingTitle(String user, String reply, String fallback) {
    if (user == null || reply == null || reply.trim().isEmpty()) return fallback;
    String request = clean(clipChars(user, MAX_TEXT_CHARS));
    boolean generic = GENERIC_OPENING.matcher(normalize(request)).matches();
    String evidence = FENCES.matcher(clipChars(reply, MAX_REPLY_CHARS)).replaceAll(" ");
    java.util.regex.Matcher conclusion = CONCLUSION.matcher(evidence);
    int conclusionStart = -1;
    while (conclusion.find()) conclusionStart = conclusion.end();
    if (conclusionStart >= 0) evidence = evidence.substring(conclusionStart);
    String answer = clean(evidence);
    Set<String> terms = new HashSet<>(Arrays.asList(normalize(answer).split("\\s+")));
    int[] scores = OpeningTitleWeights.scores(terms);
    int best = strongest(scores, 550);
    if (!generic) {
      Set<String> requestTerms = new HashSet<>(Arrays.asList(normalize(request).split(" +")));
      int[] requestScores = OpeningTitleWeights.scores(requestTerms);
      int requestLabel = strongest(requestScores, 1);
      if (best < 0 || requestLabel != best || margin(requestScores, best) + margin(scores, best) < 1100
          || "other".equals(OpeningTitleWeights.LABELS[best]))
        return sharedPhrase(request, terms, fallback);
    }
    if (best < 0) return fallback;
    boolean ar = hasArabic(request);
    switch (OpeningTitleWeights.LABELS[best]) {
      case "chat_titles": return ar ? "اختيار عناوين المحادثات" : "Chat title generation";
      case "tool_discovery": return ar ? "اكتشاف أدوات البحث واختيارها" : "Workspace tool discovery";
      case "gradle": return ar ? "بناء التطبيق باستخدام Gradle" : "Android build with Gradle";
      case "git": return ar ? "فروع Git ودمج التغييرات" : "Git branches and merges";
      case "bluetooth": return ar ? "اتصال سماعة البلوتوث" : "Bluetooth headset connection";
      case "notifications": return ar ? "إشعارات واتساب" : "WhatsApp notifications";
      case "study": return ar ? "تنظيم جدول المذاكرة" : "Study and revision schedule";
      case "room": return ar ? "ترحيل قاعدة بيانات Room" : "Room database migration";
      case "compose": return ar ? "إدارة حالة واجهة Compose" : "Jetpack Compose UI state";
      case "apk": return ar ? "توقيع وتثبيت APK" : "APK signing and installation";
      case "battery": return ar ? "استهلاك بطارية الهاتف" : "Android battery drain";
      case "network": return ar ? "مشاكل اتصال الواي فاي" : "Wi-Fi connection issues";
      default: return fallback;
    }
  }

  private static final Set<String> TITLE_FILLER = new HashSet<>(Arrays.asList(
      ("the a an this that it my your me how can could you i need want please help with to for of in on is and "
      + "انا انت هو هي ده دي ان من في علي عايز عايزك ممكن ازاي لو سمحت عشان عندي اللي مع و عن").split(" ")));

  // For an open-domain request, keep a short topic phrase supported by both turns.
  private static String sharedPhrase(String request, Set<String> answer, String fallback) {
    String[] words = request.split("[\\s،,؟?!:;]+");
    if (words.length < 6) return fallback;
    int bestStart = 0, bestLength = 0, length = 0;
    for (int i = 0; i < words.length; i++) {
      String token = normalize(words[i]);
      if (token.length() > 1 && !TITLE_FILLER.contains(token) && answer.contains(token)) {
        length++;
        if (length > bestLength) {
          bestStart = i - length + 1;
          bestLength = length;
        }
      } else length = 0;
    }
    if (bestLength < 2 || bestLength == words.length) return fallback;
    StringBuilder phrase = new StringBuilder();
    for (int i = bestStart; i < bestStart + Math.min(bestLength, 6); i++) {
      if (phrase.length() > 0) phrase.append(' ');
      phrase.append(words[i]);
    }
    return title(phrase.toString());
  }

  public static boolean hasService(String tags, String id) {
    return tags != null && tags.contains("|" + id + "|");
  }

  public static boolean matches(
      String query, String title, String sourceLabel, String topic, String tags) {
    StringBuilder search =
        new StringBuilder(title == null ? "" : title).append(' ').append(sourceLabel);
    Topic t = Topic.fromId(topic);
    search.append(' ').append(t.label).append(' ').append(t.arabic);
    for (Service s : Service.values())
      if (hasService(tags, s.id)) search.append(' ').append(s.label).append(' ').append(s.aliases);
    String haystack = normalize(search.toString());
    for (String token : normalize(query == null ? "" : query).split("\\s+"))
      if (!token.isEmpty() && !haystack.contains(token)) return false;
    return true;
  }

  public static String normalize(String value) {
    String n =
        Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT);
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < n.length(); i++) {
      char c = n.charAt(i);
      if (Character.getType(c) == Character.NON_SPACING_MARK || c == '\u0640') continue;
      if ("أإآٱ".indexOf(c) >= 0) c = 'ا';
      if (c == 'ى') c = 'ي';
      if (c == 'ة') c = 'ه';
      out.append(Character.isLetterOrDigit(c) || c == '_' ? c : ' ');
    }
    return out.toString().trim().replaceAll("\\s+", " ");
  }

  private static String clean(String input) {
    String n = FENCES.matcher(input).replaceAll(" ").replaceFirst("(?i)[•·]\\s*Status:.*$", "");
    if (SECRET.matcher(n).find())
      return hasArabic(n) ? "إعداد بيانات الدخول إلى الخدمة" : "Service access setup";
    n = URL.matcher(n).replaceAll(" ");
    n = IDENTIFIER.matcher(n).replaceAll("[private]");
    return n.replaceAll("[\\p{Cntrl}\\p{Cf}]+", " ").replaceAll("\\s+", " ").trim();
  }

  private static String title(String text) {
    String n =
        text.replaceFirst(
                "(?iu)^(?:(?:please|can you|could you|i need help with|help me|من فضلك|لو"
                    + " سمحت|ممكن|عايزك|عايز|عاوزه|عاوز)\\s+)+",
                "")
            .replaceAll("^[\\s#>*`]+|[\\s`]+$", "");
    int end = n.indexOf('\n');
    if (end >= 0) n = n.substring(0, end);
    if (n.trim().isEmpty()) n = text;
    int length = n.codePointCount(0, n.length());
    return length <= MAX_TITLE_CODEPOINTS
        ? n
        : n.substring(0, n.offsetByCodePoints(0, MAX_TITLE_CODEPOINTS - 1)).trim() + "…";
  }

  private static boolean hasAny(Set<String> words, String terms) {
    for (String term : terms.split(" ")) if (words.contains(term)) return true;
    return false;
  }

  private static boolean hasArabic(String s) {
    for (int i = 0; i < s.length(); i++)
      if (s.charAt(i) >= '\u0600' && s.charAt(i) <= '\u06ff') return true;
    return false;
  }

  private static String clipChars(String s, int length) {
    if (s.length() <= length) return s;
    int end = length;
    if (Character.isHighSurrogate(s.charAt(end - 1))) end--;
    return s.substring(0, end);
  }

  private ChatMetadataModel() {}
}
