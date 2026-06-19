package com.example.imini;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tool-level capability scoping: restricts WHICH tools a caller may invoke, by role, on top of the
 * mode-based gating in {@link PermissionService}. Where {@code PermissionService} answers "may this
 * mutating call proceed right now?" (ASK/AUTO/PLAN, deny rules, workspace confinement), capability
 * scoping answers a coarser, earlier question: "is this caller even allowed to use this tool at all?"
 * — and it applies to read-only tools too (a reporting bot can be denied {@code run_command} entirely).
 *
 * <p>Scopes are configured per role as a single property, mirroring {@code auth.principals} and
 * {@code cost.tiers}. Example:
 * <pre>
 *   capabilities.enabled=true
 *   capabilities.scopes=reader=read_file|view_dir|grep|repo_tree|web_search|web_fetch, operator=*
 *   capabilities.default-scope=*
 * </pre>
 * A scope of {@code *} means "every tool". A role with no configured scope falls back to
 * {@code capabilities.default-scope}. The {@code admin} role is always unrestricted. When
 * {@code capabilities.enabled=false} (the default) every tool is permitted, so behaviour is unchanged.
 *
 * <p>Enforcement reads the current caller from {@link RequestContext}. Request-initiated runs carry the
 * authenticated principal on the request thread; background/sub-agent runs have no principal and run as
 * the unrestricted system identity (see {@link Principal#ANON}). The scope-resolution logic is pure and
 * unit-tested.
 */
@Component
public class CapabilityService {

    private static final Logger log = LoggerFactory.getLogger(CapabilityService.class);

    @Value("${capabilities.enabled:false}") private boolean enabled;
    @Value("${capabilities.scopes:}") private String scopesCfg;
    @Value("${capabilities.default-scope:*}") private String defaultScopeCfg;

    private Map<String, Set<String>> scopes = Map.of();
    private Set<String> defaultScope; // null => unrestricted

    public boolean enabled() { return enabled; }

    @jakarta.annotation.PostConstruct
    public void init() {
        scopes = parseScopes(scopesCfg);
        defaultScope = parseScope(defaultScopeCfg);
        if (enabled) {
            log.info("[capabilities] enabled: " + scopes.size() + " role scope(s); default="
                    + (defaultScope == null ? "*" : defaultScope));
        } else {
            log.info("[capabilities] disabled (capabilities.enabled=false)");
        }
    }

    /** Parse "role=t1|t2, role2=*" into role -> tool set. A "*" set is represented as a set containing "*". */
    static Map<String, Set<String>> parseScopes(String csv) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        if (csv == null || csv.isBlank()) return out;
        for (String entry : csv.split(",")) {
            String e = entry.trim();
            if (e.isEmpty()) continue;
            int eq = e.indexOf('=');
            if (eq <= 0) continue;
            String role = e.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            Set<String> tools = parseScope(e.substring(eq + 1));
            if (tools != null) out.put(role, tools);
            else out.put(role, Set.of("*"));
        }
        return out;
    }

    /** Parse one scope value ("t1|t2" or "*"). Returns null for "*"/blank (meaning unrestricted). */
    static Set<String> parseScope(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isEmpty() || v.equals("*")) return null;
        Set<String> tools = new LinkedHashSet<>();
        for (String t : v.split("\\|")) {
            String tool = t.trim();
            if (!tool.isEmpty()) tools.add(tool);
        }
        return tools;
    }

    /** Pure: is {@code tool} permitted by {@code scope}? A null scope (or one containing "*") allows all. */
    static boolean permits(Set<String> scope, String tool) {
        if (scope == null || scope.contains("*")) return true;
        return scope.contains(tool);
    }

    /** The tool set allowed for a role: null = unrestricted (admin, or default-scope of "*"). */
    Set<String> allowedFor(String role) {
        String r = role == null ? "" : role.toLowerCase(Locale.ROOT);
        if (r.equals("admin")) return null;            // admins are never capability-restricted
        if (scopes.containsKey(r)) {
            Set<String> s = scopes.get(r);
            return (s != null && s.contains("*")) ? null : s;
        }
        return defaultScope;                            // may be null (unrestricted)
    }

    /** Is the given role permitted to call the named tool? Always true when scoping is disabled. */
    public boolean permits(String role, String tool) {
        if (!enabled) return true;
        return permits(allowedFor(role), tool);
    }

    /** Is the CURRENT caller (from RequestContext) permitted to call the named tool? */
    public boolean permitsCurrent(String tool) {
        if (!enabled) return true;
        return permits(RequestContext.current().role(), tool);
    }

    /** The resolved scopes, for the /admin/capabilities view. */
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("defaultScope", defaultScope == null ? "*" : defaultScope);
        Map<String, Object> roles = new LinkedHashMap<>();
        scopes.forEach((role, tools) -> roles.put(role, tools.contains("*") ? "*" : tools));
        out.put("roles", roles);
        return out;
    }
}
