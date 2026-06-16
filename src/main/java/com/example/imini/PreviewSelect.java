package com.example.imini;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure parsing for hunk-level approval. A staged preview's hunks are the individual edits that compose
 * it (each {@code apply_patch} edit is independently applicable). A selection spec like {@code "0,2,3"}
 * (or blank / {@code "all"}) picks which hunks to apply or discard; out-of-range and malformed indices
 * are ignored so the harness never applies something the user didn't mean.
 */
public final class PreviewSelect {

    private PreviewSelect() {}

    /**
     * Parse a comma/space-separated hunk selection over {@code n} hunks into a sorted set of valid
     * indices. Blank, null, or "all" selects every hunk (0..n-1). Ranges like {@code 1-3} are supported.
     */
    public static Set<Integer> parse(String spec, int n) {
        Set<Integer> all = new LinkedHashSet<>();
        for (int i = 0; i < n; i++) all.add(i);
        if (spec == null) return all;
        String s = spec.trim().toLowerCase();
        if (s.isEmpty() || s.equals("all") || s.equals("*")) return all;

        Set<Integer> picked = new LinkedHashSet<>();
        for (String part : s.split("[,\\s]+")) {
            if (part.isEmpty()) continue;
            int dash = part.indexOf('-');
            if (dash > 0 && dash < part.length() - 1) {
                Integer a = intOrNull(part.substring(0, dash));
                Integer b = intOrNull(part.substring(dash + 1));
                if (a != null && b != null) {
                    for (int i = Math.min(a, b); i <= Math.max(a, b); i++) {
                        if (i >= 0 && i < n) picked.add(i);
                    }
                }
            } else {
                Integer v = intOrNull(part);
                if (v != null && v >= 0 && v < n) picked.add(v);
            }
        }
        return picked;
    }

    private static Integer intOrNull(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Sublist of items at the given indices (in ascending order). Pure. */
    public static <T> List<T> pick(List<T> items, Set<Integer> idx) {
        java.util.List<T> out = new java.util.ArrayList<>();
        for (int i = 0; i < items.size(); i++) if (idx.contains(i)) out.add(items.get(i));
        return out;
    }
}
