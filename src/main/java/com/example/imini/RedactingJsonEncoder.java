package com.example.imini;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.encoder.EncoderBase;

import java.nio.charset.StandardCharsets;

/**
 * A Logback encoder that masks secret- and PII-shaped substrings in the structured ("json" Spring profile)
 * log output, so JSON logs get the same redaction as the console profile's {@code %rmsg} converter.
 *
 * <p>Rather than re-implement JSON encoding (or a fragile {@code ILoggingEvent} wrapper), this delegates to a
 * real encoder -- configured in {@code logback-spring.xml} as a nested
 * {@code <delegate class="ch.qos.logback.classic.encoder.JsonEncoder"/>} -- and scrubs the bytes it produces.
 * That keeps Logback's built-in {@code JsonEncoder} responsible for the line shape and field set (no extra
 * dependency), while guaranteeing the serialized output is redacted. The masking is the unit-tested
 * {@link Redact#scrubPii(String)}.
 *
 * <p>It touches only the small, stable {@link Encoder} surface (encode / headerBytes / footerBytes), which
 * keeps it robust across Logback versions. It compiles against Logback on the Spring Boot classpath and is
 * exercised by the real logging stack; the pure byte-scrubbing helper is unit-tested.
 */
public class RedactingJsonEncoder extends EncoderBase<ILoggingEvent> {

    /** The real encoder doing the JSON serialization (set by Logback from the nested {@code <delegate>}). */
    private Encoder<ILoggingEvent> delegate;

    public void setDelegate(Encoder<ILoggingEvent> delegate) {
        this.delegate = delegate;
    }

    @Override
    public byte[] headerBytes() {
        return delegate != null ? delegate.headerBytes() : new byte[0];
    }

    @Override
    public byte[] footerBytes() {
        return delegate != null ? delegate.footerBytes() : new byte[0];
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        if (delegate == null) return new byte[0];
        return redact(delegate.encode(event));
    }

    /** Pure: scrub secret/PII-shaped substrings from an encoded log line's bytes (UTF-8 in, UTF-8 out). */
    static byte[] redact(byte[] raw) {
        if (raw == null || raw.length == 0) return raw;
        String scrubbed = Redact.scrubPii(new String(raw, StandardCharsets.UTF_8));
        return scrubbed.getBytes(StandardCharsets.UTF_8);
    }
}
