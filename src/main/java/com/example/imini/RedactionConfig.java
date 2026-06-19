package com.example.imini;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Loads operator-supplied redaction patterns from configuration and registers them with {@link Redact} at
 * startup, so a deployment can mask its own secret shapes (e.g. an internal employee-ID or ticket format)
 * without code changes. The built-in patterns (bearer tokens, {@code key=value} secrets, {@code sk-}/AWS/JWT
 * tokens, emails) always apply; these run after them.
 *
 * <p>Configure via {@code redaction.patterns}: entries separated by {@code ;;}, each a regex optionally
 * followed by {@code =>replacement} (default {@code ****}). For example:
 * <pre>redaction.patterns=EMP-\\d{6}=&gt;EMP-****;;(?i)\\bpassword\\b\\s*\\S+=&gt;password ****</pre>
 * Invalid regexes are skipped (logged) rather than failing startup. {@code Redact} is a static utility used
 * by the tracer and both log encoders, so registering here means every redaction path picks up the rules.
 */
@Component
public class RedactionConfig {

    private static final Logger log = LoggerFactory.getLogger(RedactionConfig.class);

    @Value("${redaction.patterns:}") private String patternsCfg;

    @jakarta.annotation.PostConstruct
    public void init() {
        List<Redact.Rule> rules = Redact.parseRules(patternsCfg);
        Redact.setExtraRules(rules);
        if (!rules.isEmpty()) {
            log.info("[redaction] registered " + rules.size() + " custom pattern(s)");
        }
    }
}
