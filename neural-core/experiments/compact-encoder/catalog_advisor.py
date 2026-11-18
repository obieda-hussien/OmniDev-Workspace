"""Train a catalog-wide INT8 tool retriever and return source-grounded reference cards."""
import argparse
import csv
import json
from pathlib import Path
from itertools import islice

import numpy as np

from model import HERE, Model, quantize
from tool_advisor import catalog as operation_cards, examples, vector


def load_cards():
    source = json.loads((HERE / "catalog-cards.json").read_text(encoding="utf-8"))
    defensive = json.loads((HERE / "catalog-defensive.json").read_text(encoding="utf-8"))
    if not set(defensive) <= set(source["excluded_tools"]):
        raise ValueError("Defensive projections must be scoped to omitted source definitions")
    return {**source["cards"], **defensive}


def training_rows(cards):
    for name, card in cards.items():
        if "fixed_arguments" not in card:
            yield name, name.replace("_", " "), 1.0
        for example in card.get("examples", []):
            yield name, example, 1.0
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
    with (HERE / "catalog-multilingual.tsv").open(encoding="utf-8") as stream:
        for row in csv.DictReader(stream, delimiter="\t"):
            if row["tool"] not in cards and row["tool"] != "NO_TOOL":
                raise ValueError("Unknown multilingual training label " + row["tool"])
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

    def score(self, text):
        x = vector(text, self.encoder)
        present = np.flatnonzero(x)
        return (x[present] @ self.weights[present]) * self.scale

    def ranked_names(self, query, available, *, history=(), follow_up=False, gloss=None):
        if not query.strip() or not available:
            return []
        # Score up to eight 4K-character segments; preserve both ends beyond this budget.
        if len(query) > 32768:
            query = query[:4096] + query[-28672:]
        segments = [query[i:i + 4096] for i in range(0, len(query), 4096)]
        scores = np.average([self.score(chunk) for chunk in segments], axis=0,
                            weights=[1] * (len(segments) - 1) + [3])
        if len(segments) > 1:
            # Requests often paste an artifact and place the actual instruction on its own last line.
            _, separator, last_line = query.rstrip().rpartition("\n")
            if separator and 12 <= len(last_line.strip()) <= 512:
                scores = 0.25 * scores + 0.75 * self.score(last_line)
        if gloss and gloss.strip():
            scores = 0.35 * scores + 0.65 * self.score(gloss[:4096])
        if follow_up:
            previous = []
            for turn in islice(reversed(history), 24):
                if turn.get("role") == "user" and turn.get("content", "").strip():
                    previous.append(turn["content"][-4096:])
                    if len(previous) == 8:
                        break
            if previous:
                context = np.average([self.score(turn) for turn in previous], axis=0,
                                     weights=[0.25 ** i for i in range(len(previous))])
                scores = 0.25 * scores + 0.75 * context
        eligible = {
            name for name, card in self.cards.items()
            if (f"{name}/{card['fixed_arguments']['action']}" if "fixed_arguments" in card else name)
            in available
        }
        if not eligible or not np.any(scores):
            return []
        return [self.labels[i] for i in np.argsort(-scores, kind="stable")
                if self.labels[i] in eligible or self.labels[i] == "NO_TOOL"]

    def reference_cards(self, query, available, limit=3, *, history=(), follow_up=False, gloss=None):
        # Suggestions only. Availability is supplied by the live caller.
        ordered = self.ranked_names(query, available, history=history, follow_up=follow_up, gloss=gloss)
        if not ordered or ordered[0] == "NO_TOOL":
            return []
        return [{"tool": name, **{field: value for field, value in self.cards[name].items()
                                  if field != "examples"}}
                for name in [k for k in ordered if k in self.cards][:max(0, limit)]]


def evaluate(advisor, path):
    with path.open(encoding="utf-8") as stream:
        rows = list(csv.DictReader(stream, delimiter="\t"))
    all_tools = {f"{name}/{card['fixed_arguments']['action']}" if "fixed_arguments" in card else name
                 for name, card in advisor.cards.items()}
    for group in sorted({r["language"] for r in rows}):
        selected = [r for r in rows if r["language"] == group]
        top1 = top3 = 0
        for row in selected:
            ranked = advisor.ranked_names(row["text"], all_tools)
            top1 += bool(ranked) and row["expected"] == ranked[0]
            top3 += row["expected"] in ranked[:3]
        print(f"{group}: top1={top1}/{len(selected)}, top3={top3}/{len(selected)}")


def evaluate_context(advisor, path):
    cases = json.loads(path.read_text(encoding="utf-8"))
    context_hits = plain_hits = 0
    for case in cases:
        history = [{"role": "user", "content": case.get("previous", "")},
                   {"role": "assistant", "content": "Search the web for prices."}]
        query = case["query"]
        plain = advisor.ranked_names(query, advisor.cards)
        ranked = advisor.ranked_names(query, advisor.cards, history=history,
                                      follow_up=case.get("follow_up", False), gloss=case.get("gloss"))
        plain_hits += plain[:1] == [case["expected"]]
        context_hits += ranked[:1] == [case["expected"]]
    print(f"Context and gloss: top1={context_hits}/{len(cases)}; query only={plain_hits}/{len(cases)}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fit", action="store_true")
    parser.add_argument("--checkpoint", type=Path, default=HERE / "catalog-advisor.npz")
    parser.add_argument("--evaluate", type=Path)
    parser.add_argument("--evaluate-context", type=Path)
    parser.add_argument("--query")
    parser.add_argument("--available", nargs="*", default=[],
                        help="Active tool names and exact tool/action keys for defensive projections")
    parser.add_argument("--history", type=Path, help="JSON array of prior turns with role and content")
    parser.add_argument("--follow-up", action="store_true", help="Use prior user turns for an ambiguous current request")
    parser.add_argument("--gloss", help="Optional trusted English or Arabic rendering of the user request")
    args = parser.parse_args()
    encoder = Model(HERE / "weights.npz")
    cards = load_cards()
    if args.fit:
        fit(encoder, cards, args.checkpoint)
    advisor = CatalogAdvisor(encoder, cards, args.checkpoint)
    if args.evaluate:
        evaluate(advisor, args.evaluate)
    if args.evaluate_context:
        evaluate_context(advisor, args.evaluate_context)
    if args.query is not None:
        history = json.loads(args.history.read_text(encoding="utf-8")) if args.history else []
        if not isinstance(history, list) or any(
            not isinstance(turn, dict) or not isinstance(turn.get("content"), str)
            for turn in history
        ):
            parser.error("--history must contain an array of turns with string content")
        cards = advisor.reference_cards(args.query, set(args.available), history=history,
                                        follow_up=args.follow_up, gloss=args.gloss)
        print(json.dumps(cards, ensure_ascii=False, indent=2))
