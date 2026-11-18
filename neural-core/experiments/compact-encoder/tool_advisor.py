"""Experimental tool-use advisor. Run with --fit once or --query to inspect a suggestion."""
import argparse
import csv
import json

import numpy as np

from model import HERE, SLOTS, TextEncoder, features


def catalog():
    with (HERE / "tool-guidance.tsv").open(encoding="utf8") as stream:
        return {row["key"]: row for row in csv.DictReader(stream, delimiter="\t")}


def vector(text, encoder):
    ids, values = features(text)
    x = np.zeros(SLOTS + encoder.dimensions, dtype=np.float32)
    x[ids] = values
    embedding = encoder.encode(text)
    x[SLOTS:] = 0.5 * embedding / max(np.linalg.norm(embedding), 1e-9)
    return x


def examples(cards, split):
    return [(text, key) for key, row in cards.items() for text in row[split].split("|")]


def ranking(scores):
    order = np.argsort(-scores, kind="stable")
    return order[0], float(scores[order[0]] - scores[order[1]])


def fit(path, encoder, cards):
    from sklearn.linear_model import LogisticRegression
    samples = examples(cards, "train") + [(r["usage"], k) for k, r in cards.items()]
    with (HERE / "tool-advisor-extra.tsv").open(encoding="utf8") as stream:
        for row in csv.DictReader(stream, delimiter="\t"):
            if row["key"] not in cards:
                raise ValueError("Unknown training key " + row["key"])
            samples.extend((text, row["key"]) for text in row["examples"].split("|"))
    x = [vector(t, encoder) for t, _ in samples]
    classifier = LogisticRegression(C=16, max_iter=500, random_state=17)
    classifier.fit(x, [key for _, key in samples])
    scale = np.max(np.abs(classifier.coef_), axis=1).astype(np.float32) / 127
    w = np.rint(classifier.coef_ / scale[:, None]).astype(np.int8)
    bias = classifier.intercept_.astype(np.float32)
    labels = classifier.classes_
    misses = []
    for text, truth in examples(cards, "calibration"):
        logits = (w @ vector(text, encoder)) * scale + bias
        best, margin = ranking(logits)
        if labels[best] != truth:
            misses.append(margin)
    # Strict comparison excludes the largest incorrect calibration margin.
    np.savez_compressed(path, w=w, scale=scale, bias=bias, labels=labels,
                        threshold=max([0.] + misses))


class Advisor:
    def __init__(self, encoder, cards, path):
        self.encoder = encoder
        self.cards = cards
        with np.load(path, allow_pickle=False) as data:
            self.weights = data["w"]
            self.scale = data["scale"]
            self.bias = data["bias"]
            self.labels = data["labels"].tolist()
            self.threshold = float(data["threshold"])

    def score(self, text):
        return (self.weights @ vector(text, self.encoder)) * self.scale + self.bias

    def shortlist(self, text, available):
        if not text.strip():
            return []
        scores = self.score(text)
        keys = [self.labels[i] for i in np.argsort(-scores, kind="stable")]
        return [{"key": key, "usage": self.cards[key]["usage"],
                 "required_arguments": list(filter(None, self.cards[key]["required"].split(",")))}
                for key in keys if key in available and key != "NO_TOOL"][:3]

    def advise(self, text, available, supplied=None):
        supplied = supplied or {}
        scores = self.score(text)
        best, margin = ranking(scores)
        key = self.labels[best]
        if not text.strip() or margin <= self.threshold:
            return {"decision": "defer", "reason": "uncertain"}
        if key == "NO_TOOL":
            return {"decision": "answer"}
        if key not in available:
            return {"decision": "defer", "reason": "unavailable"}
        name, _, action = key.partition("/")
        card = self.cards[key]
        required = list(filter(None, card["required"].split(",")))
        missing = [arg for arg in required if supplied.get(arg) is None or not str(supplied[arg]).strip()]
        return {"decision": "clarify" if missing else "suggest", "tool": name,
                "action": action or None, "required_arguments": required, "missing": missing,
                "usage": card["usage"]}


def evaluate(advisor):
    for split in ("calibration", "test"):
        top1 = top3 = accepted = wrong = 0
        for text, truth in examples(advisor.cards, split):
            logits = advisor.score(text)
            best, margin = ranking(logits)
            hits = [advisor.labels[i] for i in np.argsort(-logits, kind="stable")[:3]]
            top1 += advisor.labels[best] == truth
            top3 += truth in hits
            if margin > advisor.threshold:
                accepted += 1
                wrong += advisor.labels[best] != truth
        print(split, "total/top1/top3/accepted/wrong accepted", len(examples(advisor.cards, split)),
              top1, top3, accepted, wrong)

    for name in ("contrast", "fresh"):
        with (HERE / ("tool-advisor-" + name + ".tsv")).open(encoding="utf8") as stream:
            tests = list(csv.DictReader(stream, delimiter="\t"))
        correct = incorrect_accepted = right_defer = 0
        for row in tests:
            decision = advisor.advise(row["text"], advisor.cards)
            if decision["decision"] == "defer":
                predicted = "DEFER"
            elif decision["decision"] == "answer":
                predicted = "NO_TOOL"
            else:
                predicted = decision["tool"]
                if decision["action"]:
                    predicted += "/" + decision["action"]
            correct += predicted == row["expected"]
            right_defer += predicted == row["expected"] == "DEFER"
            incorrect_accepted += predicted != "DEFER" and predicted != row["expected"]
        print(name, "total/correct/correct unsupported deferrals/wrong accepted",
              len(tests), correct, right_defer, incorrect_accepted)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fit", action="store_true")
    parser.add_argument("--query")
    parser.add_argument("--available", nargs="*", default=[], help="Exact active tool or tool/action keys")
    parser.add_argument("--shortlist", action="store_true", help="Show up to three available tool cards")
    parser.add_argument("--supplied", type=json.loads, default={}, help="JSON object of known argument values")
    args = parser.parse_args()
    if not isinstance(args.supplied, dict):
        parser.error("--supplied must be a JSON object")
    cards = catalog()
    encoder = TextEncoder()
    checkpoint = HERE / "tool-advisor.npz"
    if args.fit:
        fit(checkpoint, encoder, cards)
    advisor = Advisor(encoder, cards, checkpoint)
    if args.query is None:
        evaluate(advisor)
    else:
        active = set(args.available)
        result = advisor.advise(args.query, active, args.supplied)
        if args.shortlist and result["decision"] != "answer":
            result["reference_cards"] = advisor.shortlist(args.query, active)
        print(json.dumps(result, ensure_ascii=False))
