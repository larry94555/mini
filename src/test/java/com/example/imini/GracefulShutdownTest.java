package com.example.imini;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GracefulShutdownTest {

    private RunService service(int drainSeconds) throws Exception {
        RunService rs = new RunService();
        Field f = rs.getClass().getDeclaredField("slots");     f.setAccessible(true); f.set(rs, 2);
        Field f2 = rs.getClass().getDeclaredField("maxConcurrentCfg"); f2.setAccessible(true); f2.set(rs, 0);
        Field f3 = rs.getClass().getDeclaredField("drainSeconds"); f3.setAccessible(true); f3.set(rs, drainSeconds);
        rs.init();
        return rs;
    }

    @Test
    void newRunsRejectedOnceShutdown() throws Exception {
        RunService rs = service(1);
        rs.stop(); // trigger drain
        assertThrows(IllegalStateException.class, () -> rs.runBounded(() -> "x"));
    }

    @Test
    void inFlightRunsCompleteBeforeShutdown() throws Exception {
        RunService rs = service(5);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean(false);
        // start a run that takes ~150ms
        Thread t = new Thread(() -> {
            try {
                rs.runBounded(() -> {
                    started.countDown();
                    Thread.sleep(150);
                    finished.set(true);
                    return null;
                });
            } catch (Exception ignore) {}
        });
        t.start();
        started.await(); // run is in-flight
        rs.stop();       // drain — should wait for the run
        assertTrue(finished.get(), "in-flight run should complete before shutdown");
    }

    @Test
    void drainingFlagPreventsFurtherSubmits() throws Exception {
        RunService rs = service(1);
        assertEquals(false, rs.isDraining());
        rs.stop();
        assertTrue(rs.isDraining());
    }
}
