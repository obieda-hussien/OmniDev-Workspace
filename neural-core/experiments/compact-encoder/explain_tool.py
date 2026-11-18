"""Identify a tool for an informational explanation."""
import argparse
import json
import re

import numpy as np

from model import HERE, Model
from tool_advisor import vector

CARDS = HERE / "tool-explanations.json"
CHECKPOINT = HERE / "tool-explanations.npz"


def fit(cards, encoder):
    from sklearn.linear_model import LogisticRegression

    samples = []
    for name, card in cards.items():
        samples.extend((text, name) for text in card["examples"])
        if name != "UNKNOWN":
            samples.extend((card[field], name) for field in ("summary_ar", "summary_en"))
    x = np.stack([vector(text, encoder) for text, _ in samples])
    classifier = LogisticRegression(C=8, max_iter=500, random_state=17).fit(x, [name for _, name in samples])
    scale = (np.max(np.abs(classifier.coef_), axis=1) / 127).astype(np.float32)
    weights = np.rint(classifier.coef_ / scale[:, None]).astype(np.int8)
    np.savez_compressed(CHECKPOINT, labels=classifier.classes_, weights=weights, scale=scale,
                        bias=classifier.intercept_.astype(np.float32))
    print(f"Fitted {len(samples)} informational examples")


class ToolExplainer:
    def __init__(self, cards, encoder):
        self.cards = cards
        self.encoder = encoder
        with np.load(CHECKPOINT, allow_pickle=False) as weights:
            self.labels = weights["labels"].tolist()
            self.weights = weights["weights"]
            self.scale = weights["scale"]
            self.bias = weights["bias"]
        if set(self.labels) != set(cards):
            raise ValueError("Checkpoint labels do not match the explanations")

    def explain(self, prompt):
        if not prompt.strip():
            return []
        names = [name for name in self.cards if name != "UNKNOWN"
                 and re.search(rf"(?<!\w){re.escape(name)}(?!\w)", prompt, re.IGNORECASE)]
        if not names:
            logits = self.weights @ vector(prompt, self.encoder) * self.scale + self.bias
            best, second = np.argsort(-logits, kind="stable")[:2]
            if self.labels[best] != "UNKNOWN" and logits[best] - logits[second] >= 0.45:
                names = [self.labels[best]]
        return [{"tool": name, "summary_ar": self.cards[name]["summary_ar"],
                 "summary_en": self.cards[name]["summary_en"]} for name in names]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fit", action="store_true")
    parser.add_argument("--query", help="Tool name or plain-language question about its purpose")
    args = parser.parse_args()
    cards = json.loads(CARDS.read_text(encoding="utf-8"))
    encoder = Model(HERE / "weights.npz")
    if args.fit:
        fit(cards, encoder)
    if args.query is not None:
        result = ToolExplainer(cards, encoder).explain(args.query)
        print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
