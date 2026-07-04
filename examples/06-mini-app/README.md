# Example 6 — a whole small app in one extension (`notes-app`)

**Use case:** package a cohesive feature — tools, a specialist subagent, a shortcut command, and an
observer — as a single installable extension. This is the "small user application" the extension API is
for.

**What this demonstrates:** one `Extension` that implements all four contribution methods at once:
`note_add` + `note_list` tools, a `notes` subagent scoped to them, a `/notes` slash command, and an
`onEvent` observer counting additions. Notes live in memory to keep it self-contained.

**The code:** [`NotesAppExtension.java`](NotesAppExtension.java).

## Install

1. Copy `NotesAppExtension.java` into `src/main/java/com/example/imini/ext/`.
2. Rebuild + run. `GET /admin/extensions` shows `notes-app` with `tools: [note_add, note_list]`,
   `agents: [notes]`, `commands: [notes]`.

## Try it

```bat
chat.bat demo "Remember that the release is on Friday, and that Priya owns the changelog."
chat.bat demo "/notes"
```

**Observe:** the model uses `note_add` twice, then `/notes` expands to a prompt that calls `note_list`
and summarizes; the app log shows the observer's "N note(s) added" line. One file installed four
capabilities — turn them all off at once with `extensions.enabled=false`.
