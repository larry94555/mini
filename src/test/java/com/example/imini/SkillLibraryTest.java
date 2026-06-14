package com.example.imini;

import com.example.imini.SkillLibrary.Skill;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure skill parsing, indexing, selection, and formatting. */
class SkillLibraryTest {

    @Test
    void parsesFrontMatterNameDescriptionAndBody() {
        Skill s = SkillLibrary.parse(
                "---\nname: commit-message\ndescription: Write a commit from a diff.\n---\n"
                        + "Step 1. Use type(scope): subject.\nStep 2. Explain why.", "fallback");
        assertEquals("commit-message", s.name());
        assertEquals("Write a commit from a diff.", s.description());
        assertTrue(s.body().startsWith("Step 1."));
    }

    @Test
    void fallsBackToProvidedNameWhenNoFrontMatter() {
        Skill s = SkillLibrary.parse("Just some instructions.", "my-skill");
        assertEquals("my-skill", s.name());
        assertEquals("", s.description());
        assertEquals("Just some instructions.", s.body());
    }

    @Test
    void indexListsNamesAndDescriptions() {
        String idx = SkillLibrary.index(List.of(
                new Skill("a", "does A", ""),
                new Skill("b", "", "")));
        assertTrue(idx.contains("- a: does A"));
        assertTrue(idx.contains("- b: (no description)"));
    }

    @Test
    void selectRanksByLexicalOverlapAndDropsNonMatches() {
        List<Skill> skills = List.of(
                new Skill("commit-message", "Write a git commit message from a diff.", ""),
                new Skill("python-setup", "Create a Python virtualenv and install deps.", ""));
        List<Skill> hit = SkillLibrary.select(skills, "help me write a git commit message", 2);
        assertEquals("commit-message", hit.get(0).name());
        assertTrue(SkillLibrary.select(skills, "xyzzy nonsense", 2).isEmpty());
    }

    @Test
    void formatWrapsBodyAndCaps() {
        Skill s = new Skill("demo", "d", "0123456789ABCDEF");
        assertTrue(SkillLibrary.format(s, 0).startsWith("--- Skill: demo ---\n"));
        String capped = SkillLibrary.format(s, 5);
        assertTrue(capped.contains("01234"));
        assertTrue(capped.contains("(truncated)"));
    }

    @Test
    void mergeLetsLocalOverrideRemoteAndEarlierRepoWin() {
        List<Skill> local = List.of(new Skill("commit-message", "local", ""), new Skill("readme", "r", ""));
        List<Skill> repoA = List.of(new Skill("commit-message", "remote", ""), new Skill("deploy", "A", ""));
        List<Skill> repoB = List.of(new Skill("deploy", "B", ""), new Skill("lint", "L", ""));
        List<Skill> merged = SkillLibrary.merge(List.of(local, repoA, repoB));
        assertEquals(List.of("commit-message", "readme", "deploy", "lint"),
                merged.stream().map(Skill::name).toList());
        assertEquals("local", byName(merged, "commit-message").description());  // local wins
        assertEquals("A", byName(merged, "deploy").description());              // earlier repo wins
    }

    @Test
    void repoSlugDerivesSafeNamesFromUrls() {
        assertEquals("bar", SkillLibrary.repoSlug("https://github.com/foo/bar.git"));
        assertEquals("baz", SkillLibrary.repoSlug("git@github.com:foo/baz.git"));
        assertEquals("skills-repo", SkillLibrary.repoSlug("https://example.com/team/skills-repo/"));
        assertEquals("repo", SkillLibrary.repoSlug(""));
        assertEquals("repo", SkillLibrary.repoSlug(null));
    }

    @Test
    void splitRepoSpecSeparatesUrlFromPinnedRef() {
        assertArrayEquals(new String[]{"https://x/y.git", "v1.2"},
                SkillLibrary.splitRepoSpec("https://x/y.git#v1.2"));
        assertArrayEquals(new String[]{"https://x/y.git", ""}, SkillLibrary.splitRepoSpec("https://x/y.git"));
        assertArrayEquals(new String[]{"", ""}, SkillLibrary.splitRepoSpec(null));
    }

    private static Skill byName(List<Skill> skills, String name) {
        return skills.stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }
}
