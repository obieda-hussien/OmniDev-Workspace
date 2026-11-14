package com.omnidev.workspace.domain.history;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded local extractive baseline. It neither trains on chats nor authorizes tools. */
public final class ChatMetadataModel {
  public static final int VERSION = 2,
      MAX_TEXTS = 6,
      MAX_TEXT_CHARS = 1500,
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
    int count = 0;
    if (texts != null)
      for (String raw : texts) {
        if (count++ == MAX_TEXTS) break;
        if (raw == null) continue;
        String text = clipChars(raw, MAX_TEXT_CHARS);
        arabic |= hasArabic(text);
        String safe = clean(text);
        body.append(' ')
            .append(
                URL.matcher(SECRET.matcher(FENCES.matcher(text).replaceAll(" ")).replaceAll(" "))
                    .replaceAll(" "));
        if (chosen.isEmpty() && !safe.isEmpty() && !GREETING.matcher(safe).matches())
          chosen = title(safe);
      }
    String normalized = normalize(body.toString());
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
    return new Result(chosen, topic, services);
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
