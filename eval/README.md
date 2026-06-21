# Evaluation suite

This directory holds a curated, model-graded evaluation suite for the **agent**, distinct from the
deterministic golden traces (which check harness *correctness* without a model — see
[`../docs/WORKFLOW_WALKTHROUGH.md`](../docs/WORKFLOW_WALKTHROUGH.md) §4).

## Format

`suite.txt` has one case per line:

```
id | match | expected | prompt
```

- **match** is `contains`, `regex`, or `equals` (case-insensitive), mapping to `EvalHarness.Match`.
- Lines that are blank or start with `#` are ignored; malformed lines are skipped.
- The `prompt` may itself contain `|` (only the first three delimiters are significant).

`EvalHarness.loadCases()` reads this file (path overridable with `eval.suite-file`) and falls back to the
small built-in `defaultCases()` if it is missing or empty. The pure parser (`EvalHarness.parseCases`) is
unit-tested in `EvalSuiteFileTest`.

## Running

- `POST /admin/eval` (admin) runs the suite through the live agent and returns a pass-rate plus per-case
  detail; it self-skips (`{skipped:true}`) when the model is unreachable.
- The opt-in `.github/workflows/eval-gate.yml` workflow boots a tiny model, runs the suite, and **fails the
  build if the pass-rate drops below its threshold** — turning this into a quality gate. It is not on every
  push (it needs a model and CPU inference); trigger it from the Actions tab or via the `run-eval-gate` PR
  label.

## Extending

Add lines for your domain. Prefer `contains`/`regex` for free-form answers and `equals` only when you can
constrain the output exactly (e.g. "reply with one lowercase word"). Keep prompts self-contained and
deterministic so pass/fail reflects the agent, not prompt ambiguity.

### Harness-behavior cases

The shipped suite also includes a few cases that require the agent to **use a tool** before answering — for
example reading `fixtures/note.txt` or listing `fixtures/` — so the suite exercises the agent loop (tool
dispatch + incorporating the tool result), not just the model's factual recall. The `fixtures/` directory
holds the small committed files those cases read. These cases naturally need a capable-enough live model to
actually call the tool; they still self-skip with the rest of the suite when no model is reachable.
