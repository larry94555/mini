package com.example.imini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Pure helpers for MCP hot-reload: a normalized, equals-comparable {@link ServerSpec} for one {@code
 * mcpServers} entry, and a {@link #diff} that computes which servers were added, removed, or changed between
 * the running config and a freshly-read one. No child processes, no I/O — fully unit-testable offline. JSON
 * parsing ({@link #parseSpecs}) is separate and pure given an {@link ObjectMapper}, so the diff can be tested
 * without a mapper.
 */
public final class McpConfig {

  private McpConfig() {}

  /** A normalized server definition; two specs are equal iff command/args/env/transport/url all match. */
  public record ServerSpec(String transport, String command, List<String> args,
                           Map<String, String> env, String url) {
    public ServerSpec {
      transport = (transport == null || transport.isBlank()) ? "stdio" : transport.toLowerCase();
      command = command == null ? "" : command;
      args = args == null ? List.of() : List.copyOf(args);
      env = env == null ? Map.of() : Map.copyOf(env);
      url = url == null ? "" : url;
    }
  }

  /** The set of changes between two server-config maps (sorted, stable name lists). */
  public record ReloadPlan(List<String> added, List<String> removed,
                           List<String> restarted, List<String> unchanged) {
    public boolean isNoOp() {
      return added.isEmpty() && removed.isEmpty() && restarted.isEmpty();
    }

    /** Servers whose tools must be pruned: removed + changed (sorted, deduped). */
    public List<String> serversToStop() {
      TreeSet<String> s = new TreeSet<>(removed);
      s.addAll(restarted);
      return new ArrayList<>(s);
    }

    /** Servers to (re)launch and re-discover: added + changed (sorted, deduped). */
    public List<String> serversToStart() {
      TreeSet<String> s = new TreeSet<>(added);
      s.addAll(restarted);
      return new ArrayList<>(s);
    }

    public Map<String, Object> summary() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("added", added);
      m.put("removed", removed);
      m.put("restarted", restarted);
      m.put("unchanged", unchanged);
      m.put("no_op", isNoOp());
      return m;
    }
  }

  /** Build a {@link ServerSpec} from an already-parsed {@code mcpServers} entry map. Pure (no JSON). */
  @SuppressWarnings("unchecked")
  public static ServerSpec spec(Map<String, Object> conf) {
    if (conf == null) {
      return new ServerSpec("stdio", "", List.of(), Map.of(), "");
    }
    String transport = str(conf.get("transport"));
    String command = str(conf.get("command"));
    String url = str(conf.get("url"));
    List<String> args = new ArrayList<>();
    Object a = conf.get("args");
    if (a instanceof List<?> list) {
      for (Object o : list) {
        args.add(String.valueOf(o));
      }
    }
    Map<String, String> env = new LinkedHashMap<>();
    Object e = conf.get("env");
    if (e instanceof Map<?, ?> m) {
      m.forEach((k, v) -> env.put(String.valueOf(k), String.valueOf(v)));
    }
    return new ServerSpec(transport, command, args, env, url);
  }

  /** Pure: diff the running specs against the desired specs into an add/remove/restart plan. */
  public static ReloadPlan diff(Map<String, ServerSpec> running, Map<String, ServerSpec> desired) {
    Map<String, ServerSpec> oldM = running == null ? Map.of() : running;
    Map<String, ServerSpec> newM = desired == null ? Map.of() : desired;
    List<String> added = new ArrayList<>();
    List<String> removed = new ArrayList<>();
    List<String> restarted = new ArrayList<>();
    List<String> unchanged = new ArrayList<>();
    for (String name : new TreeSet<>(newM.keySet())) {
      if (!oldM.containsKey(name)) {
        added.add(name);
      } else if (!oldM.get(name).equals(newM.get(name))) {
        restarted.add(name);
      } else {
        unchanged.add(name);
      }
    }
    for (String name : new TreeSet<>(oldM.keySet())) {
      if (!newM.containsKey(name)) {
        removed.add(name);
      }
    }
    return new ReloadPlan(added, removed, restarted, unchanged);
  }

  /** Parse an mcp.json document's {@code mcpServers} into normalized specs. Pure given a mapper. */
  @SuppressWarnings("unchecked")
  public static Map<String, ServerSpec> parseSpecs(ObjectMapper mapper, String json) {
    Map<String, ServerSpec> out = new LinkedHashMap<>();
    try {
      JsonNode root = mapper.readTree(json);
      if (root == null) {
        return out;
      }
      JsonNode servers = root.get("mcpServers");
      if (servers == null) {
        return out;
      }
      Map<String, Object> asMap = mapper.convertValue(servers, Map.class);
      if (asMap != null) {
        for (Map.Entry<String, Object> e : asMap.entrySet()) {
          Object v = e.getValue();
          out.put(e.getKey(), spec(v instanceof Map ? (Map<String, Object>) v : Map.of()));
        }
      }
      return out;
    } catch (Exception ex) {
      return out;
    }
  }

  private static String str(Object o) {
    return o == null ? "" : String.valueOf(o);
  }

  /** Pure: the same name sanitization McpManager uses when exposing a server's tools. */
  public static String sanitize(String s) {
    return s == null ? "" : s.replaceAll("[^a-zA-Z0-9_]", "_");
  }

  /** Pure: the tool-name prefix for a server ({@code <sanitized-name>_}). */
  public static String toolPrefix(String server) {
    return sanitize(server) + "_";
  }

  /** Pure: per-server tool counts (how many tools each server contributes), sorted by server name. */
  public static Map<String, Integer> toolCountsByServer(java.util.Collection<String> toolNames,
                                                        java.util.Collection<String> serverNames) {
    Map<String, Integer> out = new java.util.TreeMap<>();
    if (serverNames == null) {
      return out;
    }
    for (String server : serverNames) {
      String prefix = toolPrefix(server);
      int n = 0;
      if (toolNames != null) {
        for (String t : toolNames) {
          if (t != null && t.startsWith(prefix)) {
            n++;
          }
        }
      }
      out.put(server, n);
    }
    return out;
  }
}
