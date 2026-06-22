# Offline JSON support (verification scaffold)

This note documents how imini's JSON-dependent tests are exercised **offline**, and the limits of that
support. It matters because several tests parse JSON-RPC (MCP discovery over stdio/HTTP/SSE) through Jackson's
`ObjectMapper`, and how they behave depends on whether a real JSON mapper is on the classpath.

## The repo uses real Jackson

In a normal build the repo depends on **real Jackson** (`jackson-databind`), pulled in transitively via
Spring Boot — there is no JSON stub in `src/`. Under Maven/CI the MCP discovery tests run for real and assert
end-to-end behavior. Nothing here changes that.

## The offline gap, and the gate

In a constrained *offline* verification environment (no Maven Central, so no Jackson jar), the only way to
compile and run the code is against a small set of compile stubs. A **no-op** `ObjectMapper` stub
(`readTree`/`readValue` return empty/null) lets the code compile but cannot actually parse JSON, so MCP
discovery yields nothing. Rather than fail misleadingly, the JSON-dependent tests gate on a runtime probe:

- `JsonProbe.realMapperAvailable()` round-trips a known JSON string through `ObjectMapper.readTree` and checks
  the parsed value — false under a no-op mapper, true under a real one.
- The tests route through `IntegrationGate.proceed("json", …)`, so they **self-skip** when no real mapper is
  present and **run** (and, with `IMINI_REQUIRE_JSON=1`, are required) when one is.

## What the verification scaffold provides

To close the offline gap *during verification*, the scaffold can supply a **faithful minimal JSON mapper** (a
real recursive-descent parser + serializer implementing the `ObjectMapper`/`JsonNode` surface the code uses:
`readTree`, `readValue(Map.class)`, `writeValueAsString`, and the `JsonNode` accessors `get`/`path`/`asText`/
`asInt`/`asLong`/`asBoolean`/`has`/`isArray`/`isObject`/`isNull`/`isMissingNode`/`size`/`fields`/`elements`).
With it in place, `JsonProbe.realMapperAvailable()` returns true and the previously-skipped MCP discovery
tests (stdio, HTTP, streaming SSE, keep-alive SSE, and the golden MCP slash-command trace) execute and pass
offline, end to end.

### Limits

- The minimal mapper is **faithful-enough, not byte-identical** to Jackson: it covers the used surface, not
  annotations, polymorphic binding, custom (de)serializers, or exact number-type nuances.
- It is a **verification-scaffold artifact, not part of the repo**: it is deliberately **not** committed to
  `src/`, because adding a `com.fasterxml.jackson.*` implementation there would shadow the real Jackson the
  application depends on and break the Maven build. The repo continues to use real Jackson via Spring Boot.

## Running the JSON-dependent tests

- Normal (Maven/CI): `./mvnw -Dtest=McpLiveIntegrationTest test` — real Jackson is present, so they run.
- Enforce in CI: the opt-in `Integration tests` workflow sets `IMINI_REQUIRE_JSON=1`; if no real mapper is
  present the gate hard-fails, and `scripts/integration-coverage.sh` flags a required-but-skipped `json`.
- Offline without a real mapper: the tests self-skip cleanly (`[integration] … (json) skipped`).

See also TESTING.md (cases 623-626), CONTRIBUTING.md ("Conventions"), and `docs/MULTI_ROOT.md`
("CI enforcement").
