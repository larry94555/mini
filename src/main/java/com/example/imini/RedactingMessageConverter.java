package com.example.imini;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * A Logback pattern converter that masks secret- and PII-shaped substrings in every log message before it
 * is written. Wired in {@code logback-spring.xml} as the conversion word {@code rmsg} (used in place of the
 * usual {@code %msg}), so the redaction applies uniformly to all console output without each call site
 * having to remember to scrub.
 *
 * <p>It delegates the actual masking to {@link Redact#scrubPii(String)} (bearer tokens, {@code key=value}
 * secrets, {@code sk-}/AWS/JWT tokens, emails). Redaction here is always on for the console pattern; to turn
 * it off, switch the pattern back to {@code %msg} in {@code logback-spring.xml}. Trace-attribute redaction is
 * governed separately by {@code redaction.enabled}.
 *
 * <p>This class compiles against Logback, which Spring Boot already puts on the classpath; it is not part
 * of the dependency-free core and is only exercised by the real logging stack.
 */
public class RedactingMessageConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return Redact.scrubPii(event.getFormattedMessage());
    }
}
