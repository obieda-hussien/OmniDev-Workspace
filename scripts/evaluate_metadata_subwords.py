"""Compare word and subword metadata heads offline using PR #278's authored fixtures."""
import argparse
import csv
import json
import re
import unicodedata
from pathlib import Path

import numpy as np
from sklearn.feature_extraction.text import CountVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import f1_score
from sklearn.model_selection import StratifiedKFold

ROOT = Path(__file__).resolve().parents[1] / "neural-core/fixtures"


def words(text):
    text = unicodedata.normalize("NFKC", text).lower().translate(str.maketrans("أإآٱىة", "اااايه"))
    text = "".join(c for c in text if unicodedata.category(c) != "Mn" and c != "ـ")
    return re.findall(r"\w+", text)


def subwords(text):
    for word in words(text):
        yield word
        padded = "^" + word + "$"
        for i in range(len(padded) - 2):
            yield "#" + padded[i:i + 3]


def evaluate(path, field, export_dir):
    with (ROOT / path).open(encoding="utf-8") as stream:
        rows = list(csv.DictReader(stream, delimiter="\t"))
    with (ROOT / "metadata-subword-probes.tsv").open(encoding="utf-8") as stream:
        for probe in csv.DictReader(stream, delimiter="\t"):
            if probe["task"] == Path(path).parent.name:
                rows.append({"split": "challenge", "label": probe["expected"], field: probe["text"]})
    labels = np.array([row["label"] for row in rows])
    texts = np.array([row[field] for row in rows])
    train = np.array([i for i, row in enumerate(rows) if row["split"] == "train"])
    held = np.array([i for i, row in enumerate(rows) if row["split"] != "train"])
    print(path)
    for analyzer in (words, subwords):
        def predict(fit, test, export=False):
            vector = CountVectorizer(analyzer=analyzer, binary=True)
            model = LogisticRegression(C=4, random_state=17, max_iter=500)
            model.fit(vector.fit_transform(texts[fit]), labels[fit])
            # Use the same integer logit resolution as the app's generator.
            if export and export_dir and analyzer == subwords:
                export_dir.mkdir(parents=True, exist_ok=True)
                artifact = {"format": "binary-word-trigram-v1", "scale": 1000,
                            "labels": model.classes_.tolist(),
                            "terms": vector.get_feature_names_out().tolist(),
                            "weights": np.rint(1000 * model.coef_).astype(int).tolist(),
                            "bias": np.rint(1000 * model.intercept_).astype(int).tolist()}
                target = export_dir / (Path(path).parent.name + ".json")
                target.write_text(json.dumps(artifact, ensure_ascii=False), encoding="utf-8")
            logits = vector.transform(texts[test]) @ np.rint(1000 * model.coef_).T
            logits += np.rint(1000 * model.intercept_)
            return model.classes_[logits.argmax(axis=1)], model.coef_.size + model.intercept_.size

        oof = np.empty(len(train), dtype=labels.dtype)
        for a, b in StratifiedKFold(5, shuffle=True, random_state=17).split(train, labels[train]):
            oof[b], _ = predict(train[a], train[b])
        guesses, count = predict(train, held, export=True)
        print(analyzer.__name__, "coefficients", count, "train-fold macro F1",
              round(f1_score(labels[train], oof, average="macro"), 3))
        for split in sorted({rows[i]["split"] for i in held}):
            mask = [rows[i]["split"] == split for i in held]
            print(split, np.sum(guesses[mask] == labels[held][mask]), "/", sum(mask))
        original = [rows[i]["split"] != "challenge" for i in held]
        print("original holdout macro F1",
              round(f1_score(labels[held][original], guesses[original], average="macro"), 3))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--export-dir", type=Path, help="Write experimental candidate weights")
    args = parser.parse_args()
    evaluate("opening-titles/pairs.tsv", "reply", args.export_dir)
    evaluate("history-topics/requests.tsv", "text", args.export_dir)
