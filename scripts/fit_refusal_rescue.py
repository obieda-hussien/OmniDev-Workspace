"""Calibrate a bounded second look at the first reference of a refused FAQ query."""
import json
import math
import sys
from pathlib import Path

from fit_topic_rerank import read_rows

ARMS = ('retrieval', 'pair', 'logistic')


def eligible(row):
    return bool(row.hits) and not row.proposed and not row.accepted


def correct(row):
    return bool(row.hits) and row.hits[0].topic in row.labels


def features(row):
    first = row.hits[0]
    second = row.hits[1].retrieval if len(row.hits) > 1 else 0
    return tuple(round(x, 8) for x in (1, first.retrieval, first.coverage,
                 first.retrieval - second, first.probability, first.coverage * first.probability))


def sigmoid(z):
    return 1 / (1 + math.exp(-z))


def fit(train):
    if len(train) != 507 or any(r.split != 'train' for r in train):
        raise ValueError('Unexpected training cohort')
    samples = [(features(r), int(correct(r))) for r in train if eligible(r)]
    return fit_samples(samples)


def fit_samples(samples):
    counts = [sum(y == label for _, y in samples) for label in (0, 1)]
    if min(counts) == 0:
        raise ValueError('Both classes required')
    weights = [0.] * 6
    for _ in range(400):
        gradient = [0.] + [.01 * w for w in weights[1:]]
        for x, y in samples:
            residual = (sigmoid(sum(a*b for a, b in zip(weights, x))) - y) / (2 * counts[y])
            for j, value in enumerate(x):
                gradient[j] += residual * value
        weights = [w - .3 * g for w, g in zip(weights, gradient)]
    return [round(w, 10) for w in weights]


def score(row, arm, weights):
    x = features(row)
    if arm == 'retrieval':
        return x[1]
    if arm == 'pair':
        return x[4]
    if arm == 'logistic':
        return round(sigmoid(sum(a*b for a, b in zip(weights, x))), 10)
    raise ValueError(arm)


def calibrate(dev, arm, weights):
    if len(dev) != 135 or any(r.split != 'development' for r in dev):
        raise ValueError('Unexpected development cohort')
    scores = [(score(r, arm, weights), correct(r)) for r in dev if eligible(r)]
    return calibrate_scores(scores)


def calibrate_scores(scores):
    # A valid threshold must exceed every incorrect score, including ties.
    highest_error = max((s for s, ok in scores if not ok), default=-math.inf)
    wins = [s for s, ok in scores if ok and s > highest_error]
    return (len(wins), min(wins)) if wins else (0, math.inf)


def evaluate(rows, arm, weights, threshold):
    gained, wrong, unsupported = [], [], []
    for r in rows:
        if eligible(r) and score(r, arm, weights) >= threshold:
            (gained if correct(r) else wrong).append(r.id)
            if not r.labels:
                unsupported.append(r.id)
    baseline_correct = sum(r.accepted and correct(r) for r in rows)
    baseline_wrong = sum(r.accepted and not correct(r) for r in rows)
    return dict(n=len(rows), baseline_correct=baseline_correct, baseline_wrong=baseline_wrong,
                eligible=sum(map(eligible, rows)), eligible_correct=sum(eligible(r) and correct(r) for r in rows),
                gained=len(gained), added_wrong=len(wrong), added_unsupported=len(unsupported),
                added_wrong_ids=wrong,
                final_correct=baseline_correct+len(gained), final_wrong=baseline_wrong+len(wrong))


def experiment(rows):
    train, dev, fresh = ([r for r in rows if r.split == s] for s in ('train','development','fresh12'))
    if len(rows) != 2052 or len(fresh) != 36:
        raise ValueError('Incomplete evaluation')
    weights = fit(train)
    calibrated = {arm: calibrate(dev, arm, weights) for arm in ARMS}
    selected = max(ARMS, key=lambda arm: calibrated[arm])
    cohorts = dict(train=train, development=dev, historical=[r for r in rows if r.split != 'fresh12'],
                   v11=[r for r in rows if r.split == 'reserved'], fresh36=fresh)
    for lang in ('en', 'ar', 'mixed'):
        cohorts['fresh_'+lang] = [r for r in fresh if r.language == lang]
    result = dict(selected=selected, weights=weights, production_eligible=False, arms={})
    for arm, (_, cutoff) in calibrated.items():
        metrics = {name:evaluate(part,arm,weights,cutoff) for name,part in cohorts.items()}
        result['arms'][arm] = dict(threshold=cutoff if math.isfinite(cutoff) else None, cohorts=metrics)
    return result


if __name__ == '__main__':
    report = experiment(read_rows(Path(sys.argv[1])))
    Path(sys.argv[2]).write_text(json.dumps(report, indent=2, allow_nan=False) + '\n')
