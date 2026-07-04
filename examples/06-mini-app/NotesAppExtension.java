package com.example.imini.ext;

import com.example.imini.AgentLibrary;
import com.example.imini.Extension;
import com.example.imini.ExtensionContext;
import com.example.imini.LoopEvent;
import com.example.imini.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * USE CASE 6 — a whole small application in one extension.
 *
 * <p>Bundles all four contribution types so you can see them work together as one installable unit:
 * <ul>
 *   <li>two <b>tools</b> — {@code note_add} and {@code note_list};
 *   <li>a <b>subagent</b> — {@code notes}, scoped to just those tools;
 *   <li>a <b>slash command</b> — {@code /notes}, a shortcut that asks the model to list + summarize;
 *   <li>a <b>lifecycle observer</b> — counts how many notes were added this process.
 * </ul>
 *
 * <p>Notes are kept in memory to keep the example self-contained; a real app would persist them, in
 * which case {@code note_add} would be {@code mutating=true} and route through PermissionService.
 */
@Component
public class NotesAppExtension implements Extension {

    private final List<String> notes = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong added = new AtomicLong();

    @Override
    public String name() {
        return "notes-app";
    }

    @Override
    public List<Tool> tools(ExtensionContext ctx) {
        Map<String, Object> textProp = new LinkedHashMap<>();
        textProp.put("type", "string");
        textProp.put("description", "The note text to remember.");
        Map<String, Object> addProps = new LinkedHashMap<>();
        addProps.put("text", textProp);
        Map<String, Object> addSchema = new LinkedHashMap<>();
        addSchema.put("type", "object");
        addSchema.put("properties", addProps);
        addSchema.put("required", List.of("text"));

        Tool noteAdd = new Tool(
                "note_add",
                "Remember a short note for this session. Returns a confirmation.",
                addSchema,
                /* mutating = */ false,   // in-memory only; a persistent version would be true
                args -> {
                    String text = String.valueOf(args.getOrDefault("text", "")).trim();
                    if (text.isEmpty()) return "note is empty; nothing added";
                    notes.add(text);
                    return "added note #" + notes.size() + ": " + text;
                });

        Map<String, Object> emptySchema = new LinkedHashMap<>();
        emptySchema.put("type", "object");
        emptySchema.put("properties", new LinkedHashMap<>());
        emptySchema.put("required", List.of());

        Tool noteList = new Tool(
                "note_list",
                "List all notes remembered this session. Read-only.",
                emptySchema,
                /* mutating = */ false,
                args -> {
                    synchronized (notes) {
                        if (notes.isEmpty()) return "(no notes yet)";
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < notes.size(); i++) {
                            sb.append(i + 1).append(". ").append(notes.get(i)).append("\n");
                        }
                        return sb.toString().stripTrailing();
                    }
                });

        return List.of(noteAdd, noteList);
    }

    @Override
    public List<AgentLibrary.AgentDef> agents(ExtensionContext ctx) {
        return List.of(new AgentLibrary.AgentDef(
                "notes",
                "Manage and summarize this session's notes.",
                List.of("note_add", "note_list"),
                "",
                "You are the notes assistant. Use note_add to remember things the user asks you to, and "
                        + "note_list to review them. When asked, summarize the notes concisely. No questions; "
                        + "finish with plain text."));
    }

    @Override
    public List<Command> commands(ExtensionContext ctx) {
        return List.of(new Command(
                "notes",
                "List and summarize your session notes.",
                "Call the note_list tool, then give me a one-line summary of my notes. $ARGS"));
    }

    @Override
    public void onEvent(LoopEvent event, ExtensionContext ctx) {
        if (event.type() == LoopEvent.Type.POST_TOOL_USE && "note_add".equals(event.tool())) {
            ctx.log().info("notes-app: " + added.incrementAndGet() + " note(s) added this process");
        }
    }
}
