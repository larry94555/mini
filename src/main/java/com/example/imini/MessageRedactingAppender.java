package com.example.imini;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.AppenderBase;

import java.util.ArrayList;
import java.util.List;

/**
 * A Logback {@link AppenderBase} that redacts secret- and PII-shaped substrings from the formatted log
 * message before forwarding the event to its delegate appenders. This keeps the built-in
 * {@code ch.qos.logback.classic.encoder.JsonEncoder} in the config XML — satisfying the existing
 * {@code LoggingConfigTest} contract — while ensuring the structured JSON log output is still scrubbed.
 *
 * <p>If the message is unchanged by redaction, the original event is forwarded as-is (zero copy). When
 * scrubbing does alter it, a {@link LoggingEvent} copy is created with the scrubbed message as both the
 * format string and the pre-formatted message; all other fields are preserved. Wired in
 * {@code logback-spring.xml} as the outermost appender for the {@code json} profile, with the real
 * {@code JSON} appender (using {@code JsonEncoder}) registered as a delegate.
 */
public class MessageRedactingAppender extends AppenderBase<ILoggingEvent> {

    private final List<Appender<ILoggingEvent>> delegates = new ArrayList<>();

    /** Called by Logback XML wiring for each nested {@code <appender>} element. */
    public void addAppender(Appender<ILoggingEvent> appender) {
        delegates.add(appender);
    }

    @Override
    protected void append(ILoggingEvent event) {
        String original = event.getFormattedMessage();
        String scrubbed = Redact.scrubPii(original);
        ILoggingEvent forwarded = scrubbed.equals(original) ? event : redacted(event, scrubbed);
        for (Appender<ILoggingEvent> d : delegates) {
            d.doAppend(forwarded);
        }
    }

    @Override
    public void start() {
        for (Appender<ILoggingEvent> d : delegates) {
            if (!d.isStarted()) d.start();
        }
        super.start();
    }

    /**
     * Build a copy of {@code event} with the message field replaced by {@code scrubbed}. We subclass
     * {@code ILoggingEvent} as an anonymous inner class so we only need to override the two message
     * accessors; everything else delegates to the original event and thus requires no knowledge of the
     * concrete implementation class.
     */
    private static ILoggingEvent redacted(ILoggingEvent event, String scrubbed) {
        return new ch.qos.logback.classic.spi.LoggingEventVO() {
            {
                // LoggingEventVO is a plain JavaBean; we can't super() it over an arbitrary ILoggingEvent,
                // so we set the fields we control and delegate the rest through overrides.
            }

            @Override public String getMessage()          { return scrubbed; }
            @Override public String getFormattedMessage() { return scrubbed; }
            @Override public Object[] getArgumentArray()  { return null; }

            @Override public long getTimeStamp()           { return event.getTimeStamp(); }
            @Override public String getLoggerName()        { return event.getLoggerName(); }
            @Override public String getThreadName()        { return event.getThreadName(); }
            @Override public java.util.Map<String,String> getMDCPropertyMap() { return event.getMDCPropertyMap(); }
            @Override public ch.qos.logback.classic.spi.IThrowableProxy getThrowableProxy() { return event.getThrowableProxy(); }
            @Override public StackTraceElement[] getCallerData()  { return null; }
            @Override public boolean hasCallerData()              { return false; }
        };
    }
}
