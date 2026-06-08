package com.example.imini;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns one-shot /ask into multi-turn /chat. Each session id maps to its message history, kept in
 * memory and mirrored to .imini/sessions/<id>.json so a conversation survives a restart (resume).
 *
 * The history it stores is the SAME list the engine works on, including any compaction the engine
 * applied -- so sessions stay small automatically.
 */
@Component
public class SessionStore {

    private static final Path DIR = Path.of(".imini", "sessions");

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, List<Map<String, Object>>> cache = new HashMap<>();

    /** Returns stored history for a session, or null if it doesn't exist yet. */
    @SuppressWarnings("unchecked")
    public synchronized List<Map<String, Object>> get(String id) {
        if (cache.containsKey(id)) return cache.get(id);
        Path f = file(id);
        if (Files.exists(f)) {
            try {
                List<Map<String, Object>> h = mapper.readValue(Files.readAllBytes(f), List.class);
                cache.put(id, h);
                return h;
            } catch (IOException e) {
                System.out.println("[session] could not read '" + id + "': " + e.getMessage());
            }
        }
        return null;
    }

    public synchronized void save(String id, List<Map<String, Object>> history) {
        cache.put(id, history);
        try {
            Files.createDirectories(DIR);
            mapper.writerWithDefaultPrettyPrinter().writeValue(file(id).toFile(), history);
        } catch (IOException e) {
            System.out.println("[session] could not save '" + id + "': " + e.getMessage());
        }
    }

    public synchronized List<String> list() {
        Set<String> ids = new LinkedHashSet<>(cache.keySet());
        if (Files.isDirectory(DIR)) {
            try (var s = Files.list(DIR)) {
                s.filter(p -> p.toString().endsWith(".json"))
                 .forEach(p -> ids.add(p.getFileName().toString().replaceAll("\\.json$", "")));
            } catch (IOException ignore) {
                // directory unreadable; just return what's cached
            }
        }
        return new ArrayList<>(ids);
    }

    private Path file(String id) {
        String safe = id.replaceAll("[^a-zA-Z0-9._-]", "_");
        return DIR.resolve(safe + ".json");
    }
}
