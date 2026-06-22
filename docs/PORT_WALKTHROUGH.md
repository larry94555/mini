# Port walkthrough — a cross-project task, end to end

This page walks through the motivating Track B task end to end and points at the golden trace that proves it:

> "Create a project at `C:\Users\larry\github\typescript-project` that is the TypeScript equivalent of the
> code at `C:\Users\larry\github\mini`."

It ties together the three Track B building blocks — the [workspace-roots registry](MULTI_ROOT.md), the
approval-gated grant tools, and the transactional `create_project` scaffold — plus per-session scoping.

## Prerequisites

Multi-root is **off by default**; enable it for the run:

```properties
agent.multi-root.enabled=true
```

Nothing else is pre-granted. The agent starts with only the default workspace root (read_write), exactly as
a single-workspace session.

## The flow

1. **Grant the source (read).** The agent calls
   `grant_workspace_root {path: "C:\\Users\\larry\\github\\mini", access: "read"}`. Because grant tools are
   always-confirm, you are prompted to approve **even in `auto` mode**. After approval the source is readable
   but not writable, and the grant is scoped to *this session* and written to the audit log.
2. **Grant the destination (read_write).** `grant_workspace_root {path:
   "C:\\Users\\larry\\github\\typescript-project", access: "read_write"}` → you approve again. The
   destination is now readable and writable for this session.
3. **Read & translate.** The agent reads the source project (the read root) and assembles the TypeScript
   equivalent as a `create_project` manifest. Translation quality is the model's job; the harness guarantees
   the safety envelope and the file operations.
4. **Plan.** `create_project {root: dest, files: [...], plan_only: true}` returns the planned tree and
   per-file byte counts, writing nothing, so you can review the manifest before it lands.
5. **Write.** `create_project {root: dest, files: [...]}` is approved and the project is written
   **transactionally** (staged in a temp dir, moved all-or-nothing, rolled back on failure).
6. **Verify.** The written files exist under the destination with the expected content; the source root was
   never written to; any path outside the granted read_write root is refused.

## What keeps it safe

- **Default-closed:** with `agent.multi-root.enabled=false` none of this is possible; behavior is identical
  to the single-workspace harness.
- **Per-path, per-access grants:** the source is `read`, the destination is `read_write`; a write into the
  source is denied.
- **Per-session:** a root granted in this run is invisible to other sessions — one run cannot widen
  another's access.
- **Approval at the boundary:** every grant is always-confirm (never auto-approved); `create_project` shows
  a manifest summary (root, file count, total bytes, tree), not raw content.
- **Transactional writes:** all-or-nothing, with rollback, and no silent overwrite (`overwrite=true`
  required to replace existing files).
- **Audited:** each grant/revoke and each `create_project` is written to the audit log.

## Proven by a golden trace

`CreateProjectTraceTest.grantThenPlanThenWrite` drives the **real `AgentEngine`** with a scripted model
through exactly this sequence (grant read source → grant read_write destination → `create_project`
`plan_only` → `create_project` write → answer) and asserts: both grants were gated (not auto-approved
despite `auto`), the grants are scoped to the run's session (another session does not see them), the files
were written with the expected content, and the source root was never written to. See `TESTING.md` cases
605-608 and [`docs/MULTI_ROOT.md`](MULTI_ROOT.md) for the registry/tool details.
