# Example 4 — a custom slash command (`/shout`)

**Use case:** a reusable prompt shortcut for your team, shipped in code alongside your other extension
pieces rather than as a loose `commands/*.md` file.

**What this demonstrates:** an `Extension` contributing a `Command` (name, description, template). The
template's `$ARGS` is replaced by whatever follows the command; the expansion is what the model sees.

**The code:** [`ShoutCommandExtension.java`](ShoutCommandExtension.java).

## Install

1. Copy `ShoutCommandExtension.java` into `src/main/java/com/example/imini/ext/`.
2. Rebuild + run. `/help` lists it under "From extensions"; `GET /admin/extensions` shows
   `shout-command` → `commands: ["shout"]`.

## Try it

```bat
ask.bat "/shout we shipped the extension api"
```

**Observe:** the command expands to the template with `$ARGS = "we shipped the extension api"`, so the
model replies in emphatic uppercase. A disk `commands/shout.md` would take precedence.
