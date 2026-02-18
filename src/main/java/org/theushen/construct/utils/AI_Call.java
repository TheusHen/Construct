// Thanks for HackClub, https://ai.hackclub.com for democratic AI API for students :)
package org.theushen.construct.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.Base64;

public class AI_Call {

    private final hackclub_api api;

    public AI_Call() {
        this.api = new hackclub_api(resolveDefaultApiKey());
    }

    public AI_Call(String apiKey) {
        this.api = new hackclub_api(apiKey);
    }

    public static String resolveDefaultApiKey() {
        String key = System.getProperty("construct.apiKey");
        if (key == null || key.isBlank()) {
            key = System.getenv("API_KEY");
        }
        if (key == null || key.isBlank()) {
            return null;
        }
        return key.trim();
    }

    public byte[] generateSchemBytes(String BuildingRequest) throws Exception {
        String base64 = api.chat(BuildingRequest);

        base64 = base64.replace("\n", "").replace("\r", "").trim();

        return Base64.getDecoder().decode(base64);
    }

    public String generateSchemBase64(String BuildingRequest) throws Exception {
        byte[] schemBytes = generateSchemBytes(BuildingRequest);
        return Base64.getEncoder().encodeToString(schemBytes);
    }

    private static class hackclub_api {
        private static final String ENDPOINT =
                "https://ai.hackclub.com/proxy/v1/chat/completions";

        private static final ObjectMapper MAPPER = new ObjectMapper();

        private final HttpClient http;
        private final String apiKey;
        private final String systemPrompt;

        public hackclub_api(String apiKey) {
            this.apiKey = apiKey == null ? "" : apiKey.trim();
            if (this.apiKey == null || this.apiKey.isBlank()) {
                throw new IllegalStateException("API key is missing");
            }
            this.systemPrompt = readResourceText("Prompt.txt");
            this.http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(120)) // Huge, for testing, first time using HackClub AI
                    .build();
        }

        // Seriously? Java for Http? :(
        public String chat(String userMessage) throws IOException, InterruptedException {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", "z-ai/glm-4.6"); // Maybe change that model à l'avenir

            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));
            messages.add(Map.of("role", "user", "content", userMessage));
            payload.put("messages", messages);

            String jsonBody = MAPPER.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT))
                    .timeout(Duration.ofSeconds(100))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() / 100 != 2) {
                throw new IOException("HackClub AI HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonNode root = MAPPER.readTree(response.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");

            if (content.isMissingNode() || content.isNull()) {
                throw new IOException("Missing content in response: " + response.body());
            }

            return content.asText();
        }

        private static String readResourceText(String resource) {
            ClassLoader classLoader = AI_Call.class.getClassLoader();
            try (InputStream inputStream = classLoader.getResourceAsStream(resource)) {
                if (inputStream == null) {
                    throw new IllegalStateException("Resource not found: " + resource);
                }
                byte[] bytes = inputStream.readAllBytes();
                return new String(bytes, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException("Error reading resource: " + resource, e);
            }
        }
    }
}
