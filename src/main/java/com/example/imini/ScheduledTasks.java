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
    private final Database db;
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(1);
    private ScheduledExecutorService ticker;
    private ExecutorService pool;

    private final Metrics metrics;
    private final java.util.Map<String, RunHistory> taskRuns = new java.util.concurrent.ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Value("${agent.schedule.run-history.persist-max:50}") private int taskRunPersistMax;

    public ScheduledTasks(AgentLoop loop, Database db, Metrics metrics) {
        this.metrics = metrics;
        this.loop = loop;
        this.db = db;
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
        reload();
        ticker.scheduleAtFixedRate(this::tick, tickSeconds, tickSeconds, TimeUnit.SECONDS);
        log.info("[schedule] ticker started (every " + tickSeconds + "s); " + tasks.size() + " task(s) loaded");
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
        persist(t);
        log.info("[schedule] added " + id + " kind=" + k + " oneShot=" + oneShot
                + " first=+" + Schedule.clampSeconds(delaySeconds) + "s");
        return t;
    }

    public List<Task> list() {
        return new ArrayList<>(tasks.values());
    }

    public boolean cancel(String id) {
        boolean removed = tasks.remove(id) != null;
        if (removed && db.available()) db.update("DELETE FROM scheduled_tasks WHERE id=?", id);
        return removed;
    }

    public boolean setEnabled(String id, boolean on) {
        Task t = tasks.get(id);
        if (t == null) return false;
        t.enabled = on;
        persist(t);
        return true;
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Task t : tasks.values()) {
            if (!Schedule.isDue(t.enabled, t.nextRunEpochMs, now)) continue;
            // reschedule BEFORE running so a long run can't double-fire
            if (t.oneShot) { t.enabled = false; t.nextRunEpochMs = 0; }
            else t.nextRunEpochMs = Schedule.nextRun(now, t.intervalSeconds, false);
            persist(t);
            pool.submit(() -> runTask(t));
        }
    }

    private void runTask(Task t) {
        long start = System.currentTimeMillis();
        t.lastRunEpochMs = start;
        org.slf4j.MDC.put("runKind", "scheduled");
        org.slf4j.MDC.put("taskId", t.id);
        org.slf4j.MDC.put("session", t.sessionId == null ? "" : t.sessionId);
        try {
            RunSink sink = new ConsoleSink();
            String result = switch (t.kind) {
                case "plan" -> loop.runPlan(t.sessionId, t.prompt, Mode.AUTO, sink);
                case "loop" -> loop.runLoop(t.sessionId, t.prompt, Mode.AUTO, sink);
                default -> loop.run(t.sessionId, t.prompt, Mode.AUTO, sink);
            };
            t.runs++;
            t.lastDetail = truncate(result, 300);
            long ms = System.currentTimeMillis() - start;
            recordTaskRun(t, ms, true);
            log.info("[schedule] " + t.id + " ran (" + ms + "ms)");
        } catch (Exception e) {
            t.lastDetail = "error: " + e.getMessage();
            recordTaskRun(t, System.currentTimeMillis() - start, false);
            log.warn("[schedule] " + t.id + " failed: " + e.getMessage());
        } finally {
            org.slf4j.MDC.clear(); // scheduled runs are on a pool thread with no request boundary to clear it
        }
        if (t.oneShot) {
            tasks.remove(t.id);                                  // completed one-shot drops off the list
            if (db.available()) db.update("DELETE FROM scheduled_tasks WHERE id=?", t.id);
        } else {
            persist(t);                                          // record runs / lastDetail
        }
    }

    /** Record a scheduled execution into this task's recent-runs ring and the global run history. */
    private void recordTaskRun(Task t, long ms, boolean ok) {
        long ts = System.currentTimeMillis();
        RunHistory h = taskRuns.computeIfAbsent(t.id, k -> new RunHistory(20));
        h.add(new RunHistory.Record(ts, "/schedule:" + t.kind, t.sessionId, "auto", ms, ok));
        if (metrics != null) metrics.recordRun("/schedule:" + t.kind, t.sessionId, "auto", ms, ok);
        if (db.available()) {                              // durable across restarts
            try {
                db.update("INSERT INTO scheduled_task_runs(task_id, ts, ms, ok) VALUES(?,?,?,?)",
                        t.id, ts, ms, ok ? 1 : 0);
                int cap = Math.max(1, taskRunPersistMax);
                db.update("DELETE FROM scheduled_task_runs WHERE task_id=? AND rowid NOT IN "
                        + "(SELECT rowid FROM scheduled_task_runs WHERE task_id=? ORDER BY ts DESC, rowid DESC LIMIT ?)",
                        t.id, t.id, cap);
            } catch (Exception e) {
                log.warn("[schedule] persist run for " + t.id + ": " + e.getMessage());
            }
        }
    }

    /** Load a task's persisted run history (oldest-first) into its in-memory ring. */
    private void loadTaskRuns(String id) {
        if (!db.available()) return;
        RunHistory h = taskRuns.computeIfAbsent(id, k -> new RunHistory(20));
        try {
            java.util.List<RunHistory.Record> newestFirst = db.query(
                    "SELECT ts, ms, ok FROM scheduled_task_runs WHERE task_id=? ORDER BY ts DESC, rowid DESC LIMIT 20",
                    rs -> new RunHistory.Record(rs.getLong(1), "/schedule", "", "auto", rs.getLong(2), rs.getInt(3) == 1),
                    id);
            for (int i = newestFirst.size() - 1; i >= 0; i--) h.add(newestFirst.get(i)); // -> oldest first
        } catch (Exception e) {
            log.warn("[schedule] load runs for " + id + ": " + e.getMessage());
        }
    }

    /** Recent executions of one task (newest first), as plain maps. Empty if unknown / none yet. */
    public java.util.List<java.util.Map<String, Object>> runHistory(String id, int n) {
        RunHistory h = taskRuns.get(id);
        return h == null ? java.util.List.of() : h.recentMaps(n);
    }

    private void persist(Task t) {
        if (!db.available()) return;
        db.update("INSERT INTO scheduled_tasks(id, session_id, prompt, kind, interval_seconds, one_shot, "
                + "next_run, enabled, owner, runs, created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?) "
                + "ON CONFLICT(id) DO UPDATE SET next_run=excluded.next_run, enabled=excluded.enabled, "
                + "runs=excluded.runs",
                t.id, t.sessionId, t.prompt, t.kind, t.intervalSeconds, t.oneShot ? 1 : 0,
                t.nextRunEpochMs, t.enabled ? 1 : 0, t.owner, t.runs, System.currentTimeMillis());
    }

    private void reload() {
        if (!db.available()) return;
        long now = System.currentTimeMillis();
        long maxSeq = 0;
        for (Task t : db.query("SELECT id, session_id, prompt, kind, interval_seconds, one_shot, next_run, "
                + "enabled, owner, runs FROM scheduled_tasks", rs -> {
                    Task x = new Task(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getLong(5), rs.getInt(6) == 1, Schedule.reloadNextRun(rs.getLong(7), System.currentTimeMillis()),
                            rs.getString(9));
                    x.enabled = rs.getInt(8) == 1;
                    x.runs = rs.getInt(10);
                    return x;
                })) {
            // drop completed one-shots (next_run==0) rather than reloading them
            if (t.oneShot && t.nextRunEpochMs <= 0) { db.update("DELETE FROM scheduled_tasks WHERE id=?", t.id); continue; }
            loadTaskRuns(t.id);
            tasks.put(t.id, t);
            try { maxSeq = Math.max(maxSeq, Long.parseLong(t.id.replace("task-", ""))); } catch (Exception ignore) {}
        }
        if (maxSeq > 0) seq.set(maxSeq + 1); // avoid id collisions with reloaded tasks
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String t = s.replace("\n", " ").strip();
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }
}
