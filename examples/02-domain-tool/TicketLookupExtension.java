package com.example.imini.ext;

import com.example.imini.Extension;
import com.example.imini.ExtensionContext;
import com.example.imini.Tool;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * USE CASE 2 — a config-driven "domain tool" that integrates an external system.
 *
 * <p>This is the shape of a real small application: a tool that reads its own settings from
 * {@code application.properties} (via {@link ExtensionContext#property}) and looks something up in an
 * external system (a tracker, a wiki, an internal API). To keep the example runnable offline and
 * deterministic it returns canned tickets; the comment shows exactly where a real HTTP call would go.
 *
 * <p>Because an extension is a Spring bean, a production version would just add a constructor and
 * {@code @Autowired} an HTTP client (or any harness service) — {@link ExtensionContext} stays small on
 * purpose.
 *
 * <p>Config (add to {@code application.properties}): {@code ext.tickets.base-url=https://tracker.internal}
 */
@Component
public class TicketLookupExtension implements Extension {

    // A tiny canned "database" so the example works with no network.
    private static final Map<String, String> CANNED = Map.of(
            "PROJ-1", "PROJ-1 [open] Login button misaligned on mobile",
            "PROJ-2", "PROJ-2 [closed] Add dark-mode toggle",
            "PROJ-3", "PROJ-3 [in-progress] Rate-limit the search endpoint");

    @Override
    public String name() {
        return "ticket-lookup";
    }

    @Override
    public List<Tool> tools(ExtensionContext ctx) {
        String baseUrl = ctx.property("ext.tickets.base-url", "https://tracker.internal");
        ctx.log().info("ticket-lookup wired to " + baseUrl);

        Map<String, Object> idProp = new LinkedHashMap<>();
        idProp.put("type", "string");
        idProp.put("description", "The ticket id, e.g. PROJ-1.");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("id", idProp);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("id"));

        Tool lookup = new Tool(
                "lookup_ticket",
                "Fetch a ticket's title and status by id (e.g. PROJ-1) from the issue tracker. Read-only.",
                schema,
                /* mutating = */ false,
                /* untrusted = */ true,   // output comes from an external system: fence it as data, not instructions
                args -> {
                    String id = String.valueOf(args.getOrDefault("id", "")).trim().toUpperCase();
                    // A real implementation would do, e.g.:
                    //   return httpClient.get(baseUrl + "/api/tickets/" + id);
                    // Here we serve canned data so the example runs offline.
                    return CANNED.getOrDefault(id, "No ticket " + id + " found at " + baseUrl);
                });

        return List.of(lookup);
    }
}
