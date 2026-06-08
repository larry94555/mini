package com.example.imini;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Launches and supervises llama-server. This is your original @PostConstruct with three changes:
 *
 *  1. Logs go to a file instead of inheritIO(). inheritIO() also wires the child's STDIN to the
 *     JVM's stdin, which then fights the PermissionGate for keyboard input. Redirecting output to
 *     a file keeps the console clean for permission prompts and avoids the stdin tug-of-war.
 *  2. waitUntilReady() polls /health so the first request doesn't race the model load.
 *  3. @PreDestroy kills the child when the Spring context shuts down.
 */
@Component
public class LlamaServerManager {

    private Process llamaProcess;

    @PostConstruct
    public void startLlamaServer() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "llama-server.exe",
                    "-hf", "Qwen/Qwen2.5-3B-Instruct-GGUF:Q4_K_M",
                    "--host", "0.0.0.0",
                    "--port", "8081",
                    "--ctx-size", "8192",
                    "--threads", "8",
                    "-ngl", "0",
                    "--alias", "qwen2.5-3b-instruct",
                    "--jinja");           // <-- enables the model's chat template, incl. tool calling

            pb.redirectOutput(new File("llama-server.log"));
            pb.redirectError(new File("llama-server.log"));
            this.llamaProcess = pb.start();
            System.out.println("llama-server starting on port 8081 (logs -> llama-server.log)...");
            waitUntilReady();
        } catch (IOException e) {
            System.err.println("Failed to start llama-server: " + e.getMessage());
        }
    }

    private void waitUntilReady() {
        HttpClient http = HttpClient.newHttpClient();
        for (int i = 0; i < 120; i++) {
            try {
                HttpResponse<String> r = http.send(
                        HttpRequest.newBuilder(URI.create("http://localhost:8081/health")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    System.out.println("llama-server is ready.");
                    return;
                }
            } catch (Exception ignore) {
                // not up yet
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        System.out.println("llama-server not ready after 120s; check llama-server.log");
    }

    @PreDestroy
    public void stop() {
        if (llamaProcess != null && llamaProcess.isAlive()) {
            llamaProcess.destroy();
            System.out.println("llama-server stopped.");
        }
    }
}
