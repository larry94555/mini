# Multi-root project work (Track B)

By default `imini` works inside a single workspace: one root directory (the current directory, or
`agent.workspace-root`) that bounds every file read and write. That is enough for "edit and run code in this
one repo," but not for real cross-project tasks such as *"create a TypeScript project at path B that ports
the code at path A."* Track B adds a **registry of workspace roots** so the harness can, with explicit user
approval, read a second project and scaffold a new one — without ever weakening the single-root default.

This page describes the registry model and access levels (**PR #1**) and the approval-gated grant/revoke
tools (**PR #2**). The transactional project scaffold is a later, separately-gated PR; see `ROADMAP.md`.

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

## The grant tools (implemented in PR #2)

Two tools let the agent request additional roots, both **mutating** and **always gated** — they are listed in
`PermissionService.ALWAYS_CONFIRM`, so they are **never auto-approved**: even in `auto` mode (or with the
global `autoApprove` flag set) they route to the human approval path; in `plan` mode they record as a plan.
A `deny` rule can still block them. Granting a new root is a trust decision, so it always asks.

- **`grant_workspace_root`** — args `path` (absolute) and `access` (`read` or `read_write`, default `read`).
  On approval it adds the root to the registry and writes the grant to the audit log. A relative path is
  rejected; while multi-root is disabled it reports that and changes nothing.
- **`revoke_workspace_root`** — args `path`. Removes a previously granted root (the default root cannot be
  revoked). Also audited.

Administrators can inspect the live registry read-only at **`GET /admin/roots`**, which returns the enabled
flag, the default root, and the list of `{id, path, access}` entries.

### Worked example — the TypeScript port

> "Create a project at `C:\Users\larry\github\typescript-project` that is the TypeScript equivalent of the
> code at `C:\Users\larry\github\mini`."

With `agent.multi-root.enabled=true`, the safe flow is:

1. The agent calls `grant_workspace_root {path: "C:\\Users\\larry\\github\\mini", access: "read"}`. Because
   this is an always-confirm tool, **you are prompted to approve** regardless of permission mode. You
   approve; the source is now readable (not writable).
2. The agent calls `grant_workspace_root {path: "C:\\Users\\larry\\github\\typescript-project", access:
   "read_write"}`. **You approve again.** The destination is now readable and writable.
3. The agent reads the source project (the read root) and produces the TypeScript equivalent. Each write
   lands under the destination's `read_write` root — and still goes through the **normal** per-write approval
   (being inside a granted root is not a blanket auto-approve).
4. Any attempt to write back into the source `read` root is denied; any path outside both granted roots is
   denied.
5. `GET /admin/roots` shows both grants; the audit log records each `grant_workspace_root` decision.

Until the transactional `create_project` scaffold lands (a later PR), step 3 is ordinary `write_file` calls,
each individually approved. The grant tools and registry are what make those writes *possible and bounded*.

## Creating a whole project at once (`create_project`, PR #3)

`create_project` writes an entire project from a **manifest** in one approval-gated, transactional step. It
takes:

- `root` — the absolute destination directory (must be inside a granted `read_write` root);
- `files` — the manifest: a list of `{path, content}` entries, where `path` is relative to `root` (no
  leading slash, no `..`);
- `plan_only` (optional) — if true, write nothing and return the planned tree + per-file byte counts;
- `overwrite` (optional) — if true, allow replacing files that already exist.

Example manifest:

```json
{
  "root": "C:\\Users\\larry\\github\\typescript-project",
  "files": [
    { "path": "package.json", "content": "{ \"name\": \"typescript-project\" }" },
    { "path": "tsconfig.json", "content": "{ \"compilerOptions\": { \"strict\": true } }" },
    { "path": "src/index.ts", "content": "export const greet = () => \"hi\";" }
  ]
}
```

**Safety properties:**

- Every target resolves under `root`; a `path` that escapes via `..` or an absolute value is rejected.
- Every target must be inside a granted `read_write` root (`WorkspaceRoots.canWrite`); otherwise the whole
  call is denied and nothing is written.
- It is **mutating** and goes through the normal approval flow. The approval payload is a **summary** — root,
  file count, total bytes, and the tree — not every file's full content.
- Writes are **transactional**: all files are staged into a temp directory first, then moved into place; a
  failure mid-move rolls back the files already written. Existing files are refused unless `overwrite=true`.

**Plan-first.** Call `create_project` with `plan_only=true` to preview the tree and byte counts, review it,
then call again without `plan_only` to write. (In the harness's `plan` permission mode the engine also
records the call without executing it; `plan_only` is the explicit, mode-independent preview.)

### End-to-end: the TypeScript port

1. `grant_workspace_root {path: "…\\mini", access: "read"}` → you approve. Source readable.
2. `grant_workspace_root {path: "…\\typescript-project", access: "read_write"}` → you approve. Destination
   writable.
3. The agent reads the source and assembles a `create_project` manifest for the destination.
4. `create_project {root: "…\\typescript-project", files: [...], plan_only: true}` → you review the tree.
5. `create_project {root: "…\\typescript-project", files: [...]}` → approved → the project is written
   transactionally. Any path outside the granted `read_write` root is refused; the source `read` root is
   never written to.

## Cross-platform paths

Roots are stored as absolute, normalized `Path` values, so confinement uses the running JVM's path rules:
on Windows, drive-letter paths like `C:\Users\larry\github\mini` and case-insensitive comparisons; on
POSIX, case-sensitive `/home/...` paths. The registry logic itself is platform-agnostic — it delegates to
`isWithin`, which uses `Path.resolve(...).normalize().startsWith(...)`. The offline tests run on POSIX;
Windows drive-letter semantics are exercised by the JVM's own path implementation rather than re-simulated.

## Security model

Track B's guarantees, in one place:

- **Default-closed.** Multi-root is off unless `agent.multi-root.enabled=true`. While off, the registry holds
  exactly the one default `read_write` root and every check reduces to the historical single-workspace
  behavior.
- **Per-path, per-access.** Each additional root is granted at a specific absolute path with an explicit
  access level (`read` or `read_write`). A `read` root permits reads but denies writes.
- **Per-session.** Runtime grants are scoped to the session that approved them; a root granted in one run is
  invisible to other sessions, so one run cannot widen another's access. The default root is the only shared
  root, and it can never be removed or downgraded.
- **Approval at the boundary.** The grant/revoke tools are always-confirm — never auto-approved, even in
  `auto` mode (in `plan` mode they record). `create_project` goes through the normal approval with a
  *summary* payload (root, file count, total bytes, tree), not raw file content. A write inside a granted
  root is not a blanket auto-approve — it still goes through the normal per-write approval.
- **Confined & transactional.** Every write must resolve inside a granted `read_write` root; path escapes
  (`..`/absolute) are rejected. `create_project` writes all-or-nothing with rollback and refuses to
  overwrite existing files unless asked.
- **Audited.** Every grant, revoke, and `create_project` is written to the audit log.

See [`docs/PORT_WALKTHROUGH.md`](PORT_WALKTHROUGH.md) for the end-to-end task and the golden trace that
proves it.

## Persistence and lifecycle

Runtime grants are **durable**: each `grant_workspace_root` is written to a `workspace_grants` table in the
existing SQLite database (keyed by session id + path, storing the access level and a granted-at timestamp),
and `revoke_workspace_root` deletes that row. On startup the registry **reloads** every non-expired grant,
so an approved root survives a restart without re-approval.

- **Default root is never persisted.** It is global and always present, reconstructed from configuration on
  every boot.
- **Optional TTL.** Set `agent.multi-root.grant-ttl` (seconds; `0` = never expire). When positive, a grant
  older than the TTL is **ignored** — not reloaded on startup *and* not honored at access time — and pruned
  from the store (on reload and lazily as it is encountered).
- **Best-effort.** Persistence never blocks an approval: if the database is unavailable the grant still
  takes effect in memory for the current process, and a DB write failure is logged, not surfaced. The
  trade-off is that an in-memory-only grant simply won't survive a restart.
- **Disabled mode is untouched.** With `agent.multi-root.enabled=false` the registry reads and writes the
  table **not at all** — behavior is byte-for-byte identical to the single-workspace harness.

`GET /admin/roots` reports each grant's `granted_at` and `remaining_ttl_ms` (null when unlimited).
`GET /admin/roots/audit` lists the grant/revoke (and `create_project`) history with timestamps and outcomes.

### How durability is verified

Two complementary layers, so the logic is covered offline *and* the real SQL is exercised:

- **Offline unit tests** (`GrantPersistenceTest`) drive the persist/reload/TTL/revoke logic through an
  in-memory `GrantStore` double and a settable clock. They run everywhere, including builds with no
  sqlite-jdbc driver, and pin the behavior deterministically.
- **A real-database integration test** (`GrantPersistenceIntegrationTest`) boots an actual SQLite database
  on a tempfile, runs the migrations, and drives a real grant → reload → revoke → TTL-prune cycle, asserting
  the rows persist, reload, disappear on revoke, and are pruned past the TTL via real SQL. It **self-skips**
  when persistence can't initialize (no driver), so it never breaks an offline build, and runs for real in
  CI. The opt-in `Integration tests` workflow (`.github/workflows/integration.yml`) provisions sqlite-jdbc
  so it executes on demand.

## Where to read next

- [`ROADMAP.md`](../ROADMAP.md) — the full Track B plan and the ranked, individually-gated PRs.
- [`docs/WORKFLOW_WALKTHROUGH.md` §4](WORKFLOW_WALKTHROUGH.md#4-how-each-branch-is-proven-the-golden-trace-suite)
  — how each branch of the harness is proven by a golden-trace test.
- `TESTING.md` — the offline coverage for the registry and its wiring.
