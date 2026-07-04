# Example 2 — a config-driven domain tool (`lookup_ticket`)

**Use case:** integrate an external system (an issue tracker, a wiki, an internal API) as a tool the
model can call, with its endpoint configurable per environment — the realistic shape of a "small user
application."

**What this demonstrates:**
- reading extension settings from `application.properties` via `ctx.property(key, default)`;
- marking the tool `untrusted = true` so output from an external system is fenced as *data*, not
  instructions (the same defense the web tools use);
- the pattern for a real HTTP-backed tool (the example serves canned data so it runs offline).

**The code:** [`TicketLookupExtension.java`](TicketLookupExtension.java).

## Install

1. Copy `TicketLookupExtension.java` into `src/main/java/com/example/imini/ext/`.
2. (Optional) add to `application.properties`: `ext.tickets.base-url=https://tracker.internal`.
3. Rebuild + run; check `GET /admin/extensions` shows `ticket-lookup` → `lookup_ticket`.

## Try it

```bat
ask.bat "Look up ticket PROJ-1 and tell me its status."
```

**Observe:** the model calls `lookup_ticket`, gets `PROJ-1 [open] Login button misaligned on mobile`,
and answers. Ask for `PROJ-9` and it reports "no ticket found." To make it real, replace the canned map
with an HTTP call to `baseUrl` (see the comment in the code) and inject an HTTP client via the bean's
constructor.
