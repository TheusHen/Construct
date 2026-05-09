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

    private static String geminiKey = "";
    private static String hackClubKey = "";

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
            geminiKey = root.path("geminiKey").asText("");
            hackClubKey = root.path("hackClubKey").asText("");
            if (geminiKey.isBlank()) {
                geminiKey = root.path("apiKey").asText("");
            }
        } catch (IOException e) {
            geminiKey = "";
            hackClubKey = "";
        }
    }

    public static void save() {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("geminiKey", geminiKey == null ? "" : geminiKey);
            root.put("hackClubKey", hackClubKey == null ? "" : hackClubKey);
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, json, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    public static String getGeminiKey() {
        return geminiKey == null ? "" : geminiKey;
    }

    public static String getHackClubKey() {
        return hackClubKey == null ? "" : hackClubKey;
    }

    public static void setGeminiKey(String key) {
        geminiKey = key == null ? "" : key.trim();
        save();
    }

    public static void setHackClubKey(String key) {
        hackClubKey = key == null ? "" : key.trim();
        save();
    }

    public static void setKeys(String newHackClubKey, String newGeminiKey) {
        hackClubKey = newHackClubKey == null ? "" : newHackClubKey.trim();
        geminiKey = newGeminiKey == null ? "" : newGeminiKey.trim();
        save();
    }
}
