package com.example.imini;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.spi.AppenderAttachable;
import ch.qos.logback.core.spi.AppenderAttachableImpl;

import java.util.Iterator;

/**
 * A Logback appender that redacts secret- and PII-shaped substrings from the formatted log message before
 * forwarding the event to nested delegate appenders (typically the JSON console appender that uses
 * {@code ch.qos.logback.classic.encoder.JsonEncoder}). This keeps the built-in {@code JsonEncoder} in the
 * config -- satisfying {@code LoggingConfigTest} -- while ensuring the structured JSON log output is scrubbed.
 *
 * <p>It implements {@link AppenderAttachable} so Logback's Joran configurator wires nested
 * {@code <appender-ref>} elements into it the same way the root logger accepts appender references. When a
 * message is altered by redaction, a thin {@link ILoggingEvent} wrapper carries the scrubbed text and
 * delegates every other field to the original event; when nothing changes, the original event is forwarded
 * unwrapped (zero copy).
 *
 * <p>Like the other Logback classes in imini, this compiles against Logback on the Spring Boot classpath
 * and is exercised only by the real logging stack; the masking logic it delegates to is unit-tested.
 */
public class MessageRedactingAppender extends AppenderBase<ILoggingEvent>
        implements AppenderAttachable<ILoggingEvent> {

    private final AppenderAttachableImpl<ILoggingEvent> aai = new AppenderAttachableImpl<>();

    @Override
    protected void append(ILoggingEvent event) {
        String original = event.getFormattedMessage();
        String scrubbed = Redact.scrubPii(original);
        ILoggingEvent forwarded = (scrubbed == null || scrubbed.equals(original))
                ? event
                : new ScrubbedEvent(event, scrubbed);
        aai.appendLoopOnAppenders(forwarded);
    }

    @Override
    public void start() {
        for (Iterator<Appender<ILoggingEvent>> it = aai.iteratorForAppenders(); it.hasNext(); ) {
            Appender<ILoggingEvent> a = it.next();
            if (!a.isStarted()) a.start();
        }
        super.start();
    }

    @Override
    public void stop() {
        aai.detachAndStopAllAppenders();
        super.stop();
    }

    // --- AppenderAttachable: delegate to AppenderAttachableImpl ----------------

    @Override public void addAppender(Appender<ILoggingEvent> newAppender) { aai.addAppender(newAppender); }
    @Override public Iterator<Appender<ILoggingEvent>> iteratorForAppenders() { return aai.iteratorForAppenders(); }
    @Override public Appender<ILoggingEvent> getAppender(String name) { return aai.getAppender(name); }
    @Override public boolean isAttached(Appender<ILoggingEvent> appender) { return aai.isAttached(appender); }
    @Override public void detachAndStopAllAppenders() { aai.detachAndStopAllAppenders(); }
    @Override public boolean detachAppender(Appender<ILoggingEvent> appender) { return aai.detachAppender(appender); }
    @Override public boolean detachAppender(String name) { return aai.detachAppender(name); }

    /** Minimal ILoggingEvent that returns a scrubbed message; every other field delegates to the original. */
    private static final class ScrubbedEvent implements ILoggingEvent {
        private final ILoggingEvent d;
        private final String message;

        ScrubbedEvent(ILoggingEvent delegate, String message) {
            this.d = delegate;
            this.message = message;
        }

        @Override public String getFormattedMessage() { return message; }
        @Override public String getMessage() { return message; }
        @Override public Object[] getArgumentArray() { return null; }
        @Override public String getThreadName() { return d.getThreadName(); }
        @Override public ch.qos.logback.classic.Level getLevel() { return d.getLevel(); }
        @Override public String getLoggerName() { return d.getLoggerName(); }
        @Override public ch.qos.logback.classic.spi.LoggerContextVO getLoggerContextVO() { return d.getLoggerContextVO(); }
        @Override public ch.qos.logback.classic.spi.IThrowableProxy getThrowableProxy() { return d.getThrowableProxy(); }
        @Override public StackTraceElement[] getCallerData() { return d.getCallerData(); }
        @Override public boolean hasCallerData() { return d.hasCallerData(); }
        @Override public org.slf4j.Marker getMarker() { return d.getMarker(); }
        @Override public java.util.List<org.slf4j.Marker> getMarkerList() { return d.getMarkerList(); }
        @Override public java.util.Map<String, String> getMDCPropertyMap() { return d.getMDCPropertyMap(); }
        @Override public java.util.Map<String, String> getMdc() { return d.getMDCPropertyMap(); }
        @Override public long getTimeStamp() { return d.getTimeStamp(); }
        @Override public int getNanoseconds() { return d.getNanoseconds(); }
        @Override public long getSequenceNumber() { return d.getSequenceNumber(); }
        @Override public java.util.List<ch.qos.logback.classic.spi.KeyValuePair> getKeyValuePairs() { return d.getKeyValuePairs(); }
        @Override public void prepareForDeferredProcessing() { d.prepareForDeferredProcessing(); }
    }
}
