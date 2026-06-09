package com.example.imini;

/**
 * Retries a transient operation with exponential backoff. Only IOExceptions (network / 5xx wrapped
 * as IOException) are retried; any other exception propagates immediately, so client errors (4xx,
 * bad input) are not pointlessly retried.
 */
public final class Retry {

    private Retry() {}

    public interface Op<T> {
        T call() throws Exception;
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
                    Thread.sleep(baseMs * (1L << i)); // 400ms, 800ms, 1600ms, ...
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last;
    }
}
