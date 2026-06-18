# Durable memory subsystem

imini keeps two kinds of memory:

- **Session memory** — the running message list for one conversation (persisted per session in the
  `sessions` table, so a conversation survives a restart).
- **Durable memory** — facts that carry *across* sessions and restarts, scoped per workspace and owner.
  This document describes the durable side end to end.

Durable memory is **workspace-local** (keyed by `owner@<workspaceId>`, where `workspaceId` is a short hash
of the working directory) and is **not** shared between users.

## The pipeline

```
                 ┌──────────────────────── a new session starts ───────────────────────┐
                 │                                                                       │
   durable  ──▶  SEED (relevance-ranked)  ──▶  conversation runs  ──▶  WRITE-BACK  ──▶  durable
   memory        pins always + top auto         FOLD big tool          CONSOLIDATE        memory
                 facts by relevance to          results; COMPACT       (quality guard)    (updated)
                 the first message              old history into                          │
                 (cap: memory-inject-max)       a [MEMORY] note        HYGIENE (decay)  ──┘
                                                                       prune unused facts

   any time:  the agent may call the recall_memory TOOL (two-stage: shortlist + model rerank)
   any time:  ANALYTICS record which facts get injected / recalled, to guide pinning & pruning
```

### 1. Seed (relevance-ranked injection)
When a session starts, `MemoryStore.relevantSeed(owner, firstMessage)` builds the `[MEMORY]` note it injects:
all **pinned** facts (always), plus the top **auto** facts ranked for relevance to the first message, capped
at `agent.memory-inject-max` and de-duplicated. Ranking uses `RetrievalService.rankTexts` — embedding cosine
when `retrieval.embeddings=true`, otherwise lexical term overlap (with a lexical fallback).

### 2. During a run (fold / compact)
Large tool results are **folded** (chunk → summarize → reduce) instead of dumped into context. When history
grows past `agent.compact-token-threshold`, older turns are **compacted** into the `[MEMORY]` note via the
summary model. Both emit trace events and per-run counts (see the admin run report).

### 3. Write-back (consolidate / quality guard)
After a run, the session's `[MEMORY]` note is written back to durable storage. If it has grown past
`agent.memory-max-chars`, `ContextManager.consolidateMemoryIfNeeded` asks the summary model to merge
duplicates and drop redundancy first (head+tail trim fallback), so the note stays tight.

### 4. Hygiene (decay)
`MemoryStore.hygiene` prunes auto facts that were **never injected or recalled** and were first observed more
than `agent.memory-decay-days` ago. Pinned facts are never pruned. It runs automatically after a run and on
demand (the *hygiene* button / `POST /memory/hygiene`).

### 5. Recall (two-stage tool)
The agent can call the **`recall_memory`** tool mid-conversation. Stage 1 shortlists candidates with the
cheap ranker (`agent.memory-recall-shortlist`); stage 2, when `agent.memory-rerank=true`, has the summary
model pick and order the most relevant (falling back to the shortlist on failure).

### 6. Analytics & curation
Each fact's `injected` / `recalled` counts are recorded (`memory_stats`) and shown in the *Memory analytics*
view (`GET /memory/analytics`). The *Promote to pin* suggestions are ordered by usage, so the facts that earn
their place are easy to pin. Pins carry **provenance** (source + timestamp). Durable memory (note + pins) is
included in the signed **workspace bundle** (Export / Import workspace).

## Configuration

| Property | Default | What it does |
|---|---|---|
| `agent.memory-inject-max` | 12 | Max durable facts seeded into a new session (pins always included) |
| `agent.memory-max-chars` | 4000 | Auto note is consolidated by the model above this size |
| `agent.memory-decay-days` | 30 | Hygiene prunes never-used facts older than this |
| `agent.memory-recall-k` | 6 | Default facts returned by `recall_memory` |
| `agent.memory-recall-shortlist` | 12 | Stage-1 shortlist size for recall |
| `agent.memory-rerank` | true | Stage-2 model rerank of recall |
| `retrieval.embeddings` | false | Rank by embedding cosine instead of lexical overlap |
| `retrieval.embed-cache-max` | 4096 | Bounded LRU + `embed_cache` rows for fact embeddings |

## Endpoints

`GET /memory/durable` (note, pins+provenance, effective, workspace) · `POST /memory/durable` (edit note) ·
`POST /memory/durable/pin` · `/unpin` · `/clear` · `POST /memory/hygiene` · `GET /memory/analytics`.

## Storage (SQLite tables)

`memory` (auto note per scope) · `memory_pins` (pinned facts + provenance) · `memory_stats`
(injected/recalled/first_seen per fact) · `embed_cache` (cached fact embeddings).
