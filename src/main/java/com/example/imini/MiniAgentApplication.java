package com.example.imini;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point. Starting this app will:
 *   1. launch llama-server (LlamaServerManager @PostConstruct),
 *   2. wait until the model is ready,
 *   3. expose POST /ask for you to drive the agent loop.
 */
@SpringBootApplication
public class MiniAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(MiniAgentApplication.class, args);
    }
}
