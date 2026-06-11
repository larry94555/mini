package com.example.imini;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the logging config wiring: plain by default, JSON under the "json" profile (no extra dep). */
class LoggingConfigTest {

    private String readLogback() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/logback-spring.xml")) {
            assertNotNull(in, "logback-spring.xml must be on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void definesPlainAndJsonConsoleAppenders() throws Exception {
        String xml = readLogback();
        assertTrue(xml.contains("ConsoleAppender"), "a console appender is configured");
        assertTrue(xml.contains("ch.qos.logback.classic.encoder.JsonEncoder"),
                "JSON output uses Logback's built-in JsonEncoder (no extra dependency)");
    }

    @Test
    void jsonProfileSelectsJsonAppenderOtherwisePlain() throws Exception {
        String xml = readLogback();
        assertTrue(xml.contains("<springProfile name=\"json\">"), "json profile present");
        assertTrue(xml.contains("<springProfile name=\"!json\">"), "default (non-json) profile present");
        assertTrue(xml.contains("ref=\"JSON\""), "json profile wires the JSON appender");
        assertTrue(xml.contains("ref=\"CONSOLE\""), "default wires the plain appender");
    }
}
