package com.example.imini;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Holds the agent's current task checklist. The model overwrites it via the todo_write tool to plan
 * and track multi-step work; you can read it at GET /todos. Last-write-wins, single list (a learning
 * simplification -- a real system would key it per session).
 */
@Component
public class TodoStore {

    public record Item(String content, String status) {}

    private List<Item> items = List.of();

    public synchronized void set(List<Item> newItems) {
        items = List.copyOf(newItems);
    }

    public synchronized List<Item> get() {
        return items;
    }

    public synchronized String render() {
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
