"""Topic-disjoint comparison of FAQ refusal recovery on newly authored contrast pairs."""
import json
import math
import sys
from pathlib import Path

from fit_refusal_rescue import calibrate_scores, correct, eligible, evaluate, features, fit_samples, score
from fit_topic_rerank import read_rows

ROOT = Path(__file__).resolve().parents[1]
ARMS = ('refused', 'all_hits')


def partition(rows):
    parts = {}
    seen = set()
    for split, count in (('train', 8), ('dev', 4), ('test', 6)):
        part = [r for r in rows if r.split == 'paired13-' + split]
        groups = {}
        for r in part:
            topic, label, lang = r.id.removeprefix('paired13-').rsplit('-', 2)
            expected = {topic} if label == '1' else set()
            if r.language != lang or r.labels != expected:
                raise ValueError('Fixture identity or polarity mismatch')
            groups.setdefault(topic, set()).add((label, lang))
        pairs = {(label, lang) for label in ('0','1') for lang in ('ar','en','mixed')}
        if (len(part) != 6*count or len(groups) != count or seen.intersection(groups)
                or any(items != pairs for items in groups.values())):
            raise ValueError('Incomplete or overlapping topic groups')
        seen.update(groups)
        parts[split] = part
    return parts


def train_policy(train, dev, arm):
    if arm not in ARMS or any(r.split != 'paired13-train' for r in train) or any(
            r.split != 'paired13-dev' for r in dev):
        raise ValueError('Invalid fitting role or arm')
    fitting = [r for r in train if r.hits and (arm == 'all_hits' or eligible(r))]
    weights = fit_samples([(features(r), int(correct(r))) for r in fitting])
    gains, threshold = calibrate_scores([(score(r, 'logistic', weights), correct(r))
                                        for r in dev if eligible(r)])
    return dict(weights=weights, gains=gains, threshold=threshold, samples=len(fitting),
                positives=sum(map(correct,fitting)), kind='logistic')


def accepts(row, policy):
    return row.accepted or (eligible(row) and score(row, policy['kind'], policy['weights'])
                            >= policy['threshold'])


def paired_outcomes(rows, policy):
    decisions = {r.id: accepts(r, policy) for r in rows}
    outcomes = []
    for r in rows:
        if r.labels:
            stem, _, lang = r.id.rsplit('-', 2)
            outcomes.append(decisions[r.id] and correct(r) and not decisions[f'{stem}-0-{lang}'])
    return dict(complete=sum(outcomes), total=len(outcomes))


def experiment(rows, out):
    parts = partition(rows)
    history = [r for r in rows if not r.split.startswith('paired13-')]
    if len(history) != 2052 or len(rows) != 2160:
        raise ValueError('Replay count mismatch')
    policies = {arm: train_policy(parts['train'], parts['dev'], arm) for arm in ARMS}
    selected = max(ARMS, key=lambda arm: policies[arm]['gains'])
    prior = json.loads((ROOT/'neural-core/checkpoints/refusal-rescue-v12/report.json').read_text())
    for kind in ('logistic','retrieval'):
        policies['v12_'+kind] = dict(kind=kind, weights=prior['weights'],
                                     threshold=prior['arms'][kind]['threshold'])
    cohorts = dict(history=history, **parts)
    for lang in ('ar','mixed','en'):
        cohorts['test_'+lang] = [r for r in parts['test'] if r.language == lang]
    result = dict(selected=selected, production_eligible=False, arms={})
    for name, policy in policies.items():
        metrics = {}
        for split, part in cohorts.items():
            m = evaluate(part, policy['kind'], policy['weights'], policy['threshold'])
            if split != 'history':
                m['pairs'] = paired_outcomes(part, policy)
            metrics[split] = m
        result['arms'][name] = dict(**policy, metrics=metrics)
        if math.isinf(policy['threshold']):
            result['arms'][name]['threshold'] = None
    policy = policies[selected]
    (out/'report.json').write_text(json.dumps(result, indent=2, allow_nan=False)+'\n')
    (out/'policy.tsv').write_text('\t'.join(map(str, policy['weights']))+'\n'+
                                  (str(policy['threshold']) if math.isfinite(policy['threshold']) else 'Infinity')+'\n')
    (out/'predictions.tsv').write_text(''.join(f'{r.id}\t{int(accepts(r,policy))}\n' for r in rows))


if __name__ == '__main__':
    experiment(read_rows(Path(sys.argv[1])), Path(sys.argv[2]))
