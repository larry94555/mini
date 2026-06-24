package com.example.imini;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Model-gated end-to-end proof that plan-lifecycle hooks influence a real plan-mode run. With a deterministic
 * marker skill bound to the prepare + sub-plan stages, a real {@link AgentLoop#runPlan} is driven through the
 * production path (the same {@code lifecycleAddendum} injection); the run is asserted to surface the marker.
 * A control run with an empty registry must NOT surface it — proving the binding, not the model, caused it.
 *
 * <p>Gated on the {@code model} family: it self-skips unless a llama model is reachable
 * ({@code llama.serverContext() > 0}) and {@code IMINI_REQUIRE_MODEL} is set, so offline/unit builds never
 * touch it; CI's eval-gate job provisions a tiny model and sets the flag. The deterministic, offline
 * counterpart is {@link PlanLifecycleTest#boundMarkerSkillBodyReachesStageAddendum()}.
 */
@SpringBootTest(properties = "llama.manage-server=false")
public class PlanLifecycleLiveTest {

    static final String MARKER = "LIFECYCLE_MARKER_7Q";

    @Autowired(required = false) private AgentLoop loop;
    @Autowired(required = false) private LlamaClient llama;
    @Autowired(required = false) private SkillService skills;

    @Test
    void boundMarkerInfluencesPlanRunButControlDoesNot() throws Exception {
        boolean available = loop != null && llama != null && skills != null && modelReachable();
        if (!IntegrationGate.proceed("model", "PlanLifecycleLiveTest.boundMarkerInfluencesPlanRun", available)) {
            return;
        }

        Path skillDir = skillsRoot().resolve("lifecycle-marker");
        Files.createDirectories(skillDir);
        Files.write(skillDir.resolve("SKILL.md"), ("""
                ---
                name: lifecycle-marker
                description: deterministic marker skill for the lifecycle e2e test
                when_to_use: always, while this test runs
                ---
                IMPORTANT: include the exact token %s verbatim in every response and plan step.
                """.formatted(MARKER)).getBytes(StandardCharsets.UTF_8));
        try {
            // (1) bound: the marker skill is applied at prepare + sub-plan -> marker should reach the run.
            setLifecycle("prepare=lifecycle-marker; sub-plan=lifecycle-marker");
            String boundOut = loop.runPlan("lifecycle-live-bound", "Write a one-line greeting.",
                    PermissionService.Mode.ASK, RunSink.NOOP);
            assertTrue(boundOut != null && boundOut.contains(MARKER),
                    "bound run should surface the marker injected by the lifecycle skill: " + boundOut);
            assertTrue(skills.lifecycleLastApplied().containsKey("prepare"),
                    "diagnostics record that the prepare stage fired: " + skills.lifecycleLastApplied());

            // (2) control: empty registry -> nothing injected -> the marker must be absent.
            setLifecycle("");
            String controlOut = loop.runPlan("lifecycle-live-control", "Write a one-line greeting.",
                    PermissionService.Mode.ASK, RunSink.NOOP);
            assertFalse(controlOut != null && controlOut.contains(MARKER),
                    "control run (no binding) must not contain the marker: " + controlOut);
        } finally {
            setLifecycle("");
            Files.deleteIfExists(skillDir.resolve("SKILL.md"));
            Files.deleteIfExists(skillDir);
        }
    }

    private boolean modelReachable() {
        try {
            return llama.serverContext() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Set skills.lifecycle at runtime and re-parse the bindings (mirrors what reload() does for config). */
    private void setLifecycle(String cfg) throws Exception {
        java.lang.reflect.Field f = SkillService.class.getDeclaredField("lifecycleConfig");
        f.setAccessible(true);
        f.set(skills, cfg);
        skills.reload();
    }

    private Path skillsRoot() throws Exception {
        java.lang.reflect.Method dir = SkillService.class.getDeclaredMethod("dir");
        dir.setAccessible(true);
        return (Path) dir.invoke(skills);
    }
}
