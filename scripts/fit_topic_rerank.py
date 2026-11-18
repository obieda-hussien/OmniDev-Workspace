"""Fit and replay the preregistered v11 top-three reference policy (Python standard library)."""
import argparse
import json
import math
from dataclasses import dataclass
from itertools import product
from pathlib import Path


@dataclass(frozen=True)
class Hit:
    topic: str
    retrieval: float
    coverage: float
    probability: float


@dataclass(frozen=True)
class Row:
    id: str
    split: str
    language: str
    labels: frozenset
    accepted: bool
    hits: tuple
    proposed: bool = True


def read_rows(path):
    rows = []
    for line in path.read_text().splitlines():
        id_, split, language, labels, proposed, flag, *items = line.split('\t')
        hits = []
        for item in items:
            topic, *values = item.split(':')
            hit = Hit(topic, *map(float, values))
            if not all(math.isfinite(v) for v in (hit.retrieval, hit.coverage, hit.probability)):
                raise ValueError(f'Nonfinite score for {id_}')
            if not 0 <= hit.probability <= 1 or not 0 <= hit.coverage <= 1:
                raise ValueError(f'Unbounded score for {id_}')
            hits.append(hit)
        if proposed not in ('true', 'false') or (flag == 'true' and proposed != 'true'):
            raise ValueError(f'Inconsistent proposal for {id_}')
        if flag not in ('true', 'false') or len(hits) > 3 or len({h.topic for h in hits}) != len(hits):
            raise ValueError(f'Invalid candidate set for {id_}')
        if flag == 'true' and (not hits or hits[0].probability < .1):
            raise ValueError(f'Inconsistent v10 acceptance for {id_}')
        rows.append(Row(id_, split, language, frozenset(filter(None, labels.split(','))),
                        flag == 'true', tuple(hits), proposed == 'true'))
    if len({r.id for r in rows}) != len(rows):
        raise ValueError('Duplicate query IDs')
    return rows


def choose(row, coverage, probability, margin):
    if not row.accepted:
        return None
    first = row.hits[0]

    def score(hit):
        return hit.retrieval + coverage * hit.coverage + probability * hit.probability

    best = max((h for h in row.hits if h.probability >= .1), key=score)
    return best.topic if score(best) - score(first) > margin else first.topic


def evaluate(rows, policy):
    values = dict(count=len(rows), accepted=0, baseline_correct=0, correct=0, wrong=0,
                  switched=0, gained=0, lost=0, oracle_correct=0, refused_supported=0,
                  accepted_unsupported=0, refused_correct_first=0, refused_correct_top3=0, supported_proposer_refusals=0, supported_vetoes=0)
    changes = []
    for row in rows:
        topic = choose(row, *policy)
        if topic is None:
            values['refused_supported'] += bool(row.labels)
            if row.labels:
                values['supported_vetoes' if row.proposed else 'supported_proposer_refusals'] += 1
            values['refused_correct_first'] += bool(row.hits and row.hits[0].topic in row.labels)
            values['refused_correct_top3'] += any(h.topic in row.labels for h in row.hits)
            continue
        baseline = row.hits[0].topic
        before, after = baseline in row.labels, topic in row.labels
        values['accepted'] += 1
        values['accepted_unsupported'] += not row.labels
        values['baseline_correct'] += before
        values['correct'] += after
        values['wrong'] += not after
        values['oracle_correct'] += any(h.topic in row.labels and h.probability >= .1 for h in row.hits)
        values['gained'] += after and not before
        values['lost'] += before and not after
        if topic != baseline:
            values['switched'] += 1
            changes.append(dict(id=row.id, old=baseline, new=topic, before=before, after=after))
    assert values['correct'] == values['baseline_correct'] + values['gained'] - values['lost']
    assert values['correct'] <= values['oracle_correct']
    return values, changes


def fit(train, dev):
    if len(train) != 507 or len(dev) != 135 or any(r.split != 'train' for r in train) or any(
            r.split != 'development' for r in dev):
        raise ValueError('Unexpected fitting roles')
    trials = []
    for c, p in product((0, .25, .5, 1), (0, .25, .5, 1, 2)):
        scores, _ = evaluate(train, (c, p, 0))
        trials.append((scores['correct'], -scores['lost'], -c, -p))
    _, _, nc, np = max(trials)
    c, p = -nc, -np
    margins = []
    for margin in (0, .025, .05, .1, .2, math.inf):
        scores, _ = evaluate(dev, (c, p, margin))
        if not scores['lost']:
            margins.append((scores['correct'], -scores['switched'], margin))
    policy = (c, p, max(margins)[2])
    grid = [dict(coverage=-nc, probability=-np, correct=correct, lost=-loss)
            for correct, loss, nc, np in trials]
    return policy, grid


def report(rows):
    train = [r for r in rows if r.split == 'train']
    dev = [r for r in rows if r.split == 'development']
    policy, grid = fit(train, dev)
    reserved = [r for r in rows if r.split == 'reserved']
    previous = [r for r in rows if r.split != 'reserved']
    if len(previous) != 1968 or len(reserved) != 48:
        raise ValueError('Incomplete evaluation')
    result = dict(coverage=policy[0], probability=policy[1],
                  margin='infinity' if math.isinf(policy[2]) else policy[2],
                  production_eligible=False, train_grid=grid, cohorts={})
    cohorts = dict(train=train, development=dev, previous=previous, reserved=reserved)
    for lang in ('en', 'ar', 'mixed'):
        cohorts['reserved_' + lang] = [r for r in reserved if r.language == lang]
        if len(cohorts['reserved_' + lang]) != 16:
            raise ValueError('Language imbalance')
    for name, cohort in cohorts.items():
        selected, changes = evaluate(cohort, policy)
        unrestricted, _ = evaluate(cohort, (*policy[:2], 0))
        result['cohorts'][name] = dict(selected=selected, unrestricted=unrestricted, changes=changes)
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('features', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    args.output.write_text(json.dumps(report(read_rows(args.features)), indent=2, allow_nan=False) + '\n')
