package com.example.imini;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates key configuration at startup and fails fast on contradictory settings, so a misconfigured
 * deployment surfaces immediately instead of misbehaving at runtime. Non-fatal concerns are logged as
 * warnings. The decision logic ({@link #validate}) is pure + static for testing; this component only wires
 * the injected values in and acts on the result.
 */
@Component
public class ConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(ConfigValidator.class);

    @Value("${llama.max-retries:2}") private int maxRetries;
    @Value("${llama.retry-backoff-ms:400}") private long retryBackoffMs;
    @Value("${persistence.enabled:true}") private boolean persistenceEnabled;
    @Value("${persistence.db-path:.imini/imini.db}") private String dbPath;
    @Value("${agent.run-history.persist-max:500}") private int persistMax;
    @Value("${auth.enabled:false}") private boolean authEnabled;
    @Value("${auth.principals:}") private String principals;
    @Value("${bundle.signing-secret:}") private String signingSecret;

    public static final String FATAL = "FATAL: ";
    public static final String WARN = "WARN: ";

    @PostConstruct
    public void check() {
        int principalCount = countPrincipals(principals);
        List<String> issues = validate(maxRetries, retryBackoffMs, persistenceEnabled, dbPath,
                persistMax, authEnabled, principalCount);
        List<String> fatal = new ArrayList<>();
        for (String i : issues) {
            if (i.startsWith(FATAL)) {
                fatal.add(i.substring(FATAL.length()));
                log.error("[config] " + i.substring(FATAL.length()));
            } else {
                log.warn("[config] " + i.substring(WARN.length()));
            }
        }
        // confirm-without-leaking what secrets are configured
        log.info("[config] auth.enabled=" + authEnabled + ", principals=" + principalCount
                + ", signingSecret=" + (signingSecret == null || signingSecret.isBlank()
                        ? "(none)" : Redact.mask(signingSecret)));
        if (!fatal.isEmpty()) {
            throw new IllegalStateException("invalid configuration: " + String.join("; ", fatal));
        }
    }

    static int countPrincipals(String principals) {
        if (principals == null || principals.isBlank()) return 0;
        int n = 0;
        for (String part : principals.split(",")) if (!part.isBlank()) n++;
        return n;
    }

    /** Pure validation: returns issue strings prefixed with FATAL/WARN. Empty list means all good. */
    static List<String> validate(int maxRetries, long retryBackoffMs, boolean persistenceEnabled,
                                 String dbPath, int persistMax, boolean authEnabled, int principalCount) {
        List<String> out = new ArrayList<>();
        if (maxRetries < 0) out.add(FATAL + "llama.max-retries must be >= 0 (got " + maxRetries + ")");
        if (retryBackoffMs < 0) out.add(FATAL + "llama.retry-backoff-ms must be >= 0 (got " + retryBackoffMs + ")");
        if (persistenceEnabled && (dbPath == null || dbPath.isBlank())) {
            out.add(FATAL + "persistence.enabled=true but persistence.db-path is blank");
        }
        if (persistMax < 1) out.add(WARN + "agent.run-history.persist-max < 1 disables run-history persistence");
        if (authEnabled && principalCount == 0) {
            out.add(WARN + "auth.enabled=true but no principals/keys configured -- all authed requests will be rejected");
        }
        if (!authEnabled) {
            out.add(WARN + "auth.enabled=false -- the API (including admin endpoints) is unauthenticated");
        }
        return out;
    }
}
