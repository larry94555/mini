package com.example.imini;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Bounds how many agent runs execute at once to the model's slot count, so we never oversubscribe
 * llama-server. A fair Semaphore is the bound; runs that can't get a permit block on acquire() --
 * that waiting set IS the job queue. Both the blocking endpoints (runBounded on the request thread)
 * and the streaming endpoints (submitAsync -> runBounded on a pool thread) share the same permits,
 * so total concurrency is capped regardless of entry point.
 */
@Component
public class RunService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RunService.class);


    @Value("${llama.parallel:1}") private int slots;
    @Value("${agent.max-concurrent-runs:0}") private int maxConcurrentCfg; // 0 => use llama.parallel

    private Semaphore permits;
    private ExecutorService async;
    private int limit;

    @PostConstruct
    public void init() {
        limit = maxConcurrentCfg > 0 ? maxConcurrentCfg : Math.max(1, slots);
        permits = new Semaphore(limit, true); // fair = first-come-first-served queue
        async = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "imini-run");
            t.setDaemon(true);
            return t;
        });
        log.info("[runs] concurrency bounded to " + limit + " (model slots).");
    }

    public int limit() { return limit; }
    public int active() { return limit - permits.availablePermits(); }
    public int queued() { return permits.getQueueLength(); }

    /** Run on the CALLING thread, but only once a slot is free (blocks otherwise). */
    public <T> T runBounded(Callable<T> task) throws Exception {
        permits.acquire();
        try {
            return task.call();
        } finally {
            permits.release();
        }
    }

    /** Run asynchronously (streaming endpoints); the task itself calls runBounded for the slot. */
    public void submitAsync(Runnable task) {
        async.submit(task);
    }

    @PreDestroy
    public void stop() {
        if (async != null) async.shutdownNow();
    }
}
