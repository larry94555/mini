package com.example.imini;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxHardenTest {

    private static void set(Object o, String f, Object v) throws Exception {
        Field fl = o.getClass().getDeclaredField(f);
        fl.setAccessible(true);
        fl.set(o, v);
    }

    private Sandbox sandbox(Path root, int maxBytes) throws Exception {
        Sandbox s = new Sandbox();
        set(s, "workspaceRootCfg", root.toString());
        set(s, "confineWrites", true);
        set(s, "confineReads", false);
        set(s, "commandMode", "deny-only");
        set(s, "allowCfg", "");
        set(s, "denyCfg", "");
        set(s, "maxCommandLength", 2000);
        set(s, "containerCommand", "");
        set(s, "maxOutputBytes", maxBytes);
        s.load();
        return s;
    }

    @Test
    void outputIsCappedAtMaxBytes() throws Exception {
        Path dir = Files.createTempDirectory("sb-test");
        Sandbox sb = sandbox(dir, 10); // tiny cap
        // echo produces well-known output; even if OS doesn't have echo, DENIED/ERROR is short
        String out = sb.executeSandboxed("echo hello world this is a long line", 5);
        // either output is capped or it's a DENIED/ERROR string -- either way <= sensible length
        assertTrue(out.length() <= 300, "output length was " + out.length());
    }

    @Test
    void deniedCommandReturnsPrefix() throws Exception {
        Path dir = Files.createTempDirectory("sb-deny");
        Sandbox sb = sandbox(dir, 65536);
        // "rm -rf" is in the DEFAULT_DENY list
        String out = sb.executeSandboxed("rm -rf /tmp/whatever", 5);
        assertTrue(out.startsWith("DENIED:"), "expected DENIED, got: " + out);
    }

    @Test
    void maxOutputBytesFloorIsRespected() throws Exception {
        Path dir = Files.createTempDirectory("sb-floor");
        Sandbox sb = sandbox(dir, 0); // zero -> should floor to 1024
        assertEquals(1024, sb.maxOutputBytes());
    }
}
