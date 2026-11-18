"""Train a catalog-wide INT8 tool retriever and return source-grounded reference cards."""
import argparse
import csv
import json
from pathlib import Path

import numpy as np

from model import HERE, Model, SLOTS, features, quantize
from tool_advisor import catalog as operation_cards, examples, vector


def load_cards():
    return json.loads((HERE / "catalog-cards.json").read_text(encoding="utf-8"))["cards"]


def training_rows(cards):
    for name, card in cards.items():
        yield name, name.replace("_", " "), 1.0
        yield name, card["short_description"], 1.0
        yield name, card["long_description"], 1.0
        arguments = "; ".join(p["name"] + ": " + p["description"] for p in card["parameters"])
        if arguments:
            yield name, card["short_description"] + " " + arguments, 0.7
    with (HERE / "catalog-ar.tsv").open(encoding="utf-8") as stream:
        for row in csv.DictReader(stream, delimiter="\t"):
            if row["tool"] not in cards:
                raise ValueError("Unknown Arabic training label " + row["tool"])
            yield row["tool"], row["text"], 1.0
    previous = examples(operation_cards(), "train")
    with (HERE / "tool-advisor-extra.tsv").open(encoding="utf-8") as stream:
        for row in csv.DictReader(stream, delimiter="\t"):
            previous.extend((text, row["key"]) for text in row["examples"].split("|"))
    for text, operation in previous:
        name = operation.partition("/")[0]
        if name in cards or name == "NO_TOOL":
            yield name, text, 0.65


def fit(encoder, cards, checkpoint):
    data = list(training_rows(cards))
    labels = sorted(set(name for name, _, _ in data))
    counts = {name: sum(weight for label, _, weight in data if label == name) for name in labels}
    x = np.stack([vector(text, encoder) for _, text, _ in data]).astype(np.float64)
    y = np.zeros((len(data), len(labels)))
    for i, (name, _, weight) in enumerate(data):
        factor = np.sqrt(weight / counts[name])
        y[i, labels.index(name)] = factor
        x[i] *= factor
    # Ridge regression in the smaller example space, followed by per-tool INT8 scaling.
    gram = x @ x.T
    gram.flat[::len(x) + 1] += 0.08
    weights, scale = quantize(x.T @ np.linalg.solve(gram, y))
    np.savez_compressed(checkpoint, labels=np.array(labels), weights=weights, scale=scale)
    print(f"Fitted {len(data)} examples for {len(cards)} tools plus NO_TOOL")


class CatalogAdvisor:
    def __init__(self, encoder, cards, checkpoint):
        self.encoder = encoder
        self.cards = cards
        with np.load(checkpoint, allow_pickle=False) as stored:
            self.labels = stored["labels"].tolist()
            self.weights = stored["weights"]
            self.scale = stored["scale"]
        if set(self.labels) != set(cards) | {"NO_TOOL"}:
            raise ValueError("Checkpoint labels disagree with the tool catalog")

    def ranked_names(self, query, available):
        if not query.strip() or not available:
            return []
        x = vector(query, self.encoder)
        present = np.flatnonzero(x)
        if len(present) == 0:
            return []
        scores = (x[present] @ self.weights[present]) * self.scale
        return [self.labels[i] for i in np.argsort(-scores, kind="stable")
                if self.labels[i] in available or self.labels[i] == "NO_TOOL"]

    def reference_cards(self, query, available, limit=3):
        # Suggestions only. Availability is supplied by the live caller.
        ordered = self.ranked_names(query, available)
        if not ordered or ordered[0] == "NO_TOOL":
            return []
        return [{"tool": name, **self.cards[name]}
                for name in [k for k in ordered if k in self.cards][:max(0, limit)]]


def evaluate(advisor, path):
    with path.open(encoding="utf-8") as stream:
        rows = list(csv.DictReader(stream, delimiter="\t"))
    all_tools = set(advisor.cards)
    for group in sorted({r["language"] for r in rows}):
        selected = [r for r in rows if r["language"] == group]
        top1 = top3 = 0
        for row in selected:
            ranked = advisor.ranked_names(row["text"], all_tools)
            top1 += bool(ranked) and row["expected"] == ranked[0]
            top3 += row["expected"] in ranked[:3]
        print(f"{group}: top1={top1}/{len(selected)}, top3={top3}/{len(selected)}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fit", action="store_true")
    parser.add_argument("--checkpoint", type=Path, default=HERE / "catalog-advisor.npz")
    parser.add_argument("--evaluate", type=Path)
    parser.add_argument("--query")
    parser.add_argument("--available", nargs="*", default=[])
    args = parser.parse_args()
    encoder = Model(HERE / "weights.npz")
    cards = load_cards()
    if args.fit:
        fit(encoder, cards, args.checkpoint)
    advisor = CatalogAdvisor(encoder, cards, args.checkpoint)
    if args.evaluate:
        evaluate(advisor, args.evaluate)
    if args.query is not None:
        print(json.dumps(advisor.reference_cards(args.query, set(args.available)), ensure_ascii=False, indent=2))
