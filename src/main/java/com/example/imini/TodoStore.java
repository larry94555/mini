package com.example.imini;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-session task checklist. The model overwrites its own session's list via the todo_write tool;
 * read it at GET /todos?sessionId=. Keyed by sessionId so concurrent users keep separate lists
 * (last-write-wins within a session).
 */
@Component
public class TodoStore {

    public record Item(String content, String status) {}

    private final Map<String, List<Item>> bySession = new ConcurrentHashMap<>();

    public void set(String sessionId, List<Item> newItems) {
        bySession.put(sessionId, List.copyOf(newItems));
    }

    public List<Item> get(String sessionId) {
        return bySession.getOrDefault(sessionId, List.of());
    }

    public String render(String sessionId) {
        List<Item> items = get(sessionId);
        if (items.isEmpty()) return "(no todos)";
        StringBuilder sb = new StringBuilder();
        for (Item it : items) {
            String status = it.status() == null ? "" : it.status();
            String box = switch (status) {
                case "completed" -> "[x]";
                case "in_progress" -> "[~]";
                default -> "[ ]";
            };
            sb.append(box).append(" ").append(it.content()).append("\n");
        }
        return sb.toString().trim();
    }
}
