"""Retrospective rotation across the 18 authored FAQ topics, after the reserved evaluation."""
import json
import sys
from dataclasses import replace
from pathlib import Path

from fit_paired_rescue import ARMS, paired_outcomes, partition, train_policy
from fit_refusal_rescue import evaluate
from fit_topic_rerank import read_rows


def rotate(rows):
    parts = partition(rows)
    by_topic = {}
    for part in parts.values():
        for row in part:
            topic = row.id.removeprefix('paired13-').rsplit('-', 2)[0]
            by_topic.setdefault(topic, []).append(row)
    topics = sorted(by_topic)
    folds = []
    for idx, held in enumerate(topics):
        calibration = {topics[(idx + offset) % len(topics)] for offset in (1, 5, 9, 13)}
        train = [replace(r, split='paired13-train') for t in topics
                 if t != held and t not in calibration for r in by_topic[t]]
        dev = [replace(r, split='paired13-dev') for t in sorted(calibration) for r in by_topic[t]]
        policies = {arm: train_policy(train, dev, arm) for arm in ARMS}
        best = max(ARMS, key=lambda arm: policies[arm]['gains'])
        p = policies[best]
        m = evaluate(by_topic[held], 'logistic', p['weights'], p['threshold'])
        folds.append(dict(held=held, dev=sorted(calibration), selected=best, metrics=m,
                          pairs=paired_outcomes(by_topic[held], p)))
    total = {k:sum(f['metrics'][k] for f in folds) for k in
             ('n','baseline_correct','baseline_wrong','gained','added_wrong','added_unsupported')}
    return dict(retrospective=True, topics=len(topics), total=total, folds=folds)


if __name__ == '__main__':
    Path(sys.argv[2]).write_text(json.dumps(rotate(read_rows(Path(sys.argv[1]))), indent=2)+'\n')
