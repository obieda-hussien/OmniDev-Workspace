"""Fit compact linear classifiers offline. Training consumes authored/public train splits only."""
import csv
import json
import re
import unicodedata
from pathlib import Path

import numpy as np
from sklearn.feature_extraction.text import CountVectorizer
from sklearn.linear_model import LogisticRegression

fixtures = Path("neural-core/fixtures")
history = Path("app/src/main/java/com/omnidev/workspace/domain/history")
neural = Path("neural-core/src/main/java/com/omnidev/workspace/domain/neural")


def read(path):
    with path.open(encoding="utf-8") as f:
        return list(csv.DictReader(f, delimiter="\t"))


def words(text):
    text = unicodedata.normalize("NFKC", text).lower().translate(str.maketrans("أإآٱىة", "اااايه"))
    text = "".join(c for c in text if unicodedata.category(c) != "Mn" and c != "ـ")
    return re.findall(r"\w+", text)


def literal(values):
    return ",\n      ".join(", ".join(values[i:i + 8]) for i in range(0, len(values), 8))


def fit(samples, output, name, package, auxiliary=(), contrast_words=()):
    vector = CountVectorizer(analyzer=words, binary=True)
    vector.fit([text for text, _ in samples])
    sample_weights = [1.] * len(samples) + [0.15] * len(auxiliary)
    samples = samples + list(auxiliary)
    x = vector.transform([text for text, _ in samples])
    model = LogisticRegression(C=4, random_state=17, max_iter=500).fit(x, [label for _, label in samples], sample_weight=sample_weights)
    terms = list(vector.get_feature_names_out())
    weights = np.rint(model.coef_ * 1000).astype(int)
    bias = np.rint(model.intercept_ * 1000).astype(int)
    source = """package %s;

// Fitted by scripts/train_opening_models.py.
public final class %s {
  private %s() {}
  private static final String[] TERMS = {
      %s
  };
  private static final int[][] WEIGHTS = {
      %s
  };
  private static final int[] BIAS = {%s};
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
  static final String[] LABELS = {%s};
}
""" % (package, name, name, literal([json.dumps(t, ensure_ascii=False) for t in terms]),
       ",\n      ".join("{"+literal(list(map(str, row)))+"}" for row in weights),
       ", ".join(map(str, bias)), ", ".join(json.dumps(v) for v in model.classes_))
    if contrast_words:
        indices = vector.vocabulary_
        anchors = sorted(w for w in contrast_words if w in indices and weights[0, indices[w]] < -250)
        source = source.replace("  static final String[] LABELS", """  private static final String[] ANCHORS = {
      %s
  };
  public static boolean hasNegativeAnchor(java.util.Set<String> words) {
    for (String word : words) if (java.util.Arrays.binarySearch(ANCHORS, word) >= 0) return true;
    return false;
  }
  static final String[] LABELS""" % literal([json.dumps(w, ensure_ascii=False) for w in anchors]))
    (output / (name + ".java")).write_text(source, encoding="utf-8")
    print(name, len(samples), "train rows,", weights.size + bias.size, "coefficients")


titles = read(fixtures / "opening-titles/pairs.tsv")
fit([(r["user"]+" "+r["reply"], r["label"]) for r in titles if r["split"] == "train"],
    history, "OpeningTitleWeights", "com.omnidev.workspace.domain.history")
routes = read(fixtures / "context-inspection/examples.tsv") + read(fixtures / "context-inspection/contrast-training.tsv")
data = [(r["text"], "technical" if r["label"] == "1" else "other") for r in routes if r["split"] == "train"]
data.extend((r["text"], "technical" if r["label"] in {"build","device","repository"} else "other")
            for r in read(fixtures / "text-intents.tsv") if r["split"] == "train")
auxiliary = [(r["text"], "technical" if r["expected_domain"] == "device" else "other")
             for r in read(fixtures / "massive-refusal/corpus.tsv") if r["split"] == "train"]
neg = {w for r in read(fixtures / "context-inspection/contrast-training.tsv") if r["label"] == "0" for w in words(r["text"])}
positive = {w for t, label in data + auxiliary if label == "technical" for w in words(t)}
fit(data, neural, "InspectionContrastWeights", "com.omnidev.workspace.domain.neural", auxiliary, neg - positive)
