package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure plugin-registry index parsing + lookup/search. */
class PluginRegistryTest {

    private static final String OBJECT_FORM = "{\"format\":\"imini-registry/1\",\"name\":\"demo\",\"packs\":["
            + "{\"name\":\"web-tools\",\"version\":\"2\",\"description\":\"web helpers\",\"url\":\"https://x/web.json\",\"sha256\":\"abc\"},"
            + "{\"name\":\"db-tools\",\"description\":\"database review\",\"url\":\"https://x/db.json\"},"
            + "{\"name\":\"no-url\"},"                               // skipped: no url
            + "{\"description\":\"no name\",\"url\":\"https://x/z.json\"}" // skipped: no name
            + "]}";

    @Test
    void parsesObjectFormAndSkipsIncompleteEntries() {
        List<PluginRegistry.Listing> ls = PluginRegistry.parse(OBJECT_FORM);
        assertEquals(2, ls.size());
        PluginRegistry.Listing web = ls.get(0);
        assertEquals("web-tools", web.name());
        assertEquals("2", web.version());
        assertEquals("https://x/web.json", web.url());
        assertEquals("abc", web.sha256());
        assertEquals("", ls.get(1).sha256()); // unpinned -> empty, not null
    }

    @Test
    void parsesTopLevelArrayForm() {
        List<PluginRegistry.Listing> ls = PluginRegistry.parse("[{\"name\":\"a\",\"url\":\"https://x/a.json\"}]");
        assertEquals(1, ls.size());
        assertEquals("a", ls.get(0).name());
    }

    @Test
    void parseIsNeverThrowingOnGarbage() {
        assertEquals(0, PluginRegistry.parse("not json").size());
        assertEquals(0, PluginRegistry.parse(null).size());
        assertEquals(0, PluginRegistry.parse("").size());
        assertEquals(0, PluginRegistry.parse("{\"packs\":\"oops\"}").size());
    }

    @Test
    void byNameIsCaseInsensitive() {
        List<PluginRegistry.Listing> ls = PluginRegistry.parse(OBJECT_FORM);
        assertNotNull(PluginRegistry.byName(ls, "WEB-TOOLS"));
        assertEquals("https://x/db.json", PluginRegistry.byName(ls, "db-tools").url());
        assertNull(PluginRegistry.byName(ls, "missing"));
        assertNull(PluginRegistry.byName(ls, null));
    }

    @Test
    void searchRanksByOverlapAndPassesThroughBlankQuery() {
        List<PluginRegistry.Listing> ls = PluginRegistry.parse(OBJECT_FORM);
        List<PluginRegistry.Listing> hits = PluginRegistry.search(ls, "database", 5);
        assertTrue(!hits.isEmpty() && hits.get(0).name().equals("db-tools"));
        assertEquals(2, PluginRegistry.search(ls, "", 5).size()); // blank -> passthrough (capped at k)
        assertEquals(0, PluginRegistry.search(null, "x", 5).size());
    }
}
