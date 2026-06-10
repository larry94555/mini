# Testing imini

This file focuses on **manual learning checks**.
Use it when you want to see a feature in action.

For deterministic CI-oriented harness checks, see the JUnit tests under `src/test/java/com/example/imini`.

---

## Before you start

1. Start the app with `run.bat`.
2. Wait for `Started MiniAgentApplication`.
3. Keep the app window visible for tool logs and guard messages.
4. Use a second terminal for `ask.bat`, `chat.bat`, `plan.bat`, `rewind.bat`, `interrupt.bat`, and `steer.bat`.
5. Leave approvals in **ask** mode unless the test says otherwise.

If a small model ignores a tool on the first try, re-run the prompt and name the tool explicitly.
That is a model limitation, not a harness failure.

---

## 1. Streaming

**Run**

```bat
ask.bat "In one sentence, what is a large language model?"
```

**Observe**

- streaming output appears incrementally,
- the run finishes with a normal one-sentence answer.

---

## 2. web_fetch success and failure

**Run**

```bat
ask.bat "Use web_fetch on https://text.npr.org and list three headlines you see."
```

**Observe**

- a `web_fetch` tool call is logged,
- the answer uses extracted article text, not HTML tag soup.

**Failure path**

```bat
ask.bat "Use web_fetch on https://httpstat.us/404 and tell me what happened."
```

**Observe**

- the tool returns an explicit error,
- the model reports the failure rather than inventing content.

---

## 3. edit_file + approval flow

Create `notes.txt` with:

```text
Project: imini
Status: draft
Owner: me
```

**Run**

```bat
ask.bat "In notes.txt, change the line 'Status: draft' to 'Status: final'."
```

**Observe**

- the model may inspect the file first,
- a mutating tool request is raised,
- approval is required in **ask** mode.

### Approval behavior

If remote approvals are enabled, approve the action in the browser/API flow.
If remote approvals are not enabled, the console prompt remains the fallback behavior.

This replaces the older assumption that approvals are always console-only.

**Also try denial**

Deny the edit and confirm that the file remains unchanged.

---

## 4. Checkpoint and rewind

After a successful edit:

```bat
rewind.bat
```

**Observe**

- the rewind endpoint reports the restored file,
- `notes.txt` returns to its earlier state.

---

## 5. Sessions and persistence

**Run**

```bat
chat.bat work1 "Remember that my favorite color is teal."
chat.bat work1 "What is my favorite color?"
```

**Observe**

- the second answer uses session memory.

Then restart the app and run:

```bat
chat.bat work1 "What is my favorite color?"
```

**Observe**

- the answer still uses the saved session.

---

## 6. Sub-agent isolation

**Run**

```bat
ask.bat "Use delegate_research to find what the James Webb Space Telescope is, then give me a two-sentence summary."
```

**Observe**

- sub-agent tool activity appears separately,
- the main answer stays concise,
- the noisy search trace does not become the final answer.

---

## 7. Plan mode

**Run**

```bat
plan.bat "Edit notes.txt to say done and create out.txt."
```

**Observe**

- the agent describes intended actions,
- the files are not actually changed.

---

## 8. Permission rules and remembered decisions

If you have a `permissions.json`, test both an allow rule and a deny rule.

Then test a remembered decision:

```bat
ask.bat "Run the command: echo hi"
```

Approve with the equivalent of “always”, then run the same command again.

**Observe**

- the second run should not require the same approval.

---

## 9. Workspace confinement

**Run**

```bat
ask.bat "Write hi to C:\\Windows\\x.txt"
```

**Observe**

- the operation is denied because the target is outside the workspace.

This is one of the most important harness checks because it verifies that the model cannot freely edit arbitrary paths.

---

## 10. Todos

**Run**

```bat
ask.bat "Using todo_write, plan 3 steps to add license headers without actually doing them."
```

**Observe**

- the todo list is updated,
- the current checklist is visible through the todo surface.

---

## 11. Parallel read-only tools

**Run**

```bat
ask.bat "Fetch https://text.npr.org and https://lite.cnn.com and give one headline from each."
```

**Observe**

- independent read-only calls overlap,
- the answer contains one result from each source.

---

## 12. Interrupt and steer

Start a long run, then in another terminal:

```bat
interrupt.bat
```

or:

```bat
steer.bat "Actually, answer in French."
```

**Observe**

- the current run stops or changes direction,
- controls are intended to apply to the active session/run rather than as a global conceptual feature.

This wording replaces the older docs that described interrupt/steer only as a single global signal.

---

## 13. Project memory

Copy `IMINI.example.md` to `IMINI.md` and add a project-specific instruction such as a preferred build command.

Then run:

```bat
ask.bat "What build command should I use?"
```

**Observe**

- the answer reflects your project memory file.

---

## 14. Injection hardening

Use a page containing embedded instructions and fetch it through a web tool.

**Observe**

- untrusted content is fenced as data,
- the answer summarizes content instead of following the embedded instruction.

---

## 15. Hooks and slash commands

For hooks:

- copy `hooks.example.json` to `hooks.json`,
- run a simple command tool call,
- observe pre/post behavior.

For slash commands:

```bat
ask.bat "/help"
ask.bat "/explain recursion"
```

**Observe**

- `/help` lists commands without needing the model,
- `/explain` expands a template before the model sees it.

---

## 16. What to test in CI instead of manually

The following belong in deterministic JUnit coverage instead of manual repetition:

- schema validation edge cases,
- retry contracts,
- command-screening policy,
- confinement normalization,
- grammar generation,
- auth/rate-limit behavior,
- approvals lifecycle,
- retrieval scoring.

This patch expands that deterministic coverage so the repo teaches both sides of agent engineering:

- **manual** feature observation, and
- **automated** harness correctness.
