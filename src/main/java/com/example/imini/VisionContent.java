package com.example.imini;

import java.util.List;
import java.util.Map;

/**
 * Pure helpers for multimodal (image) input. llama-server's OpenAI-compatible endpoint takes an image as
 * a user message whose {@code content} is an array of parts: a {@code text} part and an {@code image_url}
 * part carrying a {@code data:} URL. This class builds that content deterministically and, when the model
 * is text-only, degrades gracefully -- it drops the image and appends a short note so the turn still runs
 * instead of erroring. Capability detection (is the model vision-capable?) lives in {@link VisionSupport};
 * everything here is dependency-free and testable.
 */
public final class VisionContent {

    private VisionContent() {}

    public static final String NO_VISION_NOTE =
            "\n\n(Note: an image was attached, but this model is text-only, so the image was not included. "
            + "Describe the image in text if you need help with it.)";

    /** Normalize raw base64 (+ optional media type) into a {@code data:} URL; pass through an existing one. */
    public static String dataUrl(String imageBase64OrDataUrl, String mediaType) {
        if (imageBase64OrDataUrl == null || imageBase64OrDataUrl.isBlank()) return null;
        String s = imageBase64OrDataUrl.trim();
        if (s.startsWith("data:")) return s;
        String mt = (mediaType == null || mediaType.isBlank()) ? "image/png" : mediaType.trim();
        return "data:" + mt + ";base64," + s;
    }

    /**
     * The user-message content for a turn. With no image (or a text-only model) returns a plain string
     * (the prompt, plus a note if an image was supplied but unsupported). With an image on a vision model
     * returns the OpenAI parts array [{text}, {image_url}].
     */
    public static Object userContent(String text, String dataUrl, boolean visionEnabled) {
        String prompt = text == null ? "" : text;
        if (dataUrl == null || dataUrl.isBlank()) return prompt;
        if (!visionEnabled) return prompt + NO_VISION_NOTE;
        Map<String, Object> textPart = new java.util.LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", prompt);
        Map<String, Object> imgUrl = new java.util.LinkedHashMap<>();
        imgUrl.put("url", dataUrl);
        Map<String, Object> imagePart = new java.util.LinkedHashMap<>();
        imagePart.put("type", "image_url");
        imagePart.put("image_url", imgUrl);
        return List.of(textPart, imagePart);
    }

    /** True when the content is a multimodal parts array (vs a plain string). */
    public static boolean isMultimodal(Object content) {
        return content instanceof List;
    }
}
