package org.theushen.construct.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import net.fabricmc.loader.api.FabricLoader;

public final class ConstructKeyConfig {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("construct.json");

    private static String apiKey = "";

    private ConstructKeyConfig() {
    }

    public static void load() {
        if (!Files.exists(FILE)) {
            save();
            return;
        }

        try {
            String raw = Files.readString(FILE, StandardCharsets.UTF_8);
            JsonNode root = MAPPER.readTree(raw);
            apiKey = root.path("apiKey").asText("");
        } catch (IOException e) {
            apiKey = "";
        }
    }

    public static void save() {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("apiKey", apiKey == null ? "" : apiKey);
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, json, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    public static String getKey() {
        return apiKey == null ? "" : apiKey;
    }

    public static void setKey(String key) {
        apiKey = key == null ? "" : key.trim();
        save();
    }
}