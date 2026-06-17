package com.example.imini;

/**
 * Pure scheduling math for local scheduled tasks. A task either runs once after a delay or repeats on a
 * fixed interval; this class decides when it is due and computes the next run time, so the timing logic
 * is deterministic and unit-testable while {@link ScheduledTasks} owns the threads and execution.
 */
public final class Schedule {

    private Schedule() {}

    /** Minimum interval/delay we accept, to avoid runaway local load. */
    public static final long MIN_SECONDS = 10;

    /** A task is due when enabled and now has reached its next run time. */
    public static boolean isDue(boolean enabled, long nextRunEpochMs, long nowMs) {
        return enabled && nextRunEpochMs > 0 && nowMs >= nextRunEpochMs;
    }

    /** First run time given a delay (clamped to a minimum). */
    public static long firstRun(long nowMs, long delaySeconds) {
        return nowMs + Math.max(MIN_SECONDS, delaySeconds) * 1000L;
    }

    /**
     * Next run after firing: for a repeating task, now + interval (clamped); for a one-shot, 0 (done).
     */
    public static long nextRun(long nowMs, long intervalSeconds, boolean oneShot) {
        if (oneShot) return 0L;
        return nowMs + Math.max(MIN_SECONDS, intervalSeconds) * 1000L;
    }

    /** Clamp a requested interval/delay to the allowed minimum. */
    public static long clampSeconds(long seconds) {
        return Math.max(MIN_SECONDS, seconds);
    }
}
