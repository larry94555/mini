# Changelog

All notable changes to imini are recorded here. From this point on, entries are generated automatically by
release-please from [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `feat!:`
for breaking changes). Merging the "release please" PR bumps the `pom.xml` version, updates this file, and
tags `vX.Y.Z` -- which triggers the release and image-publish workflows.

## [Unreleased]

_A human-readable summary of recent work for readers coming to the repo cold; release-please will formalize
these into versioned entries from the Conventional Commits on the next release. Full prose history is in
[`docs/HISTORY.md`](docs/HISTORY.md)._

- **Golden-trace test suite.** The agent loop's control-flow branches are now each covered by a
  deterministic, model-free end-to-end test that drives the real `AgentEngine` via the shared
  `ScriptedAgent` fixture: the edit→verify→commit happy path with hooks (`GoldenTraceWorkflowTest`); plan
  mode, invalid-args recovery, and the duplicate-call guard (`RecoveryTraceTest`); capability scoping and
  per-tenant rate limiting (`CapabilityScopingTraceTest`); and subagent delegation plus failure propagation
  (`SubAgentHandoffTraceTest`, `SubAgentFailureTraceTest`). The fixture provides a scripted `LlamaClient`, a
  routing variant for scripting two agents on one engine, schema builders, and a `Harness` factory.
- **MCP streaming + multi-server.** The HTTP MCP transport consumes a multi-event and **unbounded**
  (keep-alive) `text/event-stream` incrementally, returning on the JSON-RPC response event; multi-server
  setups namespace tools `<server>_<tool>` and route `/mcp__<server>__<prompt>` per server
  (`McpLiveIntegrationTest`).
- **Access control.** Capability scoping (`CapabilityService`) denies + audits out-of-scope tools, and
  per-tenant tool rate limiting (`ToolRateLimiter`) returns `RATE_LIMITED`; both are exercised end to end.
- **CI.** Node is installed in CI so the stdio MCP integration tests run rather than self-skip.
- **Docs.** `WORKFLOW_WALKTHROUGH.md` §4 maps every branch to the test that proves it;
  `docs/TRACE_TOUR.md` narrates one session touching edit→commit, delegation, and MCP; `LEARNING_PATH.md`
  and `CONCEPT_MAP.md` cross-link the traces; `WHATS_NOT_INCLUDED.md` was corrected for drift.

## [0.2.0] - baseline

- Established baseline for automated changelog/versioning. Prior history is in
  [`docs/HISTORY.md`](docs/HISTORY.md) (moved out of `ROADMAP.md`).
