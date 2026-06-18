package com.example.imini;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Retries a transient operation with exponential backoff plus jitter. Only IOExceptions (network errors, and
 * 5xx responses that callers wrap as IOException) are retried; any other exception propagates immediately, so
 * client errors (4xx, bad input) are not pointlessly retried. Jitter spreads retries so concurrent callers
 * don't reconnect in lockstep (thundering herd) when a llama-server restarts.
 */
public final class Retry {

    private Retry() {}

    public interface Op<T> {
        T call() throws Exception;
    }

    /** Cap a single backoff so a large attempt index can't sleep absurdly long (or overflow the shift). */
    private static final long MAX_BACKOFF_MS = 30_000L;

    /**
     * Backoff for a given attempt: {@code base * 2^attempt}, capped, plus up to that much again as jitter.
     * Pure and deterministic given {@code jitterFraction} in [0,1), so it is unit-testable.
     */
    static long delayMs(long baseMs, int attempt, double jitterFraction) {
        long b = Math.max(0, baseMs);
        int shift = Math.min(attempt, 20); // avoid overflow; 2^20 * base is already capped below
        long exp = Math.min(MAX_BACKOFF_MS, b * (1L << shift));
        double jf = jitterFraction < 0 ? 0 : (jitterFraction >= 1 ? 0.999 : jitterFraction);
        long jitter = (long) (exp * jf);
        return Math.min(MAX_BACKOFF_MS, exp + jitter);
    }

    public static <T> T withBackoff(int attempts, long baseMs, Op<T> op) throws Exception {
        int max = Math.max(1, attempts);
        java.io.IOException last = null;
        for (int i = 0; i < max; i++) {
            try {
                return op.call();
            } catch (java.io.IOException e) {
                last = e;
                if (i == max - 1) break;
                try {
                    Thread.sleep(delayMs(baseMs, i, ThreadLocalRandom.current().nextDouble()));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last;
    }
}
