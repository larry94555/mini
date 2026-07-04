package com.example.imini.ext;

import com.example.imini.Extension;
import com.example.imini.ExtensionContext;
import com.example.imini.LoopEvent;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * USE CASE 5 — observe the agent loop.
 *
 * <p>The typed, in-process successor to a {@code postToolUse} shell hook. {@link #onEvent} is called for
 * every {@link LoopEvent} the harness emits — today {@code PRE_TOOL_USE} and {@code POST_TOOL_USE} from
 * the tool-dispatch path. This is OBSERVE-ONLY: it is for metrics, logging, and side-channel reactions,
 * and (unlike a preToolUse hook) it cannot block a tool. Keep it fast and exception-safe — a throw is
 * caught and logged, never propagated into the run.
 *
 * <p>This example keeps a per-tool call counter and logs each completed call with its result size.
 */
@Component
public class ToolAuditExtension implements Extension {

    private final ConcurrentHashMap<String, AtomicLong> counts = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "tool-audit";
    }

    @Override
    public void onEvent(LoopEvent event, ExtensionContext ctx) {
        if (event.type() == LoopEvent.Type.POST_TOOL_USE) {
            long n = counts.computeIfAbsent(event.tool(), k -> new AtomicLong()).incrementAndGet();
            int size = event.result() == null ? 0 : event.result().length();
            ctx.log().info("tool " + event.tool() + " called " + n + " time(s) this process; "
                    + "last result " + size + " chars (session " + event.sessionId() + ")");
        }
    }
}
