---
name: omnidev-orchestrating-agents
description: Multi-agent orchestration for OmniDev. Use when work is genuinely decomposable, needs specialist roles, independent verification, parallel research, or coordinated implementation across multiple concerns; avoid for simple single-agent tasks.
---

# OmniDev Agent Orchestration

Use multiple agents only when specialization or parallelism produces a real advantage. A swarm is an execution topology, not a quality setting.

## Choose topology dynamically

- Simple/local task: one agent.
- Bounded specialist task: manager calls one specialist and keeps final-answer ownership.
- Complex implementation: Planner → independent workers where safe → Evaluator.
- Research: Lead researcher → parallel, non-overlapping researchers → synthesis/evidence check.
- High-risk change: Planner → Executor → isolated Verifier with a frozen acceptance rubric.

## Delegation contract

Every delegated task must state: id, objective, exact scope, dependencies, inputs/evidence already known, allowed tool domain, expected output, acceptance criteria, and forbidden overlap. Avoid vague prompts such as “investigate this.”

## Parallelism

Parallelize only tasks with no write conflict or unmet dependency. Never let two workers edit the same file/state concurrently. Prefer parallel discovery and tests; serialize shared workspace mutations unless isolation/worktrees exist.

## Verification independence

The evaluator judges artifacts and observable evidence, not the executor's reasoning. Give it the original acceptance criteria plus resulting diff/state/test output. A failed gate returns structured findings to the executor; it does not get relabeled as success.

## Anti-loop controls

Track task IDs, dependency graph, worker failures, repeated handoffs, token/tool budgets, and progress. If two worker/evaluator cycles produce no new evidence, re-plan instead of oscillating. A failed dependency must be explicit in synthesis.

## Synthesis

One owner produces the final user-facing answer. Preserve disagreements and failed subtasks; never average contradictory evidence into a confident claim.
