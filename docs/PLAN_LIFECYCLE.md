# Plan-lifecycle hooks

Skills normally surface by relevance: the agent sees a short index and loads a skill on demand. **Plan-lifecycle
hooks** let you additionally *bind* skills to specific stages of a plan-mode run, so they are applied
automatically at the right moment — completing the `skill-builder` workflow (prepare a plan, review it,
sub-plan a step, select a tool, evaluate against the goal, and run a post-mortem).

## Stages

`prepare`, `review`, `sub-plan`, `tool-select`, `goal-eval`, `post-mortem`.

## Configure

Bind skills to stages with `skills.lifecycle` (empty by default — a no-op that changes nothing):

```
skills.lifecycle=prepare=skill-builder,code-review; tool-select=tool-builder; goal-eval=code-review
```

Entries are separated by `;` or newlines; each is `<stage>=<comma/space-separated skill names>`. Unknown
stages are ignored.

## Behavior

During a plan-mode run, the agent consults the registry for the stage and appends the bound skills' bodies to
that stage's prompt:
- **prepare** when the plan is first drafted,
- **sub-plan** as each step is worked,
- **goal-eval** when the final answer is synthesized.

Only skills both **bound** to the stage and **available** (enabled) are applied; they are ordered by relevance
to the plan/goal text using the same lexical/BM25 scorer used elsewhere, with bound-but-unmatched skills
retained in binding order. When no skills are bound to a stage, nothing is injected and unbound skills surface
exactly as before.

Inspect the active bindings at `GET /admin/skills/lifecycle` (admin).

## Design (pure core)

`PlanLifecycle` is pure and offline-tested: `Stage` (with stable ids), `Bindings.parse` (the config grammar),
and `selectForStage(stage, bindings, skills, planText, k)` (reuses `SkillLibrary.select`; an empty registry
returns an empty list). See TESTING cases 648-649.
