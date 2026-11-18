# Quantity evidence in local FAQ acceptance

The v15 experiment applies a deterministic Java veto to the frozen v14 selected policy. It recognizes a small vocabulary of quantitative requests in English, Egyptian Arabic and mixed wording. A recognized history-input limit or local bridge port requires a matching affirmative sentence in the proposed topic's canonical English FAQ answer. The result carries the article ID, verbatim sentence and captured value. Changed values are read from the source. Missing evidence, multiple matching sentences, an invalid port, recognized incompatible units and recognized scope changes cause abstention. PASS leaves the parent decision intact. Only SUPPORTED provides a witness. Decisions never upgrade a refusal.

This is a hand-written specialist for two FAQ facts. The experiment adds no learned parameters. The vocabulary does not cover arbitrary requests, scopes or paraphrases. A witness identifies a relevant sentence and value under these limited rules. It is not a general entailment score. A rewrite of a documented fact may require a new extraction pattern. Queries over 512 UTF-16 units abstain.

| Evaluated questions | Before correct / wrong | After correct / wrong | Wrong prevented | Correct lost |
|---|---:|---:|---:|---:|
| Previously observed 2,196 | 1,036 / 67 | 1,036 / 57 | 10 | 0 |
| New 36 | 7 / 4 | 7 / 0 | 4 | 0 |
| New English 12 | 2 / 2 | 2 / 0 | 2 | 0 |
| New Egyptian Arabic 12 | 2 / 0 | 2 / 0 | 0 | 0 |
| New mixed 12 | 3 / 2 | 3 / 0 | 2 | 0 |

The new examples are 12 authored groups with three related language variants. Their IDs and evaluation protocol were reserved before implementation. The rule author had access to the questions. This small targeted regression set does not provide a blind accuracy estimate.

Supplying the named reference directly isolates the check from retrieval. Of 15 supported requests, six quantitative requests returned source sentences and nine ordinary ones passed. Of 21 unsupported requests, 19 abstained. Two mixed questions about a hosted relay or a separate handset passed the rule, but the upstream model had already refused them. Reporting only the final zero wrong answers hides these misses. The full system still declined eight supported requests.

The host script replays the v14 baseline row by row and checks the decisions, original sentence text and captured values against committed fixtures. The API 30 instrumentation contract compares all 36 named-reference outcomes against the same goldens. Run the host experiment with `bash scripts/train_topic_rerank.sh`. The Android check runs in the neural-core CI emulator. No production activation or physical handset measurement is included.
