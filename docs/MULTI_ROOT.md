# Multi-root project work (Track B)

By default `imini` works inside a single workspace: one root directory (the current directory, or
`agent.workspace-root`) that bounds every file read and write. That is enough for "edit and run code in this
one repo," but not for real cross-project tasks such as *"create a TypeScript project at path B that ports
the code at path A."* Track B adds a **registry of workspace roots** so the harness can, with explicit user
approval, read a second project and scaffold a new one — without ever weakening the single-root default.

This page describes the model and the access levels introduced by **PR #1 (the registry)**. The
approval-gated grant tools and the transactional project scaffold are later, separately-gated PRs; see the
"Roadmap" section below and `ROADMAP.md`.

## The model

The single `root` is replaced by a `WorkspaceRoots` registry. Each root has:

- an **id** — `default` for the primary root, `r1`, `r2`, … for additional roots;
- an **absolute, normalized path**;
- an **access level** — `READ` or `READ_WRITE`.

The **default root** (current directory, or `agent.workspace-root`) is always present and always
`READ_WRITE`. It can never be removed or downgraded. Path containment uses the same `isWithin` logic as the
rest of the harness, so a candidate path is "inside" a root only if it resolves underneath it.

Two questions the registry answers:

- **Can I read this path?** — yes if it is inside *any* registered root (`READ` or `READ_WRITE`).
- **Can I write this path?** — yes only if it is inside a `READ_WRITE` root.

`Sandbox` (the per-tool read/write guard) and `PermissionService` (the write-approval gate) both consult the
registry; `RetrievalService` sources its index root from it. Being inside a writable root does **not**
auto-approve a write — it only lets the write proceed to the normal approval flow.

## Default-closed

Multi-root is governed by one flag:

```properties
# Off by default. With this false, the registry holds exactly one READ_WRITE root (the default),
# so behavior is byte-for-byte identical to the historical single-workspace harness.
agent.multi-root.enabled=false

# Optional static seeds, honored ONLY when enabled: CSV of path|access entries.
# e.g. agent.multi-root.roots=/srv/source|read, /srv/out|read_write
agent.multi-root.roots=
```

When `agent.multi-root.enabled=false`:

- the registry contains only the default `READ_WRITE` root;
- `canRead`/`canWrite` reduce to `isWithin(defaultRoot, …)`;
- any seeds in `agent.multi-root.roots` are ignored;
- runtime root additions are refused.

So enabling the feature is a deliberate, explicit act; nothing changes until you turn it on.

## Access levels

| Level | Read | Write | Typical use |
| --- | --- | --- | --- |
| `READ` | yes | no | the **source** project being ported or referenced |
| `READ_WRITE` | yes | yes | the default workspace, and a **destination** project you are scaffolding |

For the motivating task you would grant `READ` on the source (`…\github\mini`) and `READ_WRITE` on the
destination (`…\github\typescript-project`). The agent could then read the source and write the new project,
but could not modify the source.

## The approval flow (where this is going)

PR #1 establishes the registry and its enforcement. The intended end-to-end flow, built in the following
PRs, is:

1. The user asks for a cross-project task.
2. The agent calls an **approval-gated** `grant_workspace_root` tool naming the exact absolute path and
   access level. This tool is **always** gated — it is never auto-approved, even in `auto` mode.
3. On approval the root joins the session's registry and the grant is written to the audit log.
4. Reads/writes outside every granted root stay denied; a write into a granted `READ_WRITE` root still goes
   through the normal per-write approval.
5. A transactional `create_project` tool presents the new project as a plan (the full file manifest) under
   plan mode, and writes it all-or-nothing once approved.

Until those PRs land, the registry can be exercised through the `agent.multi-root.roots` config seeds (for
local experimentation) and is covered by the offline tests described in `TESTING.md`.

## Cross-platform paths

Roots are stored as absolute, normalized `Path` values, so confinement uses the running JVM's path rules:
on Windows, drive-letter paths like `C:\Users\larry\github\mini` and case-insensitive comparisons; on
POSIX, case-sensitive `/home/...` paths. The registry logic itself is platform-agnostic — it delegates to
`isWithin`, which uses `Path.resolve(...).normalize().startsWith(...)`. The offline tests run on POSIX;
Windows drive-letter semantics are exercised by the JVM's own path implementation rather than re-simulated.

## Where to read next

- [`ROADMAP.md`](../ROADMAP.md) — the full Track B plan and the ranked, individually-gated PRs.
- [`docs/WORKFLOW_WALKTHROUGH.md` §4](WORKFLOW_WALKTHROUGH.md#4-how-each-branch-is-proven-the-golden-trace-suite)
  — how each branch of the harness is proven by a golden-trace test.
- `TESTING.md` — the offline coverage for the registry and its wiring.
