package com.example.imini;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * The SSE wire contract for the streaming endpoints, in one testable place.
 *
 * Tokens are JSON-encoded before they go on the wire because SSE strips a leading space after
 * "data:" and treats newlines as frame structure -- so a raw word-piece token like " on" would arrive
 * as "on" and inter-word spaces would vanish (the bug this guards against). JSON-encoding puts the
 * token inside quotes, where spaces and newlines survive untouched; the browser JSON-decodes it back.
 *
 *   server : emits   event:&lt;name&gt;\ndata:&lt;encode(text)&gt;\n\n
 *   browser: splits on \n\n, reads the data: line, JSON-decodes it (see static/index.html parseSse)
 *
 * {@link #encode}/{@link #decode} are the two ends; {@link #frame}/{@link #parse} model the whole wire
 * round-trip so a unit test can prove tokens survive intact.
 */
public final class Sse {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Sse() {}

    /** One decoded event: its name (token/log/answer/...) and its already-decoded data. */
    public record Event(String name, String data) {}

    /** The text placed after "data:" -- a JSON-encoded string, immune to SSE's space/newline rules. */
    public static String encode(String data) {
        try {
            return MAPPER.writeValueAsString(data == null ? "" : data);
        } catch (Exception e) {
            return "\"\"";
        }
    }

    /** Inverse of {@link #encode}; mirrors the browser's JSON.parse of the data field. */
    public static String decode(String dataField) {
        try {
            return MAPPER.readValue(dataField == null || dataField.isEmpty() ? "\"\"" : dataField, String.class);
        } catch (Exception e) {
            return dataField == null ? "" : dataField;   // tolerate a non-JSON line rather than throwing
        }
    }

    /** The full wire frame for one event: {@code event:<name>\ndata:<encoded>\n\n}. */
    public static String frame(String name, String data) {
        return "event:" + name + "\ndata:" + encode(data) + "\n\n";
    }

    /**
     * Parse one frame the way the browser does: collect event:/data: lines and JSON-decode the data.
     * Crucially does NOT strip a leading space from the data line -- the payload is JSON, so the value
     * comes back exactly (this is what the spacing fix relies on).
     */
    public static Event parse(String frame) {
        String name = "message";
        List<String> dataLines = new ArrayList<>();
        for (String line : frame.split("\n")) {
            if (line.startsWith("event:")) {
                name = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                dataLines.add(line.substring(5));
            }
        }
        return new Event(name, decode(String.join("\n", dataLines)));
    }
}
