# imini Workshop: Build-Your-Own-Agent in 90 Minutes

A packaged, hands-on session that turns the self-study material into a guided workshop. It works **solo**
or **facilitated** (one screen, a small group). The throughline: *the model is not the agent — the harness
is.* Each lab ends with a **checkpoint** you can verify, including a `mvn test` target that needs no live
model.

**Audience:** developers new to agent harnesses. **Prereq:** imini runs and `ask.bat "hello"` returns an
answer (see [`../GettingStarted.md`](../GettingStarted.md) and [`../INSTALL.md`](../INSTALL.md)). Read the
[`GLOSSARY.md`](GLOSSARY.md) first (5 min).

**Format:** 5 labs, ~15 minutes each, + intro/wrap. Times are guidance, not rules. For a group, have one
person drive while others read the files named in each lab.

---

## 0. Frame the day (5 min)

State the goal: by the end, everyone can explain why the model and harness are separate, and how a tool
call is validated and executed. Open [`../ARCHITECTURE.md`](../ARCHITECTURE.md) section 2 (the request
lifecycle) and keep it visible.

## Lab 1 — Model vs. harness (15 min)

**Do:** `run.bat`, then `ask.bat "Say hello in one sentence."` Watch the first window stream tokens.

**Read:** `LlamaServerManager.java`, `LlamaClient.java`, `AgentController.java`.

**Discuss:** Where does the model stop and the harness begin? What would break if you swapped the model?

**Checkpoint:** You can point to the exact method that sends a prompt to llama-server.

## Lab 2 — The agent loop and tools (20 min)

**Do:** `ask.bat "Use repo_tree to inspect the project, then grep for class AgentEngine and read it."`

**Read:** `AgentEngine.java` (the `converse` loop), `ToolRegistry.java`, `CodebaseTools.java`.

**Discuss:** The model asked for tools by name — who decided those tools exist, validated the arguments,
and ran them? What stops the model from calling something that isn't offered?

**Checkpoint (test):** `mvn test -Dtest=CodebaseToolsTest` — deterministic tools, no model needed.

## Lab 3 — Safety: permissions, checkpoints, rewind (20 min)

**Do:**
```
ask.bat "Create scratch-workshop.txt with one sentence about imini." --mode ask
ask.bat "Append a second sentence to scratch-workshop.txt." --mode auto
curl -X POST http://localhost:8080/rewind -H "Content-Type: application/json" -d "{\"sessionId\":\"default\"}"
```

**Read:** `PermissionService.java`, `BuiltinTools.java`, `CheckpointStore.java`.

**Discuss:** Why do read-only tools skip approval while mutating tools don't? Why snapshot *before* a
change? What scope must a checkpoint have to be safe?

**Checkpoint (test):** `mvn test -Dtest=BadModelBehaviorTest` — path confinement and guards hold even when
the model misbehaves.

## Lab 4 — Plan mode and context (15 min)

**Do:** `plan.bat "Add a doc that explains model vs harness."` Note that nothing is written. Then create an
`IMINI.md` with `When editing docs, prefer concise prose.` and run
`ask.bat "What writing preference is active for this project?"`

**Read:** `AgentLoop.java`, `ProjectContext.java`, `ContextManager.java`.

**Discuss:** How does plan mode avoid mutation? How does always-on project memory differ from a slash
command? When does the **token budget** trim context, and when does a turn auto-switch to plan mode?

**Checkpoint (test):** `mvn test -Dtest=TokenBudgetTest,PlanFallbackTest`.

## Lab 5 — Reliability and a full trace (15 min)

**Do:** `mvn test` (whole suite). Then read one trace end to end.

**Read:** [`TRACE_EDIT.md`](TRACE_EDIT.md); `FakeModelHarnessTest.java`, `LoopCorrectnessTest.java`.

**Discuss:** Which reliability properties are tested *without* a model? Why is "agent reliability" not the
same as "model quality"?

**Checkpoint:** You can walk the trace from user prompt to final answer and name each stage.

## Lab 6 — The write workflow, end to end (20 min)

**Read:** [`WORKFLOW_WALKTHROUGH.md`](WORKFLOW_WALKTHROUGH.md) — the edit → verify → commit loop, the six
hook events, and the MCP server lifecycle, each with a diagram.

**Do:** run the three golden/integration tests and read their assertions as the executable version of the
diagrams.

**Discuss:** Where does the permission gate sit in the loop? What does a hook see at each event? How does an
MCP prompt become a `/`-slash-command, and what reaches the model when you invoke it?

**Checkpoint (test):** `mvn test -Dtest=GoldenTraceWorkflowTest,McpLiveIntegrationTest,GitCommitApprovalFlowTest`
— the golden trace drives the real agent loop (edit→stage→commit) with a scripted model; the MCP test
exercises stdio + HTTP (incl. streaming SSE); the approval test shows the staged diff on the approval payload.

## Wrap-up (5 min)

Read [`CONCEPT_MAP.md`](CONCEPT_MAP.md) together: each imini piece maps to a Claude Code category (context,
tools, permissions, sessions, checkpoints, MCP, hooks, subagents, navigation). Close on the completion
checklist at the end of [`LEARNING_PATH.md`](LEARNING_PATH.md).

---

## Facilitator notes

- **Keep the first window visible** the whole time — watching tokens stream makes "model vs harness"
  concrete.
- **The checkpoints are the assessment.** If the `mvn test` targets pass and people can explain them,
  the lab landed. No live-model output is graded (it varies).
- **Going long?** Labs 1–3 are the core; 4–5 are extension. **Have extra time?** Try `/loop`, scheduled
  tasks, or export a plugin pack (README sections) as bonus rounds.
- **Reset between groups:** delete any `scratch-*.txt` files and the local `.imini/` state.
