package com.example.imini;

import com.example.imini.SkillManifest.Entry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure registry manifest: hashing, hash verification, lexical search, and tolerant parsing. */
class SkillManifestTest {

    @Test
    void sha256MatchesAKnownVector() {
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                SkillManifest.sha256("hello"));
    }

    @Test
    void matchesVerifiesHashAndTreatsUnpinnedAsAccepted() {
        String h = SkillManifest.sha256("body");
        assertTrue(SkillManifest.matches(new Entry("a", "d", "s", "1", h), "body"));
        assertFalse(SkillManifest.matches(new Entry("a", "d", "s", "1", h), "tampered"));
        assertTrue(SkillManifest.matches(new Entry("a", "d", "s", "1", ""), "anything"), "unpinned -> accept");
        assertFalse(SkillManifest.matches(null, "x"));
    }

    @Test
    void searchRanksByLexicalOverlapAndDropsNonMatches() {
        List<Entry> es = List.of(
                new Entry("commit-message", "write a git commit from a diff", "a", "", ""),
                new Entry("python-venv", "create a python virtualenv", "b", "", ""));
        assertEquals("commit-message",
                SkillManifest.search(es, "help write a git commit", 2).get(0).name());
        assertTrue(SkillManifest.search(es, "zzzz nonsense", 2).isEmpty());
    }

    @Test
    void parsesArrayAndSkillsObjectFormsTolerantly() {
        List<Entry> arr = SkillManifest.parse(
                "[{\"name\":\"a\",\"description\":\"d\",\"source\":\"a/SKILL.md\",\"sha256\":\"h\"}]");
        assertEquals(1, arr.size());
        assertEquals("a", arr.get(0).name());
        assertEquals("h", arr.get(0).sha256());

        List<Entry> obj = SkillManifest.parse("{\"skills\":[{\"name\":\"b\"},{\"description\":\"no name\"}]}");
        assertEquals(1, obj.size(), "entries without a name are skipped");
        assertEquals("b", obj.get(0).name());
        assertEquals("", obj.get(0).source());

        assertTrue(SkillManifest.parse("not json").isEmpty());
        assertTrue(SkillManifest.parse("").isEmpty());
    }
}
