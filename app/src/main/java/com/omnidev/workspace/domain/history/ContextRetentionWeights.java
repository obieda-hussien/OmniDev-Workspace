package com.omnidev.workspace.domain.history;

// Fitted by scripts/train_opening_models.py.
public final class ContextRetentionWeights {
  private ContextRetentionWeights() {}
  private static final String[] TERMS = {
      "1", "120", "2", "30", "38", "4", "40", "42",
      "a", "accepted", "after", "all", "am", "and", "android", "api",
      "apk", "app", "assistant", "at", "back", "branch", "build", "but",
      "call", "can", "cause", "change", "changed", "check", "checked", "checking",
      "checkout", "checkpoint", "commit", "compilation", "completion", "config", "configuration", "confirmed",
      "copied", "correction", "count", "database", "dataset", "date", "decision", "denied",
      "deployment", "destination", "discovered", "do", "due", "error", "every", "existing",
      "expected", "failed", "feature", "fetch", "file", "files", "first", "for",
      "format", "found", "friday", "generated", "get", "git", "github_manager", "good",
      "gradle", "great", "have", "hello", "help", "history", "i", "in",
      "inspect", "inspection", "intact", "integration", "intend", "is", "json", "keep",
      "kotlin", "let", "list", "listing", "loading", "locate", "look", "main",
      "maybe", "me", "merge", "migration", "minimum", "missing", "modify", "moment",
      "morning", "need", "next", "no", "not", "observed", "on", "one",
      "opened", "original", "outdated", "output", "passed", "path", "pending", "permission",
      "permissions", "planning", "please", "prefs", "preserve", "project", "push", "querying",
      "question", "queued", "read", "read_file", "ready", "recent", "records", "recovery",
      "release", "released", "relevant", "repository", "requested", "response", "restored", "result",
      "rows", "run", "scheduled", "search", "second", "settings", "sha", "soon",
      "sounds", "source", "staging", "started", "status", "still", "supported", "sync",
      "take", "task", "test", "tests", "thanks", "that", "the", "think",
      "this", "tmp", "to", "toml", "tool", "unchanged", "update", "upgrade",
      "upload", "use", "user", "verified", "verify", "version", "wait", "waiting",
      "were", "whenever", "will", "with", "workspace", "yet", "you", "اتوجد",
      "اساعدك", "استخدم", "استني", "اعدادات", "اقرب", "اكتب", "الاتصال", "الاخبار",
      "الاختبارات", "الاصدار", "الاقصي", "الانترنت", "الانتظار", "التطبيق", "التعديلات", "التقرير",
      "التنفيذ", "الجدول", "الجديد", "الجديده", "الحد", "الخير", "الدمج", "الشاشه",
      "الصفحه", "الفحص", "الفرع", "الفل", "القاعده", "القديم", "القديمه", "المسار",
      "المشروع", "الملف", "الموضوع", "النسخه", "انا", "انقطع", "اهلا", "ايه",
      "بالمصري", "بدا", "تتراجع", "تحميل", "تسلم", "تظهر", "تمام", "جاري",
      "جاهزه", "جدا", "حاضر", "خلي", "دلوقتي", "زي", "شكرا", "شويه",
      "صاحبي", "صباح", "عامل", "علي", "عند", "فتح", "في", "قبل",
      "قررنا", "كده", "لا", "لازم", "لحد", "ما", "مازالت", "متعتمدش",
      "مجلد", "ملفات", "ممكن", "موجوده", "ميجا", "ناجل", "ناقص", "نتائج",
      "نجاح", "نجحت", "نورت", "هبص", "هتابع", "هي", "والنسخه", "وسيب",
      "وفشل", "وقت", "وقع", "ولم", "ومتستخدمش", "ومتعدلش", "ونسخه", "وهشوف",
      "وهقولك", "يا", "يرجي", "يونيو", "٤"
  };
  private static final int[][] WEIGHTS = {
      {317, 179, 317, 249, 406, 317, 302, 302,
      -428, 269, 239, 239, -89, 783, 473, 558,
      367, 367, -1518, 669, -128, 269, 177, -736,
      -1360, -44, -71, 265, 168, -44, -71, -218,
      436, 289, 369, -321, -345, 442, 95, 369,
      179, 302, 179, 239, 302, 265, 263, 217,
      317, 179, 249, 309, 265, 1896, -142, 263,
      302, 302, 269, -458, 121, 168, 302, -518,
      265, 528, 265, 367, -128, 443, 369, -527,
      302, -492, -71, -527, -89, -213, -475, 226,
      -214, 249, 239, -208, -142, 231, 441, 574,
      302, -72, -175, -213, -390, -214, -128, 406,
      -71, -72, 369, 410, 249, 342, 309, -72,
      -527, -303, -390, -290, -668, -345, 675, 302,
      239, 265, 302, -92, 406, 174, -622, 385,
      263, -208, -390, 224, 263, 217, 269, -333,
      -72, -1020, -390, 442, -89, -213, 480, 289,
      309, 317, -214, 174, -345, -228, 289, 1105,
      239, -213, -622, -333, 302, 226, 369, -142,
      -420, -157, 317, -779, -213, -228, 249, 179,
      -128, -561, 94, -147, -464, -464, 200, -72,
      -71, 224, -199, 226, -509, 309, -390, 239,
      168, 564, 366, 467, -142, 317, -390, -228,
      168, -89, -128, 239, 291, -458, -262, 224,
      -196, 315, -196, 315, -226, 411, 339, -196,
      686, 652, 369, 369, -281, 224, 465, 411,
      339, 171, 315, 268, 369, -220, 465, 224,
      -281, -175, 315, -407, 171, 268, 171, 224,
      411, 492, -134, 268, -134, 339, -527, -527,
      411, -175, 465, -281, -501, -175, -635, -281,
      349, -556, -196, 369, -196, -93, -963, -196,
      -134, -220, -527, 134, 224, 224, 266, 465,
      337, -407, -501, 465, 337, 315, 171, 268,
      268, 411, -196, 439, 369, 337, 171, -175,
      337, 349, -220, -134, -226, 315, 349, 315,
      339, -226, 224, -175, 369, 411, 171, -196,
      -226, -134, -281, 268, 369}
  };
  private static final int[] BIAS = {-669};
  public static int[] scores(java.util.Set<String> words) {
    int[] result = BIAS.clone();
    for (String word : words) {
      int index = java.util.Arrays.binarySearch(TERMS, word);
      if (index >= 0) {
        for (int k = 0; k < result.length; k++) result[k] += WEIGHTS[k][index];
      }
    }
    return result;
  }
  static final String[] LABELS = {"drop", "keep"};
}
