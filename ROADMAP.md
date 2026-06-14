# ROADMAP

This roadmap tracks the next improvements for `imini` as a Claude Code-style learning harness.

`imini` already has the core educational harness pieces: agent loop, local llama.cpp model serving, tool calling, file tools, `apply_patch`, codebase navigation, git tools, sessions, checkpoints, retrieval, project memory, permissions, plan mode, todos, MCP, hooks, slash commands, web UI, remote approvals, auth/rate limiting, metrics, Docker, and CI.

The next work should not re-add those features. It should make the existing system easier to learn from, easier to trust, and safer to run.

## 1. Educational completeness

Goal: a developer should be able to learn Claude Code-style harness architecture from this repository in a weekend.

### Next

- Keep `docs/LEARNING_PATH.md` current as the main guided curriculum.
- Keep `docs/TRACE_EDIT.md` current as the canonical end-to-end trace.
- Keep `docs/CONCEPT_MAP.md` current as the mapping from agent concepts to implementation files.
- Add more trace documents:
  - `docs/traces/plan-mode.md`
  - `docs/traces/remote-approval.md`
  - `docs/traces/bad-tool-call-recovery.md`
  - `docs/traces/mcp-tool-call.md`
- Add small exercises at the end of each learning module.
- Add a short demo script for recording a walkthrough video.

### Later

- Add diagrams for the agent loop, approval flow, and persistence flow.
- Add a glossary for terms such as tool call, schema, compaction, checkpoint, MCP, hook, slash command, and sandbox.
- Add a `docs/PRODUCTION_NOTES.md` file that explains what is educational versus production-grade.

## 2. Source readability

Goal: the most important classes should be readable without a formatter or IDE magic.

### Next

- Continue manually reformatting high-value teaching files:
  - `AgentEngine.java`
  - `BuiltinTools.java`
  - `CodebaseTools.java`
  - `ContextManager.java`
  - `CheckpointStore.java`
  - `SessionStore.java`
  - `AgentController.java`
  - `LlamaClient.java`
- Keep formatting-only changes separate from behavior changes whenever possible.
- Avoid huge one-line Java and Markdown files.

### Later

- Reintroduce automated formatting once the local JDK and formatter versions are pinned and verified.
- Add a formatting check in CI after the formatter is stable.

## 3. Deterministic harness tests

Goal: test harness behavior without depending on a live model.

### Done baseline

- Schema validation tests.
- Retry tests.
- Sandbox tests.
- Codebase navigation tests.
- SSE serialization tests.
- Fake-model harness tests.
- Bad-model behavior scenarios.

### Done since

- Plan-mode executor with retry, re-planning, step verification (declared + auto-suggested checks), live SSE streaming, persistence/resume, and tool-call-level audit + per-step transcript -- all with deterministic pure-logic tests (`PlannerTest`, `PlanRecoveryTest`, `StepCheckTest`, `PlanPersistenceTest`, `PlanStreamTest`, `CheckLibraryTest`, `ToolCallTest`).

### Next

- Add scripted traces for the real `AgentEngine` using a fake `LlamaClient` once the engine is easier to instantiate in tests.
- Test plan mode end-to-end: a mutating call should be recorded but not executed.
- Test denied approval recovery: the model should receive a `DENIED` tool result and continue safely.
- Test interrupt/steer behavior with a fake streaming model.
- Test checkpoint grouping for multi-file patches.

### Later

- Add a small offline eval suite that runs against fake model scripts.
- Add a live smoke suite that runs only when a local `llama-server` is available.

## 4. Edit trust and verification

Goal: file changes should be easy to review and hard to misrepresent.

### Done since

- Edit-trust summary: after any run that changed files, a git-verified block (`git status` +
  `git diff --stat`) is appended to the final answer (`EditSummary` + `GitInspector`), and the plan
  synthesis step is prompted to note changed files, verification, and risks. Pure parsing is unit
  tested (`EditSummaryTest`). Toggle with `agent.verify-edits`.

### Done since

- Structured coding report: runs that change files end with a rendered report (changed files, commands
  run, verification, tests not run, risks). Factual fields come from git + the tool recorder; soft
  fields from a small JSON model call (`CodingReport`, unit-tested). Toggle with `agent.coding-report`.

### Done since

- Intermediate diff feedback: after each plan step that changes files, an `[edits this step]` note
  (files changed + `git diff --stat`) is fed into later steps and the final synthesis, so the model can
  react to unexpected diffs mid-plan (`EditSummary.stepNote`, unit-tested). Toggle with
  `agent.plan.step-diff`.

### Next

- (done) Validate/enforce the report schema -- gaps (missing verification/risks/summary for changed
  files) are flagged inline + logged via `CodingReport.validate`; see `agent.coding-report.enforce`.
- Per-step diff *deltas* (snapshot/restore) rather than the cumulative working-tree stat, for precise
  attribution of which step caused which change.

### Later

- Add explicit patch preview tools:
  - `preview_patch`
  - `apply_previewed_patch`
  - `discard_previewed_patch`
- Add a UI diff viewer.
- Add a final-answer schema for coding tasks.
- Add project-specific verification commands in `IMINI.md` or `.imini/config`.

## 5. Codebase understanding

