package com.example.imini;

import java.util.Map;
import java.util.function.Function;

/**
 * One tool the model can call.
 *
 * <ul>
 *   <li>{@code mutating}: changes the world (file/shell/MCP) and goes through {@link
 *       PermissionService}.
 *   <li>{@code untrusted}: output comes from outside (web pages, MCP servers). The engine fences
 *       untrusted output so the model treats it as data, not instructions.
 * </ul>
 */
public class Tool {

  public final String name;
  public final String description;
  public final Map parameters;
  public final boolean mutating;
  public final boolean untrusted;
  public final Function<Map, String> executor;

  public Tool(
      String name,
      String description,
      Map parameters,
      boolean mutating,
      Function<Map, String> executor) {
    this(name, description, parameters, mutating, false, executor);
  }

  public Tool(
      String name,
      String description,
      Map parameters,
      boolean mutating,
      boolean untrusted,
      Function<Map, String> executor) {
    this.name = name;
    this.description = description;
    this.parameters = parameters;
    this.mutating = mutating;
    this.untrusted = untrusted;
    this.executor = executor;
  }
}
