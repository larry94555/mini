package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Git write tools (pure argv), MCP HTTP/SSE body selection, and hook prompt-result shape. */
class GitHookMcpWorkflowTest {

    // ---- Feature 1: git write — pure argv builders + validation ----

    @Test
    void stageArgsAllVsPaths() {
        assertEquals(List.of("add", "-A"), GitWriteTools.stageArgs(List.of()));
        assertEquals(List.of("add", "-A"), GitWriteTools.stageArgs(List.of(".")));
        assertEquals(List.of("add", "--", "src/A.java", "b.txt"),
                GitWriteTools.stageArgs(List.of("src/A.java", "b.txt")));
    }

    @Test
    void commitAndBranchArgs() {
        assertEquals(List.of("commit", "-m", "feat: x"), GitWriteTools.commitArgs("feat: x", false));
        assertEquals(List.of("commit", "-a", "-m", "m"), GitWriteTools.commitArgs("m", true));
        assertEquals(List.of("checkout", "dev"), GitWriteTools.branchArgs("dev", false));
        assertEquals(List.of("checkout", "-b", "feature/y"), GitWriteTools.branchArgs("feature/y", true));
    }

    @Test
    void branchNameValidationRejectsUnsafe() {
        assertTrue(GitWriteTools.isValidBranchName("feature/login-v2.1"));
        assertFalse(GitWriteTools.isValidBranchName("-rf"));        // looks like a flag
        assertFalse(GitWriteTools.isValidBranchName("a b"));        // whitespace
        assertFalse(GitWriteTools.isValidBranchName("a..b"));       // ref metachar
        assertFalse(GitWriteTools.isValidBranchName("x;rm -rf"));   // shell metachar
        assertFalse(GitWriteTools.isValidBranchName(""));
    }

    @Test
    void gitWriteToolsAreMutating() {
        GitWriteTools g = new GitWriteTools(new Sandbox());
        for (Tool t : g.all()) {
            assertTrue(t.mutating, t.name + " must be mutating (goes through approval)");
        }
        assertEquals(3, g.all().size());
    }

    // ---- Feature 3: MCP — HTTP/SSE body -> JSON-RPC payload selection (pure) ----

    @Test
    void httpBodyPlainJsonPassesThrough() {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}";
        assertEquals(body, McpManager.jsonFromHttpBody(body));
    }

    @Test
    void httpBodySseExtractsFirstDataLine() {
        String sse = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}\n\n";
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}", McpManager.jsonFromHttpBody(sse));
    }

    @Test
    void httpBodyJunkReturnsNull() {
        assertNull(McpManager.jsonFromHttpBody(""));
        assertNull(McpManager.jsonFromHttpBody("not json at all"));
        assertNull(McpManager.jsonFromHttpBody(null));
    }

    // ---- Feature 2: hook prompt-result shape ----

    @Test
    void promptResultRecordCarriesBlockAndInjection() {
        HookService.PromptResult blocked = new HookService.PromptResult(true, "BLOCKED: nope", "");
        assertTrue(blocked.blocked());
        assertEquals("BLOCKED: nope", blocked.message());
        HookService.PromptResult allowed = new HookService.PromptResult(false, null, "extra context");
        assertFalse(allowed.blocked());
        assertEquals("extra context", allowed.injectedContext());
    }
}
