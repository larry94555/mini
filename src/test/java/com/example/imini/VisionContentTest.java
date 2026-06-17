package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure multimodal content building + graceful text-only fallback. */
class VisionContentTest {

    @Test
    void dataUrlNormalizesRawBase64AndPassesThrough() {
        assertEquals("data:image/jpeg;base64,AAAA", VisionContent.dataUrl("AAAA", "image/jpeg"));
        assertEquals("data:image/png;base64,AAAA", VisionContent.dataUrl("AAAA", null));   // default type
        assertEquals("data:image/png;base64,XX", VisionContent.dataUrl("data:image/png;base64,XX", null));
        assertNull(VisionContent.dataUrl(null, null));
        assertNull(VisionContent.dataUrl("  ", null));
    }

    @Test
    void noImageReturnsPlainString() {
        Object c = VisionContent.userContent("hello", null, true);
        assertTrue(c instanceof String);
        assertEquals("hello", c);
        assertFalse(VisionContent.isMultimodal(c));
    }

    @Test
    void textOnlyModelDropsImageWithNote() {
        Object c = VisionContent.userContent("hello", "data:image/png;base64,XX", false);
        assertTrue(c instanceof String);
        assertTrue(((String) c).contains("text-only"));
        assertFalse(VisionContent.isMultimodal(c));
    }

    @Test
    void visionModelBuildsPartsArray() {
        Object c = VisionContent.userContent("describe this", "data:image/png;base64,XX", true);
        assertTrue(VisionContent.isMultimodal(c));
        List<?> parts = (List<?>) c;
        assertEquals(2, parts.size());
        Map<?, ?> textPart = (Map<?, ?>) parts.get(0);
        assertEquals("text", textPart.get("type"));
        assertEquals("describe this", textPart.get("text"));
        Map<?, ?> imagePart = (Map<?, ?>) parts.get(1);
        assertEquals("image_url", imagePart.get("type"));
        Map<?, ?> imageUrl = (Map<?, ?>) imagePart.get("image_url");
        assertEquals("data:image/png;base64,XX", imageUrl.get("url"));
    }
}