Goal: improve the quality of repo understanding now that the deterministic navigation tools exist.

### Next

- Improve `repo_tree` output for larger repositories.
- Add better caps and paging for `grep`, `read_many`, `git_log`, and `git_blame`.
- Make symbol extraction easier to extend by language.
- Add tests for symbol extraction edge cases.
- Add a simple repository map summary that combines tree, key files, and top symbols.

### Later

- Add optional LSP-backed symbol lookup.
- Add dependency graph summaries for Java/Maven projects.
- Add call-site search for selected languages.
- Add smarter retrieval refresh after file mutation.

## 6. Production safety

Goal: move from educational safety controls to enforceable execution boundaries.

### Biggest gap

The biggest production gap is still command and tool isolation. Command screening and path checks are useful, but they are not a complete security boundary.

### Next

- Document the difference between policy checks and real isolation.
- Make `sandbox.command-mode=allowlist` the recommended shared-deployment setting.
- Add a `/doctor` or startup check that warns when running without containerized command execution.
- Add stricter defaults for shared or Docker deployment profiles.
- Ensure all tool calls include run/session IDs in logs. (Done: mutating tool calls are audited with `session:<id>[ step:N]` attribution.)

### Later

- Run shell commands in a per-run or per-session container/jail.
- Disable network by default for tool execution.
- Mount only the workspace.
- Add CPU, memory, and process limits.
- Harden MCP server execution and permissions.
- Add append-only audit logs for prompts, tool calls, approvals, diffs, and results. (Partly done: request-level + per-tool-call audit entries and a per-step tool transcript now exist; prompts/diffs/results not yet captured.)

## 7. Multi-user and operations

Goal: make the app safer to run for more than one user.

### Next

- Clarify that API-key auth is app-level auth, not full identity/RBAC.
- Add per-key attribution to run logs where missing.
- Add docs for running behind a reverse proxy.
- Add backup/restore notes for `.imini/imini.db`.

### Later

- Per-user workspaces.
- Per-user permission policies.
- OAuth/OIDC or external auth integration.
- Prometheus/OpenTelemetry metrics.
- Admin audit dashboard.

## 8. Monetization and packaging

Goal: package the project as a learning asset before trying to sell it as a developer tool.

### Next

- Add a course outline.
- Add a five-minute demo script.
- Add a landing-page-style section to the README: who this is for and what they will learn.
- Add a clear license if one is missing.

### Later

- Create workshop materials.
- Add optional enterprise hardening modules.
- Consider paid support or consulting only after the learning path is polished.

## Recently completed

- Web UI plan-history + report viewer: a *Plan history* card lists a session's past plans and expands
  any one to its step checklist (with per-step tools) and coding report, reusing the streaming plan
  renderer; refreshes on run completion and session switch; ownership/shared-read scoped.
- Skills Phase 3 (remote repositories, read-only): an allowlist of git URLs (`skills.repos`) is
  cloned/pulled read-only into a cache and merged with local skills (local overrides remote,
  earlier-repo-wins via `SkillLibrary.merge`, unit-tested); `refresh_skills` re-pulls. Instructions
  only -- no executable bundles. A skill registry with provenance/signing is the remaining piece.
- Session sharing / ownership transfer: an owner can grant read access (`POST /share`/`/unshare`),
  inspect access (`GET /shares`), and transfer ownership (`POST /transfer`, prior owner kept as reader);
  read endpoints resolve via `Ownership.canRead` (unit-tested), management stays owner/admin-only.
- Skills (Phase 1 + 2): reusable `SKILL.md` instruction bundles discovered from `skills/`, a short
  index injected into the system prompt, `load_skill` (progressive disclosure) + `save_skill` (capture
  knowledge) tools, optional `skills.auto-load` for weaker models (`SkillLibrary` pure + unit-tested,
  `SkillService`). Read-only instructions only -- no executable bundles or remote repos yet.
- Plan history: completed plans are archived per session (goal + final checklist + per-step tool
  transcript + coding report), listed at `GET /plans` and fetched at `GET /plan?n=<seq>`
  (`PlanHistory`, pure `summarize` unit-tested; `agent.plan.history-max`).
- Coding-report schema enforcement: incomplete reports (no verification/risks/summary for changed
  files) are flagged inline + logged (`CodingReport.validate`, unit-tested; `agent.coding-report.enforce`).

## Current recommended priority

The plan-mode arc is now complete end to end: structured execution, retry, re-planning, step
verification (declared + suggested checks), live UI streaming, persistence/resume, and tool-call-level
audit with a per-step transcript that is now surfaced in the web UI.

The next highest-leverage engineering changes are:

> 1. Skill registry (Phase 3+): a searchable index of available skills with provenance -- a manifest of
>    `{name, description, source, version, hash}`, a `search_skills` tool over it, and hash-verified
>    install -- plus commit pinning for the remote repos just added. Executable skill bundles remain
>    deferred behind the sandbox command policy.
> 2. Per-step diff *deltas* (snapshot/restore) for precise attribution of which step changed what,
>    replacing the cumulative working-tree stat.
> 3. A sharing surface in the web UI (grant/transfer/visualize who can see a session), so the
>    just-added session-sharing endpoints are usable without curl.

Both are much smaller than full sandboxing and continue to improve trust and learnability.
