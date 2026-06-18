package com.example.imini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The skip/fold/inline decision for oversized @file references in {@link ContextRefService}. */
class ContextRefFoldTest {

    @Test
    void smallFileIsInlined() {
        assertEquals(ContextRefService.LargeFileAction.INLINE,
                ContextRefService.largeFileAction(10 * 1024, 64, 512, true));
    }

    @Test
    void overInlineCapButFoldableIsFolded() {
        assertEquals(ContextRefService.LargeFileAction.FOLD,
                ContextRefService.largeFileAction(100 * 1024, 64, 512, true));
    }

    @Test
    void overInlineCapSkipsWhenFoldDisabled() {
        assertEquals(ContextRefService.LargeFileAction.SKIP,
                ContextRefService.largeFileAction(100 * 1024, 64, 512, false));
    }

    @Test
    void overFoldCapIsSkippedEvenWhenFoldEnabled() {
        assertEquals(ContextRefService.LargeFileAction.SKIP,
                ContextRefService.largeFileAction(1024 * 1024, 64, 512, true));
    }
}
