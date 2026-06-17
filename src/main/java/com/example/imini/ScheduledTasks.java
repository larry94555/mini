package com.example.imini;

import com.example.imini.PermissionService.Mode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Local scheduled tasks: run a prompt (as a normal run, a plan, or a {@code /loop}) after a delay or on a
 * fixed interval, unattended. A single ticker checks for due tasks; due tasks run on a small pool in
 * AUTO permission mode (there is no user present to answer ASK prompts). Bounded by a max task count and
 * a minimum interval ({@link Schedule#MIN_SECONDS}). In-memory and single-node: tasks do not survive a
 * restart (see ROADMAP for durable scheduling).
 */
@Component
public class ScheduledTasks {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ScheduledTasks.class);

    /** One scheduled task. Mutable fields are updated under the store lock. */
    public static final class Task {
        public final String id;
        public final String sessionId;
        public final String prompt;
        public final String kind;          // run | plan | loop
        public final long intervalSeconds; // for repeating tasks
        public final boolean oneShot;
        public final String owner;
        public volatile long nextRunEpochMs;
        public volatile long lastRunEpochMs;
        public volatile boolean enabled = true;
        public volatile int runs;
        public volatile String lastDetail = "";

        Task(String id, String sessionId, String prompt, String kind, long intervalSeconds,
             boolean oneShot, long nextRunEpochMs, String owner) {
            this.id = id; this.sessionId = sessionId; this.prompt = prompt; this.kind = kind;
            this.intervalSeconds = intervalSeconds; this.oneShot = oneShot;
            this.nextRunEpochMs = nextRunEpochMs; this.owner = owner;
        }
    }

    @Value("${agent.schedule.enabled:true}") private boolean enabled;
    @Value("${agent.schedule.max-tasks:50}") private int maxTasks;
    @Value("${agent.schedule.tick-seconds:5}") private int tickSeconds;

    private final AgentLoop loop;
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(1);
    private ScheduledExecutorService ticker;
    private ExecutorService pool;

    public ScheduledTasks(AgentLoop loop) {
        this.loop = loop;
    }

    @PostConstruct
    public void start() {
        if (!enabled) return;
        pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "scheduled-task");
            t.setDaemon(true);
            return t;
        });
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "schedule-ticker");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleAtFixedRate(this::tick, tickSeconds, tickSeconds, TimeUnit.SECONDS);
        log.info("[schedule] ticker started (every " + tickSeconds + "s)");
    }

    @PreDestroy
    public void stop() {
        if (ticker != null) ticker.shutdownNow();
        if (pool != null) pool.shutdownNow();
    }

    /** Add a task; runs first after {@code delaySeconds}, then every {@code intervalSeconds} if repeating. */
    public synchronized Task add(String sessionId, String prompt, String kind, long delaySeconds,
                                 long intervalSeconds, boolean oneShot, String owner) {
        if (tasks.size() >= maxTasks) throw new IllegalStateException("too many scheduled tasks (max " + maxTasks + ")");
        String k = switch (kind == null ? "" : kind.toLowerCase()) {
            case "plan", "loop" -> kind.toLowerCase();
            default -> "run";
        };
        long now = System.currentTimeMillis();
        long first = Schedule.firstRun(now, delaySeconds);
        String id = "task-" + seq.getAndIncrement();
        Task t = new Task(id, sessionId, prompt, k, Schedule.clampSeconds(intervalSeconds), oneShot, first, owner);
        tasks.put(id, t);
        log.info("[schedule] added " + id + " kind=" + k + " oneShot=" + oneShot
                + " first=+" + Schedule.clampSeconds(delaySeconds) + "s");
        return t;
    }

    public List<Task> list() {
        return new ArrayList<>(tasks.values());
    }

    public boolean cancel(String id) {
        return tasks.remove(id) != null;
    }

    public boolean setEnabled(String id, boolean on) {
        Task t = tasks.get(id);
        if (t == null) return false;
        t.enabled = on;
        return true;
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Task t : tasks.values()) {
            if (!Schedule.isDue(t.enabled, t.nextRunEpochMs, now)) continue;
            // reschedule BEFORE running so a long run can't double-fire
            if (t.oneShot) { t.enabled = false; t.nextRunEpochMs = 0; }
            else t.nextRunEpochMs = Schedule.nextRun(now, t.intervalSeconds, false);
            pool.submit(() -> runTask(t));
        }
    }

    private void runTask(Task t) {
        long start = System.currentTimeMillis();
        t.lastRunEpochMs = start;
        try {
            RunSink sink = new ConsoleSink();
            String result = switch (t.kind) {
                case "plan" -> loop.runPlan(t.sessionId, t.prompt, Mode.AUTO, sink);
                case "loop" -> loop.runLoop(t.sessionId, t.prompt, Mode.AUTO, sink);
                default -> loop.run(t.sessionId, t.prompt, Mode.AUTO, sink);
            };
            t.runs++;
            t.lastDetail = truncate(result, 300);
            log.info("[schedule] " + t.id + " ran (" + (System.currentTimeMillis() - start) + "ms)");
        } catch (Exception e) {
            t.lastDetail = "error: " + e.getMessage();
            log.warn("[schedule] " + t.id + " failed: " + e.getMessage());
        }
        if (t.oneShot) tasks.remove(t.id);  // completed one-shot drops off the list
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String t = s.replace("\n", " ").strip();
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }
}
