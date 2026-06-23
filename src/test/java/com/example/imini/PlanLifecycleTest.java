package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline coverage for the pure plan-lifecycle model: stage ids, binding parsing, and stage selection
 * (ordering by the existing scorer, bound-but-unmatched skills retained, empty registry = no-op). No I/O.
 */
public class PlanLifecycleTest {

  private static SkillLibrary.Skill skill(String name, String desc, String whenToUse) {
    return new SkillLibrary.Skill(name, desc, "body of " + name, whenToUse, "", List.of(), "");
  }

  @Test
  void stageIdsRoundTrip() {
    assertEquals("sub-plan", PlanLifecycle.Stage.SUB_PLAN.id());
    assertEquals(PlanLifecycle.Stage.GOAL_EVAL, PlanLifecycle.Stage.fromId("goal-eval"));
    assertEquals(PlanLifecycle.Stage.PREPARE, PlanLifecycle.Stage.fromId("  PREPARE "));
    assertNull(PlanLifecycle.Stage.fromId("nonsense"));
  }

  @Test
  void bindingsParseStagesAndNames() {
    PlanLifecycle.Bindings b = PlanLifecycle.Bindings.parse(
        "prepare=skill-builder, code-review; review=code-review\ntool-select=tool-builder; bogus=x");
    assertEquals(List.of("skill-builder", "code-review"), b.forStage(PlanLifecycle.Stage.PREPARE));
    assertEquals(List.of("code-review"), b.forStage(PlanLifecycle.Stage.REVIEW));
    assertEquals(List.of("tool-builder"), b.forStage(PlanLifecycle.Stage.TOOL_SELECT));
    assertTrue(b.forStage(PlanLifecycle.Stage.POST_MORTEM).isEmpty(), "unbound stage is empty");
    assertTrue(b.asIdMap().containsKey("prepare") && !b.asIdMap().containsKey("post-mortem"),
        "id map omits empty stages: " + b.asIdMap());
  }

  @Test
  void emptyRegistryIsNoOp() {
    PlanLifecycle.Bindings empty = PlanLifecycle.Bindings.parse("");
    assertTrue(empty.isEmpty());
    List<SkillLibrary.Skill> all = List.of(skill("a", "x", ""), skill("b", "y", ""));
    assertTrue(PlanLifecycle.selectForStage(PlanLifecycle.Stage.PREPARE, empty, all, "anything", 5).isEmpty(),
        "no bindings -> no lifecycle skills (unbound skills surface as before)");
  }

  @Test
  void selectorReturnsOnlyBoundSkillsRankedByPlanText() {
    PlanLifecycle.Bindings b = PlanLifecycle.Bindings.parse("prepare=api-design, db-migration, unused");
    List<SkillLibrary.Skill> all = List.of(
        skill("api-design", "design a REST API endpoint", "when designing an API"),
        skill("db-migration", "safe database schema migration", "when migrating a database"),
        skill("noise", "unrelated", ""));
    // "unused" is bound but not present; "noise" is present but not bound -> excluded.
    List<SkillLibrary.Skill> picks = PlanLifecycle.selectForStage(
        PlanLifecycle.Stage.PREPARE, b, all, "I need to design an API for the service", 5);
    List<String> names = picks.stream().map(SkillLibrary.Skill::name).toList();
    assertEquals(List.of("api-design", "db-migration"), names,
        "only bound+present skills, api-design first (matches plan text): " + names);
  }

  @Test
  void boundButUnmatchedSkillsAreRetainedInBindingOrder() {
    PlanLifecycle.Bindings b = PlanLifecycle.Bindings.parse("review=alpha, beta");
    List<SkillLibrary.Skill> all = List.of(skill("alpha", "zzz", ""), skill("beta", "qqq", ""));
    // plan text matches neither description -> both still returned (bound), in binding order.
    List<SkillLibrary.Skill> picks = PlanLifecycle.selectForStage(
        PlanLifecycle.Stage.REVIEW, b, all, "totally different words", 5);
    assertEquals(List.of("alpha", "beta"), picks.stream().map(SkillLibrary.Skill::name).toList());
  }

  @Test
  void selectorCapsToK() {
    PlanLifecycle.Bindings b = PlanLifecycle.Bindings.parse("prepare=a, b, c");
    List<SkillLibrary.Skill> all = List.of(skill("a", "", ""), skill("b", "", ""), skill("c", "", ""));
    assertEquals(2, PlanLifecycle.selectForStage(PlanLifecycle.Stage.PREPARE, b, all, "x", 2).size());
  }

  @Test
  void appliedNamesListsPickedSkills() {
    List<SkillLibrary.Skill> picks = List.of(skill("api-design", "", ""), skill("db-migration", "", ""));
    assertEquals(List.of("api-design", "db-migration"), PlanLifecycle.appliedNames(picks));
    assertTrue(PlanLifecycle.appliedNames(null).isEmpty(), "null-safe");
  }

  /**
   * Deterministic proof (offline) that a bound skill's distinctive guidance reaches the stage prompt: when a
   * marker skill is bound to a stage, it is selected and its body — including the unique marker — survives
   * formatting for injection; with an empty registry nothing is selected, so the marker is absent. This is the
   * same selection+format the production lifecycleAddendum performs; the live model-gated counterpart is
   * PlanLifecycleLiveTest.
   */
  @Test
  void boundMarkerSkillBodyReachesStageAddendum() {
    String marker = "LIFECYCLE_MARKER_7Q";
    SkillLibrary.Skill markerSkill = new SkillLibrary.Skill(
        "lifecycle-marker", "a deterministic marker skill", "Always include " + marker + " in your output.",
        "when planning", "", List.of(), "");
    List<SkillLibrary.Skill> all = List.of(markerSkill, skill("noise", "unrelated", ""));

    PlanLifecycle.Bindings bound = PlanLifecycle.Bindings.parse("prepare=lifecycle-marker; sub-plan=lifecycle-marker");
    for (PlanLifecycle.Stage stage : List.of(PlanLifecycle.Stage.PREPARE, PlanLifecycle.Stage.SUB_PLAN)) {
      List<SkillLibrary.Skill> picks = PlanLifecycle.selectForStage(stage, bound, all, "draft a plan", 2);
      assertEquals(List.of("lifecycle-marker"), PlanLifecycle.appliedNames(picks), "marker bound at " + stage.id());
      String body = SkillLibrary.format(picks.get(0), 4000);
      assertTrue(body.contains(marker), "the marker reaches the injected body at " + stage.id() + ": " + body);
    }

    // Control: empty registry -> no skills selected -> the marker cannot reach any stage prompt.
    PlanLifecycle.Bindings empty = PlanLifecycle.Bindings.parse("");
    assertTrue(PlanLifecycle.selectForStage(PlanLifecycle.Stage.PREPARE, empty, all, "draft a plan", 2).isEmpty(),
        "empty registry injects nothing (the binding, not the model, causes the marker)");
  }
}
