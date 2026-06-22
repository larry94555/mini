# Offline verification: faithful minimal JSON mapper

These two files are a **verification-scaffold artifact**, not part of the application:

- `jackson/com/fasterxml/jackson/databind/ObjectMapper.java`
- `jackson/com/fasterxml/jackson/databind/JsonNode.java`

They are a faithful-enough, dependency-free implementation of the `ObjectMapper`/`JsonNode` surface the code
uses (`readTree`, `readValue(Map.class)`, `writeValueAsString`, and the `JsonNode` accessors), with a real
recursive-descent JSON parser + serializer. Their purpose is to let the JSON-dependent tests (MCP discovery
over stdio/HTTP/SSE) run **offline**, in an environment that has no real Jackson jar, by putting these classes
on the test classpath instead of a no-op stub. With them present, `JsonProbe.realMapperAvailable()` returns
true and `McpManager` parses JSON-RPC for real.

## Do NOT move these into `src/`

The application already uses **real Jackson** (transitively via Spring Boot). Placing a
`com.fasterxml.jackson.*` implementation under `src/main` or `src/test` would shadow the real library and
break the Maven build. Keep them here, and add this directory to the classpath only for offline verification,
e.g.:

```sh
javac -d /tmp/realjson tools/offline-verify/jackson/com/fasterxml/jackson/databind/*.java
# then compile/run the tests with /tmp/realjson AHEAD of the no-op stub on the classpath
```

## Limits

Faithful-enough, not byte-identical to Jackson: no annotations, polymorphic binding, custom
(de)serializers, or exact number-type nuances. See `docs/OFFLINE_JSON.md`.
