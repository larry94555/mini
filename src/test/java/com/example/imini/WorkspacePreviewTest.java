package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pure workspace import-preview classification + summary. */
class WorkspacePreviewTest {

    @Test
    void classifySetting() {
        assertEquals("new", WorkspacePreview.classifySetting(null, "v"));
        assertEquals("unchanged", WorkspacePreview.classifySetting("v", "v"));
        assertEquals("changed", WorkspacePreview.classifySetting("v", "w"));
        assertEquals("changed", WorkspacePreview.classifySetting("", "w"));
        assertEquals("unchanged", WorkspacePreview.classifySetting("", ""));
    }

    @Test
    @SuppressWarnings("unchecked")
    void summarizeBuildsNestedCountsAndClampsNegatives() {
        Map<String, Object> s = WorkspacePreview.summarize(2, 1, 0, 3, 1, 4);
        Map<String, Object> pack = (Map<String, Object>) s.get("pack");
        assertEquals(2, pack.get("create"));
        assertEquals(1, pack.get("overwrite"));
        assertEquals(0, pack.get("blocked"));
        Map<String, Object> settings = (Map<String, Object>) s.get("settings");
        assertEquals(3, settings.get("new"));
        assertEquals(1, settings.get("changed"));
        assertEquals(4, settings.get("unchanged"));
        assertEquals(true, s.get("dryRun"));

        Map<String, Object> neg = (Map<String, Object>) WorkspacePreview.summarize(-1, -2, -3, -4, -5, -6).get("pack");
        assertEquals(0, neg.get("create"));
    }
}
