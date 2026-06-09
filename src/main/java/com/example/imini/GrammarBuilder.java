package com.example.imini;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds a GBNF grammar that constrains the model's output to EITHER plain text OR one-or-more
 * tagged tool calls whose "name" is restricted to the current tool set and whose "arguments" is
 * valid JSON. This is the "constrained decoding" lever for weak/small models that otherwise emit
 * malformed or hallucinated tool calls.
 *
 * It is OPT-IN (llama.constrain-tools): llama-server with --jinja already applies a tool-call grammar
 * for supported chat templates, and the engine validates every call regardless, so this is a
 * belt-and-suspenders for setups where the template doesn't auto-constrain. Caveat: the free-text
 * branch here disallows a literal '<' in prose answers (it would otherwise be ambiguous with a tool
 * tag), so leave it off unless you need it.
 */
public final class GrammarBuilder {

    private GrammarBuilder() {}

    @SuppressWarnings("unchecked")
    public static String fromTools(List<Map<String, Object>> toolSpecs) {
        List<String> names = new ArrayList<>();
        if (toolSpecs != null) {
            for (Map<String, Object> spec : toolSpecs) {
                Object fn = spec.get("function");
                if (fn instanceof Map<?, ?> m && m.get("name") != null) {
                    names.add(String.valueOf(((Map<String, Object>) m).get("name")));
                }
            }
        }
        if (names.isEmpty()) return null;

        StringBuilder nameRule = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) nameRule.append(" | ");
            nameRule.append("\"\\\"").append(names.get(i)).append("\\\"\"");
        }

        return """
                root        ::= tool-calls | free-text
                free-text   ::= [^<]+
                tool-calls  ::= tool-call+
                tool-call   ::= "<tool_call>" ws tc ws "</tool_call>" ws
                tc          ::= "{" ws "\\"name\\"" ws ":" ws tool-name ws "," ws "\\"arguments\\"" ws ":" ws object ws "}"
                tool-name   ::= %NAMES%
                object      ::= "{" ws ( pair ( ws "," ws pair )* )? ws "}"
                pair        ::= string ws ":" ws value
                array       ::= "[" ws ( value ( ws "," ws value )* )? ws "]"
                value       ::= object | array | string | number | "true" | "false" | "null"
                string      ::= "\\"" ( [^"\\\\] | "\\\\" . )* "\\""
                number      ::= "-"? ( [0-9] | [1-9] [0-9]* ) ( "." [0-9]+ )? ( [eE] [-+]? [0-9]+ )?
                ws          ::= [ \\t\\n]*
                """.replace("%NAMES%", nameRule.toString());
    }
}
