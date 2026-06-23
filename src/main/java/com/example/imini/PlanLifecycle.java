package com.example.imini;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Pure plan-lifecycle model: the well-defined stages of a plan, a registry binding skills to stages, and a
 * selector that — given a stage, the bindings, the available skills, and the current plan/goal text — returns
 * the ordered skills to apply at that stage. No I/O, no LLM, fully offline-testable. An empty registry yields
 * an empty result (a no-op), so unbound skills surface exactly as before. Ranking reuses
 * {@link SkillLibrary#select} (the existing lexical/BM25 scorer).
 */
public final class PlanLifecycle {

  private PlanLifecycle() {}

  /** The stages at which a skill can be invoked while planning and executing. */
  public enum Stage {
    PREPARE("prepare"), REVIEW("review"), SUB_PLAN("sub-plan"),
    TOOL_SELECT("tool-select"), GOAL_EVAL("goal-eval"), POST_MORTEM("post-mortem");

    private final String id;

    Stage(String id) {
      this.id = id;
    }

    public String id() {
      return id;
    }

    /** Parse a stage by its id (e.g. {@code "sub-plan"}); null if unknown. */
    public static Stage fromId(String s) {
      if (s == null) {
        return null;
      }
      String t = s.trim().toLowerCase(java.util.Locale.ROOT);
      for (Stage st : values()) {
        if (st.id.equals(t)) {
          return st;
        }
      }
      return null;
    }
  }

  /** Immutable stage -> bound skill-name list. */
  public record Bindings(Map<Stage, List<String>> map) {

    public List<String> forStage(Stage s) {
      List<String> v = map.get(s);
      return v == null ? List.of() : v;
    }

    public boolean isEmpty() {
      for (List<String> v : map.values()) {
        if (!v.isEmpty()) {
          return false;
        }
      }
      return true;
    }

    /** Diagnostics view: stage id -> bound names (only stages with at least one binding). */
    public Map<String, List<String>> asIdMap() {
      Map<String, List<String>> out = new LinkedHashMap<>();
      for (Stage st : Stage.values()) {
        List<String> v = forStage(st);
        if (!v.isEmpty()) {
          out.put(st.id(), v);
        }
      }
      return out;
    }

    /**
     * Parse a config string of the form {@code "prepare=skill-builder,code-review; review=code-review"}:
     * entries separated by {@code ;} or newlines, each {@code <stage>=<comma/space-separated names>}. Unknown
     * stages are ignored. Pure.
     */
    public static Bindings parse(String cfg) {
      Map<Stage, List<String>> map = new LinkedHashMap<>();
      if (cfg != null) {
        for (String entry : cfg.split("[;\\n]+")) {
          int eq = entry.indexOf('=');
          if (eq <= 0) {
            continue;
          }
          Stage stage = Stage.fromId(entry.substring(0, eq));
          if (stage == null) {
            continue;
          }
          List<String> names = SkillLibrary.parseList(entry.substring(eq + 1));
          if (!names.isEmpty()) {
            map.computeIfAbsent(stage, k -> new ArrayList<>()).addAll(names);
          }
        }
      }
      return new Bindings(map);
    }
  }

  /**
   * The ordered skills to apply at {@code stage}: the skills bound to that stage that exist in {@code all},
   * ranked by relevance to {@code planText} (reusing {@link SkillLibrary#select}), with bound-but-unmatched
   * skills retained in binding order, capped to {@code k} (k &lt;= 0 = all). Empty bindings -> empty list.
   */
  public static List<SkillLibrary.Skill> selectForStage(Stage stage, Bindings bindings,
                                                         List<SkillLibrary.Skill> all, String planText, int k) {
    if (stage == null || bindings == null || bindings.isEmpty() || all == null) {
      return List.of();
    }
    List<String> names = bindings.forStage(stage);
    if (names.isEmpty()) {
      return List.of();
    }
    Map<String, SkillLibrary.Skill> byName = new LinkedHashMap<>();
    for (SkillLibrary.Skill s : all) {
      byName.put(s.name(), s);
    }
    // Candidates in binding order (only skills that actually exist and are available).
    List<SkillLibrary.Skill> candidates = new ArrayList<>();
    for (String n : names) {
      SkillLibrary.Skill s = byName.get(n);
      if (s != null && !candidates.contains(s)) {
        candidates.add(s);
      }
    }
    if (candidates.isEmpty()) {
      return List.of();
    }
    // Rank by the existing selector; then append bound-but-unranked skills in binding order.
    LinkedHashSet<SkillLibrary.Skill> ordered = new LinkedHashSet<>(
        SkillLibrary.select(candidates, planText == null ? "" : planText, candidates.size()));
    ordered.addAll(candidates);
    List<SkillLibrary.Skill> out = new ArrayList<>(ordered);
    if (k > 0 && out.size() > k) {
      out = new ArrayList<>(out.subList(0, k));
    }
    return out;
  }

  /** Pure: the names of the skills applied at a stage (for diagnostics / "which skills fired"). */
  public static List<String> appliedNames(List<SkillLibrary.Skill> picks) {
    List<String> names = new ArrayList<>();
    if (picks != null) {
      for (SkillLibrary.Skill s : picks) {
        if (s != null) {
          names.add(s.name());
        }
      }
    }
    return names;
  }
}
