"""Train and evaluate a shared low-rank INT8 text encoder. Python 3 + NumPy."""
import argparse
import collections
import csv
import hashlib
import re
import time
import unicodedata
import zlib
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
FIXTURES = HERE.parents[1] / "fixtures"
SLOTS = 4096
RANK = 16
TASKS = ("title", "history", "intent")


def terms(text):
    text = unicodedata.normalize("NFKC", text[:4096]).lower().translate(str.maketrans("أإآٱىة", "اااايه"))
    tokens = re.findall(r"\w+", "".join(c for c in text if unicodedata.category(c) != "Mn" and c != "ـ"))
    previous = ""
    for token in tokens:
        yield "w:" + token, 2.0
        if previous:
            yield "p:" + previous + " " + token, 0.7
        previous = token
        padded = "^" + token + "$"
        for i in range(len(padded) - 2):
            yield "c:" + padded[i:i + 3], 1.0


def features(text, reply=""):
    values = collections.defaultdict(float)
    for field, weight in ((text, 1.0), (reply, 0.85)):
        for term, amount in sorted(set(terms(field))):
            h = zlib.crc32(term.encode())
            values[h % SLOTS] += weight * amount * (1 if h < 2**31 else -1)
    # Stable iteration order across fresh processes and hash seeds.
    ids = np.array(sorted(values), dtype=np.int32)
    v = np.array([values[i] for i in ids], dtype=float)
    return ids, v / max(1., np.linalg.norm(v))


def rows(file):
    with (FIXTURES / file).open(encoding="utf8") as f:
        return list(csv.DictReader(f, delimiter="\t"))


def corpus():
    data = []
    for r in rows("opening-titles/pairs.tsv"):
        data.append(("title", r["split"], r["user"], r["reply"], r["label"]))
    for task, path in [("history", "history-topics/requests.tsv"), ("intent", "text-intents.tsv")]:
        for r in rows(path):
            data.append((task, r["split"], r["text"], "", r["label"]))
    for r in rows("metadata-subword-probes.tsv"):
        title = r["task"] == "opening-titles"
        data.append(("title" if title else "history", "challenge", "" if title else r["text"],
                     r["text"] if title else "", r["expected"]))
    return data


def quantize(matrix):
    scale = np.maximum(np.max(np.abs(matrix), axis=0), 1e-12) / 127
    return np.rint(matrix / scale).astype(np.int8), scale.astype(np.float32)


def train(data, path):
    examples = [r for r in data if r[1] == "train"]
    labels = sorted({task + "/" + label for task, _, _, _, label in examples})
    x = np.zeros((len(examples), SLOTS))
    for i, (task, _, user, reply, label) in enumerate(examples):
        idx, v = features(user, reply)
        x[i, idx] = v
    # Fit each supervised task on its own labels, then factor their weights jointly.
    # Other tasks supply no negative labels for this task.
    from sklearn.linear_model import LogisticRegression
    coefficients = np.zeros((SLOTS, len(labels)))
    bias = np.zeros(len(labels))
    for task in TASKS:
        indices = [i for i, row in enumerate(examples) if row[0] == task]
        expected = [examples[i][-1] for i in indices]
        classifier = LogisticRegression(C=16., max_iter=500, random_state=17).fit(x[indices], expected)
        for i, label in enumerate(classifier.classes_):
            j = labels.index(task + "/" + label)
            coefficients[:, j] = classifier.coef_[i]
            bias[j] = classifier.intercept_[i]
    u, s, vt = np.linalg.svd(coefficients, full_matrices=False)
    encoder, enc_scale = quantize(u[:, :RANK] * np.sqrt(s[:RANK]))
    head, head_scale = quantize(np.sqrt(s[:RANK, None]) * vt[:RANK])
    np.savez_compressed(path, encoder=encoder, enc_scale=enc_scale, head=head,
                        head_scale=head_scale, bias=bias.astype(np.float32), labels=np.array(labels))


class Model:
    def __init__(self, path):
        with np.load(path, allow_pickle=False) as z:
            self.table = z["encoder"]
            self.scale = z["enc_scale"]
            self.head = z["head"] * z["head_scale"]
            self.bias = z["bias"]
            self.labels = z["labels"].tolist()

    def encode(self, user, reply=""):
        ids, values = features(user, reply)
        return (values @ self.table[ids]) * self.scale

    def rank(self, task, user, reply=""):
        scores = self.encode(user, reply) @ self.head + self.bias
        return sorted(((name.split("/")[1], float(s)) for name, s in zip(self.labels, scores)
                       if name.startswith(task + "/")), key=lambda item: (-item[1], item[0]))


def retrieval(model, split):
    # Each English capability card is indexed once; all 15 queries are evaluation only.
    with (HERE / "retrieval.tsv").open(encoding="utf8") as f:
        records = list(csv.DictReader(f, delimiter="\t"))
    cards = [r for r in records if r["kind"] == "card"]
    queries = [r for r in records if r["kind"] == split]

    def lexical(text):
        ix, v = features(text)
        result = np.zeros(SLOTS)
        result[ix] = v
        return result

    for name, embed in [("lexical", lexical), ("shared INT8", model.encode)]:
        vectors = np.array([embed(r["text"]) for r in cards])
        vectors /= np.maximum(1e-12, np.linalg.norm(vectors, axis=1, keepdims=True))
        correct = top3 = 0
        rank_sum = 0.
        for q in queries:
            scores = vectors @ embed(q["text"])
            order = sorted(range(len(cards)), key=lambda i: (-scores[i], cards[i]["label"]))
            rank = next(k for k, i in enumerate(order, 1) if cards[i]["label"] == q["label"])
            correct += rank == 1
            top3 += rank <= 3
            rank_sum += 1 / rank
        print(split, name, "retrieval top1/top3", correct, top3, "/", len(queries),
              "MRR", round(rank_sum / len(queries), 3))


def evaluate(model, data):
    outcomes = collections.defaultdict(list)
    for task, split, user, reply, truth in data:
        if split == "train":
            continue
        ranked = model.rank(task, user, reply)
        margin = ranked[0][1] - ranked[1][1]
        outcomes[task, split].append((ranked[0][0] == truth, margin))
    for (task, split), group in sorted(outcomes.items()):
        print(task, split, sum(ok for ok, _ in group), "/", len(group))
    cal = outcomes["intent", "calibration"]
    # Choose maximum coverage among thresholds with zero observed calibration errors.
    thresholds = sorted({0., *(m for _, m in cal), float("inf")})
    threshold = next(t for t in thresholds if all(ok for ok, m in cal if m >= t))
    held = outcomes["intent", "holdout"]
    accepted = [ok for ok, m in held if m >= threshold]
    print("intent holdout after abstention", sum(accepted), "correct of", len(accepted),
          "accepted from", len(held), "threshold", threshold)
    texts = [(r[2], r[3]) for r in data if r[1] != "train"]
    ms = []
    for user, reply in texts * 5:
        start = time.perf_counter_ns()
        model.rank("intent", user, reply)
        ms.append((time.perf_counter_ns() - start) / 1e6)
    print("host warm milliseconds p50/p95", np.percentile(ms, [50, 95]).round(3))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fit", action="store_true")
    parser.add_argument("--checkpoint", type=Path, default=HERE / "weights.npz")
    args = parser.parse_args()
    data = corpus()
    if args.fit:
        train(data, args.checkpoint)
    model = Model(args.checkpoint)
    evaluate(model, data)
    retrieval(model, "query")
    retrieval(model, "fresh")
    print(args.checkpoint.stat().st_size, "bytes", hashlib.sha256(args.checkpoint.read_bytes()).hexdigest())
