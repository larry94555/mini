package com.example.imini;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;

/**
 * The smallest possible version of what is, in Claude Code, dozens of UI components: a gate that
 * stops the agent before it does something irreversible and asks a human. Read-only tools never
 * reach this; only mutating ones (write_file, run_command) do.
 *
 * Set agent.auto-approve=true in application.properties to skip prompts while experimenting.
 * If there is no console attached, this denies by default -- fail safe, not fail open.
 */
@Component
public class PermissionGate {

    @Value("${agent.auto-approve:false}")
    private boolean autoApprove;

    private final BufferedReader in = new BufferedReader(new InputStreamReader(System.in));

    public boolean confirm(String toolName, Map<String, Object> args) {
        if (autoApprove) {
            System.out.println("[permission] auto-approved '" + toolName + "'");
            return true;
        }
        System.out.println("\n[permission] Tool '" + toolName + "' wants to run with:");
        System.out.println("            " + args);
        System.out.print("[permission] Allow? (y/N): ");
        try {
            String line = in.readLine();
            boolean ok = line != null && line.trim().equalsIgnoreCase("y");
            System.out.println(ok ? "[permission] approved." : "[permission] denied.");
            return ok;
        } catch (IOException e) {
            return false; // safe default
        }
    }
}
