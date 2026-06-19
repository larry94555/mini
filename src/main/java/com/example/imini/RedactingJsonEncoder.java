package com.example.imini;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.EncoderBase;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * A self-contained JSON log encoder that masks secret- and PII-shaped substrings in the message before
 * emitting it, so the structured ("json" Spring profile) logs get the same redaction as the console
 * profile's {@code %rmsg} converter. It emits one JSON object per line with the timestamp, level, logger,
 * thread, MDC properties, and the {@link Redact#scrubPii(String) scrubbed} message.
 *
 * <p>We write the JSON ourselves (a tiny, fixed set of fields) rather than wrapping Logback's built-in
 * {@code JsonEncoder}, because that keeps the redaction guarantee explicit and the line shape easy to
 * verify — the field assembly is the pure, unit-tested {@link #toJson}. Wired in {@code logback-spring.xml}
 * as the encoder for the JSON appender.
 *
 * <p>Like {@link RedactingMessageConverter}, this compiles against Logback (already on the Spring Boot
 * classpath) and is exercised only by the real logging stack; the masking logic it calls is covered by
 * unit tests.
 */
public class RedactingJsonEncoder extends EncoderBase<ILoggingEvent> {

    @Override
    public byte[] headerBytes() { return new byte[0]; }

    @Override
    public byte[] footerBytes() { return new byte[0]; }

    @Override
    public byte[] encode(ILoggingEvent event) {
        String line = toJson(
                event.getTimeStamp(),
                String.valueOf(event.getLevel()),
                event.getLoggerName(),
                event.getThreadName(),
                event.getMDCPropertyMap(),
                event.getFormattedMessage());
        return (line + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Pure: build one JSON log line. The message is scrubbed of secrets/PII; every value is JSON-escaped.
     * MDC entries are emitted in sorted order for stable, testable output.
     */
    static String toJson(long ts, String level, String logger, String thread,
                         Map<String, String> mdc, String message) {
        StringBuilder sb = new StringBuilder(128);
        sb.append('{');
        field(sb, "ts", String.valueOf(ts), false);
        field(sb, "level", level, true);
        field(sb, "logger", logger, true);
        field(sb, "thread", thread, true);
        field(sb, "message", Redact.scrubPii(message), true);
        if (mdc != null && !mdc.isEmpty()) {
            for (Map.Entry<String, String> e : new TreeMap<>(mdc).entrySet()) {
                field(sb, "mdc." + e.getKey(), e.getValue(), true);
            }
        }
        sb.append('}');
        return sb.toString();
    }

    /** Append a "key":"value" pair (value JSON-escaped), with a leading comma unless this is the first. */
    private static void field(StringBuilder sb, String key, String value, boolean comma) {
        if (comma) sb.append(',');
        sb.append('"').append(esc(key)).append("\":\"").append(esc(value == null ? "" : value)).append('"');
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
