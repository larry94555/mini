package com.example.imini;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides whether the configured model can accept images. Vision is enabled when explicitly configured
 * ({@code model.vision-enabled=true}) OR the llama-server reports a vision capability via /props
 * (best-effort). When neither holds, image input degrades gracefully (the image is dropped with a note)
 * rather than erroring -- many local llama.cpp builds are text-only.
 */
@Component
public class VisionSupport {

    @Value("${model.vision-enabled:false}") private boolean configured;

    private final LlamaClient llama;

    public VisionSupport(LlamaClient llama) {
        this.llama = llama;
    }

    /** True if images may be sent to the model. */
    public boolean enabled() {
        return configured || llama.serverVision();
    }

    public boolean configuredFlag() { return configured; }
}
