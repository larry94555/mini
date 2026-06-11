package com.example.imini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the SSE wire contract that the "missing spaces" bug taught us: token-leading spaces and
 * newlines must survive encode -> wire -> decode. Runs against real Jackson under `mvn test`.
 */
class SseSerializationTest {

    @Test
    void leadingSpaceSurvivesEncodeDecode() {
        assertEquals(" on", Sse.decode(Sse.encode(" on")));
        assertEquals("Based", Sse.decode(Sse.encode("Based")));
        assertEquals("  two spaces", Sse.decode(Sse.encode("  two spaces")));
    }

    @Test
    void newlinesSurvive() {
        assertEquals("line1\nline2", Sse.decode(Sse.encode("line1\nline2")));
        assertEquals("\n", Sse.decode(Sse.encode("\n")));
    }

    @Test
    void encodedPayloadIsQuotedJsonImmuneToSseStrip() {
        String enc = Sse.encode(" leading");
        assertTrue(enc.startsWith("\""), "JSON string starts with a quote");
        assertFalse(enc.startsWith(" "), "so SSE's leading-space strip can't bite");
    }

    @Test
    void nullBecomesEmptyString() {
        assertEquals("", Sse.decode(Sse.encode(null)));
    }

    /**
     * The regression itself: word-piece tokens (each beginning with a space) streamed as separate
     * frames must reassemble with spaces intact -- not "Basedontheresults".
     */
    @Test
    void wordPieceTokensReassembleWithSpaces() {
        String[] tokens = {"Based", " on", " the", " search", " results"};
        StringBuilder wire = new StringBuilder();
        for (String t : tokens) wire.append(Sse.frame("token", t));

        StringBuilder assembled = new StringBuilder();
        for (String f : wire.toString().split("\n\n")) {
            if (f.isBlank()) continue;
            Sse.Event ev = Sse.parse(f);
            if ("token".equals(ev.name())) assembled.append(ev.data());
        }
        assertEquals("Based on the search results", assembled.toString());
    }

    @Test
    void frameCarriesEventNameAndMultilineData() {
        Sse.Event ev = Sse.parse(Sse.frame("answer", "para1\n\npara2"));
        assertEquals("answer", ev.name());
        assertEquals("para1\n\npara2", ev.data());
    }
}
