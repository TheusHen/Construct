package org.theushen.construct.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponse;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AI_Call {
    private static final Logger LOGGER = LoggerFactory.getLogger(AI_Call.class);

    /**
     * Result of a schematic generation attempt.
     * If {@code isFallback} is {@code true}, the AI failed and a procedural house was placed instead.
     * {@code diagnosticReport} contains a human-readable explanation of every step that failed.
     */
    public record SchemResult(byte[] bytes, boolean isFallback, String diagnosticReport) {
        public static SchemResult success(byte[] bytes) {
            return new SchemResult(bytes, false, null);
        }

        public static SchemResult fallback(byte[] bytes, String report) {
            return new SchemResult(bytes, true, report);
        }
    }
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String DEFAULT_MODEL = "gemini-2.5-flash";
    public static final String GEMINI_3_MODEL = "gemini-3.0";

    private static final Pattern BASE64_CHUNK_PATTERN = Pattern.compile("([A-Za-z0-9+/=_\\-\\s]{128,})");
    private static final Pattern CODE_FENCE_PATTERN = Pattern.compile("```(?:[A-Za-z0-9_-]+)?\\s*([\\s\\S]*?)```");
    private static final Pattern DATA_URI_PATTERN = Pattern.compile("base64,([A-Za-z0-9+/=_\\-\\s]{64,})", Pattern.CASE_INSENSITIVE);

    private static final int MAX_DIMENSION = 32;
    private static final int MAX_BLOCKS = 32 * 32 * 32;

    private final GeminiApi api;

    public AI_Call() {
        this(resolveDefaultApiKey(), resolveDefaultModel());
    }

    public AI_Call(String apiKey) {
        this(apiKey, resolveDefaultModel());
    }

    public AI_Call(String apiKey, String model) {
        this.api = new GeminiApi(apiKey, normalizeModel(model));
    }

    public static String resolveDefaultApiKey() {
        String key = System.getProperty("construct.apiKey");
        if (key == null || key.isBlank()) {
            key = System.getenv("GEMINI_API_KEY");
        }
        if (key == null || key.isBlank()) {
            key = System.getenv("GOOGLE_API_KEY");
        }
        if (key == null || key.isBlank()) {
            key = System.getenv("API_KEY");
        }
        if (key == null || key.isBlank()) {
            return null;
        }
        return key.trim();
    }

    public static String resolveDefaultModel() {
        String model = System.getProperty("construct.model");
        if (model == null || model.isBlank()) {
            model = System.getenv("GEMINI_MODEL");
        }
        if (model == null || model.isBlank()) {
            model = System.getenv("GOOGLE_GEMINI_MODEL");
        }
        String normalized = normalizeModel(model);
        return normalized == null ? DEFAULT_MODEL : normalized;
    }

    public static String normalizeModel(String model) {
        if (model == null) {
            return null;
        }
        String trimmed = model.trim();
        if (trimmed.isBlank()) {
            return null;
        }

        String token = trimmed.toLowerCase(Locale.ROOT);
        if (token.equals("2.5") || token.equals("2.5-flash") || token.equals("gemini-2.5-flash")) {
            return DEFAULT_MODEL;
        }
        if (token.equals("3") || token.equals("3.0") || token.equals("gemini-3") || token.equals("gemini-3.0")
                || token.equals("gemini-3.0-flash")) {
            return GEMINI_3_MODEL;
        }
        if (token.startsWith("gemini-")) {
            return trimmed;
        }
        return null;
    }

    public SchemResult generateSchemBytes(String buildingRequest) throws Exception {
        long t0 = System.nanoTime();
        LOGGER.info("AI_Call start: request='{}'", oneLine(buildingRequest));

        StringBuilder diag = new StringBuilder();
        diag.append("Request: '").append(oneLine(buildingRequest)).append("'\n");
        diag.append("Model: ").append(api.model).append("\n");

        Exception lastError = null;
        try {
            String prompt = buildPrompt(buildingRequest);
            diag.append("Prompt: systemPromptChars=").append(api.systemPrompt.length())
                    .append(", userPromptChars=").append(prompt.length()).append("\n");

            String raw = api.chat(prompt);
            int rawChars = raw == null ? 0 : raw.length();
            LOGGER.info("AI_Call response received: rawChars={}", rawChars);
            diag.append("AI response: chars=").append(rawChars).append("\n");

            byte[] jsonSchem = tryDecodeJsonBlueprint(raw, diag);
            if (jsonSchem != null) {
                LOGGER.info("AI_Call json blueprint success: bytes={}, durationMs={}", jsonSchem.length, elapsedMs(t0));
                return SchemResult.success(jsonSchem);
            }

            List<String> base64Candidates = sanitizeCandidates(extractBase64Candidates(raw));
            LOGGER.info("AI_Call base64 candidates: count={}", base64Candidates.size());
            diag.append("Base64 candidates: ").append(base64Candidates.size()).append("\n");

            for (int i = 0; i < base64Candidates.size(); i++) {
                String candidate = base64Candidates.get(i);
                try {
                    byte[] bytes = Base64.getDecoder().decode(candidate);
                    validateSchemPayload(bytes);
                    LOGGER.info("AI_Call base64 success: index={}, bytes={}, durationMs={}", i + 1, bytes.length, elapsedMs(t0));
                    return SchemResult.success(bytes);
                } catch (Exception ex) {
                    String reason = oneLine(ex.getMessage());
                    LOGGER.warn("AI_Call base64 candidate rejected: index={}, reason='{}'", i + 1, reason);
                    diag.append("  base64[").append(i + 1).append("] rejected: ").append(reason).append("\n");
                    lastError = ex;
                }
            }

            throw new IllegalStateException("AI did not return a valid schematic payload.");
        } catch (Exception e) {
            lastError = e;
            LOGGER.warn("AI_Call failed: {}", oneLine(e.getMessage()));
            diag.append("Failure: ").append(oneLine(e.getMessage())).append("\n");
        }

        diag.append("Fallback: procedural house (9x6x9) placed instead of requested build.");
        String diagnosticReport = diag.toString();

        LOGGER.warn("AI_Call fallback activated — full diagnostic:\n{}", diagnosticReport);

        byte[] fallback = buildProceduralHouseFallback(buildingRequest);
        validateSchemPayload(fallback);
        LOGGER.info("AI_Call fallback success: type='procedural-house', bytes={}", fallback.length);
        return SchemResult.fallback(fallback, diagnosticReport);
    }

    public String generateSchemBase64(String buildingRequest) throws Exception {
        SchemResult result = generateSchemBytes(buildingRequest);
        return Base64.getEncoder().encodeToString(result.bytes());
    }

    private static String buildPrompt(String buildingRequest) {
        return buildingRequest;
    }

    private static byte[] tryDecodeJsonBlueprint(String raw, StringBuilder diag) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        List<String> jsonCandidates = extractJsonCandidates(raw);
        LOGGER.info("AI_Call json candidates: count={}", jsonCandidates.size());
        diag.append("JSON candidates: ").append(jsonCandidates.size()).append("\n");

        for (int i = 0; i < jsonCandidates.size(); i++) {
            String candidate = jsonCandidates.get(i);
            try {
                JsonNode root = JSON.readTree(normalizeJsonLike(candidate));
                byte[] bytes = schemFromBlueprint(root);
                validateSchemPayload(bytes);
                LOGGER.info("AI_Call json candidate accepted: index={}, bytes={}", i + 1, bytes.length);
                return bytes;
            } catch (Exception ex) {
                String reason = oneLine(ex.getMessage());
                LOGGER.warn("AI_Call json candidate rejected: index={}, reason='{}'", i + 1, reason);
                diag.append("  json[").append(i + 1).append("] rejected: ").append(reason).append("\n");
            }
        }
        return null;
    }

    private static List<String> extractJsonCandidates(String raw) {
        String trimmed = raw.trim();
        LinkedHashSet<String> out = new LinkedHashSet<>();

        // Closed code fences
        Matcher fenceMatcher = CODE_FENCE_PATTERN.matcher(trimmed);
        while (fenceMatcher.find()) {
            String content = fenceMatcher.group(1);
            if (content != null && content.contains("{")) {
                out.addAll(extractBalancedJsonObjects(content));
            }
        }

        // Unclosed code fence — response was truncated before the closing ```
        if (out.isEmpty()) {
            Matcher openFence = Pattern.compile("```(?:[A-Za-z0-9_-]+)?\\s*([\\s\\S]+)").matcher(trimmed);
            if (openFence.find()) {
                String content = openFence.group(1).trim();
                if (content.contains("{")) {
                    List<String> objects = extractBalancedJsonObjects(content);
                    if (!objects.isEmpty()) {
                        out.addAll(objects);
                    } else {
                        out.add(repairTruncatedJson(content));
                    }
                }
            }
        }

        // Balanced objects anywhere in the raw response.
        out.addAll(extractBalancedJsonObjects(trimmed));

        // Raw JSON with both braces present
        int first = trimmed.indexOf('{');
        int last = trimmed.lastIndexOf('}');
        if (first >= 0 && last > first) {
            out.add(trimmed.substring(first, last + 1));
        }

        // Raw JSON with opening brace but truncated (no closing brace)
        if (first >= 0 && last < first) {
            out.add(repairTruncatedJson(trimmed.substring(first)));
        }

        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            out.add(trimmed);
        }

        return new ArrayList<>(out);
    }

    private static List<String> extractBalancedJsonObjects(String text) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return new ArrayList<>(out);
        }

        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\' && inString) {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }

            if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
                if (depth == 0 && start >= 0) {
                    out.add(text.substring(start, i + 1));
                    start = -1;
                }
            }
        }

        if (out.isEmpty() && start >= 0) {
            out.add(repairTruncatedJson(text.substring(start)));
        }
        return new ArrayList<>(out);
    }

    /** Closes any unclosed JSON arrays/objects caused by a truncated response. */
    private static String repairTruncatedJson(String candidate) {
        if (candidate == null) return "";
        // Strip trailing comma left by the cut-off
        String s = candidate.trim().replaceAll(",\\s*$", "");

        int openBrackets = 0;
        int openBraces = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\' && inString) { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '[') openBrackets++;
            else if (c == ']') openBrackets = Math.max(0, openBrackets - 1);
            else if (c == '{') openBraces++;
            else if (c == '}') openBraces = Math.max(0, openBraces - 1);
        }
        StringBuilder sb = new StringBuilder(s);
        for (int i = 0; i < openBrackets; i++) sb.append(']');
        for (int i = 0; i < openBraces; i++) sb.append('}');
        return sb.toString();
    }

    private static List<String> extractBase64Candidates(String raw) {
        if (raw == null) {
            return List.of();
        }

        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> candidates = new LinkedHashSet<>();

        Matcher dataUriMatcher = DATA_URI_PATTERN.matcher(trimmed);
        while (dataUriMatcher.find()) {
            candidates.add(dataUriMatcher.group(1));
        }

        Matcher fenceMatcher = CODE_FENCE_PATTERN.matcher(trimmed);
        while (fenceMatcher.find()) {
            candidates.add(fenceMatcher.group(1));
        }

        Matcher chunkMatcher = BASE64_CHUNK_PATTERN.matcher(trimmed);
        while (chunkMatcher.find()) {
            candidates.add(chunkMatcher.group(1));
        }

        candidates.add(trimmed);
        return new ArrayList<>(candidates);
    }

    private static List<String> sanitizeCandidates(List<String> candidates) {
        LinkedHashSet<String> sanitized = new LinkedHashSet<>();
        for (String candidate : candidates) {
            String value = sanitizeBase64(candidate);
            if (isLikelyBase64(value)) {
                sanitized.add(value);
            }
        }
        return new ArrayList<>(sanitized);
    }

    private static String sanitizeBase64(String value) {
        if (value == null) {
            return "";
        }

        String out = value.trim();
        int dataIndex = out.toLowerCase().lastIndexOf("base64,");
        if (dataIndex >= 0) {
            out = out.substring(dataIndex + "base64,".length());
        }

        out = out.replaceAll("\\s+", "");
        out = out.replace('-', '+').replace('_', '/');
        out = out.replaceAll("[^A-Za-z0-9+/=]", "");

        if (out.isEmpty()) {
            return out;
        }

        int firstPad = out.indexOf('=');
        if (firstPad >= 0 && firstPad < out.length() - 2) {
            out = out.substring(0, firstPad);
        }

        int mod = out.length() % 4;
        if (mod != 0) {
            out = out + "=".repeat(4 - mod);
        }

        return out;
    }

    private static boolean isLikelyBase64(String value) {
        if (value == null || value.length() < 64) {
            return false;
        }
        if (!value.matches("[A-Za-z0-9+/=]+")) {
            return false;
        }
        int paddingIndex = value.indexOf('=');
        return paddingIndex < 0 || paddingIndex >= value.length() - 2;
    }

    private static String normalizeJsonLike(String input) {
        String text = input == null ? "" : input.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            text = text.substring(start, end + 1);
        }
        text = text.replace('\u201C', '"').replace('\u201D', '"');
        text = text.replace('\u2018', '\'').replace('\u2019', '\'');
        text = text.replaceAll(",\\s*([}\\]])", "$1");
        return text;
    }

    private static byte[] schemFromBlueprint(JsonNode root) throws Exception {
        int width = readPositiveInt(root, "width", "size", "width");
        int height = readPositiveInt(root, "height", "size", "height");
        int length = readPositiveInt(root, "length", "size", "length");

        if (width > MAX_DIMENSION || height > MAX_DIMENSION || length > MAX_DIMENSION) {
            throw new IllegalStateException("blueprint dimensions exceed max " + MAX_DIMENSION);
        }

        int total = width * height * length;
        if (total <= 0 || total > MAX_BLOCKS) {
            throw new IllegalStateException("blueprint block count out of range");
        }

        JsonNode paletteNode = root.path("palette");
        if (!paletteNode.isArray() || paletteNode.isEmpty()) {
            throw new IllegalStateException("blueprint palette must be a non-empty array");
        }
        List<String> palette = new ArrayList<>();
        for (JsonNode node : paletteNode) {
            String state = node.asText("").trim();
            if (state.isBlank()) {
                throw new IllegalStateException("blueprint palette contains blank block state");
            }
            palette.add(state);
        }

        int[] ids = extractBlockIds(root, palette, width, height, length);
        ensureHasNonAirBlocks(ids, paletteIndexForBlock(palette, "minecraft:air"));

        NbtCompound nbt = new NbtCompound();
        nbt.putInt("Version", 2);
        nbt.putInt("DataVersion", 4325);
        nbt.putShort("Width", (short) width);
        nbt.putShort("Height", (short) height);
        nbt.putShort("Length", (short) length);
        nbt.putIntArray("Offset", new int[]{0, 0, 0});

        NbtCompound paletteNbt = new NbtCompound();
        for (int i = 0; i < palette.size(); i++) {
            paletteNbt.putInt(palette.get(i), i);
        }
        nbt.put("Palette", paletteNbt);
        nbt.putInt("PaletteMax", palette.size());
        nbt.putByteArray("BlockData", encodeVarIntArray(ids));

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            NbtIo.writeCompressed(nbt, out);
            return out.toByteArray();
        }
    }

    private static void ensureHasNonAirBlocks(int[] ids, int airIndex) {
        for (int id : ids) {
            if (id != airIndex) {
                return;
            }
        }
        throw new IllegalStateException("blueprint contains only air blocks");
    }

    private static byte[] encodeVarIntArray(int[] values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(values.length);
        for (int value : values) {
            int v = value;
            while ((v & 0xFFFFFF80) != 0) {
                out.write((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            out.write(v & 0x7F);
        }
        return out.toByteArray();
    }

    private static int[] extractBlockIds(JsonNode root, List<String> palette, int width, int height, int length) {
        int total = width * height * length;
        int[] ids = new int[total];
        int airIndex = paletteIndexForBlock(palette, "minecraft:air");
        for (int i = 0; i < total; i++) {
            ids[i] = airIndex;
        }

        JsonNode blocksNode = root.path("blocks");
        if (blocksNode.isArray()) {
            List<Integer> flat = new ArrayList<>();
            flattenIntArray(blocksNode, flat);
            if (!flat.isEmpty()) {
                // Accept full or truncated arrays; missing trailing blocks default to air.
                int count = Math.min(flat.size(), total);
                for (int i = 0; i < count; i++) {
                    ids[i] = checkedPaletteId(flat.get(i), palette.size(), i);
                }
                if (flat.size() < total) {
                    LOGGER.warn("AI_Call blocks array truncated: got={}, expected={}, padding {} with air",
                            flat.size(), total, total - flat.size());
                }
                return ids;
            }

            if (is3dBlockArray(blocksNode, width, height, length)) {
                int idx = 0;
                for (int y = 0; y < height; y++) {
                    JsonNode yLayer = blocksNode.get(y);
                    for (int z = 0; z < length; z++) {
                        JsonNode zRow = yLayer.get(z);
                        for (int x = 0; x < width; x++) {
                            int id = zRow.get(x).asInt(-1);
                            ids[idx++] = checkedPaletteId(id, palette.size(), idx - 1);
                        }
                    }
                }
                return ids;
            }
        }

        JsonNode layersNode = root.path("layers");
        if (layersNode.isArray() && layersNode.size() == height) {
            boolean valid = true;
            outer:
            for (int y = 0; y < height; y++) {
                JsonNode yLayer = layersNode.get(y);
                if (!yLayer.isArray() || yLayer.size() != length) { valid = false; break; }
                for (int z = 0; z < length; z++) {
                    JsonNode zRow = yLayer.get(z);
                    if (!zRow.isArray() || zRow.size() != width) { valid = false; break outer; }
                    for (int x = 0; x < width; x++) {
                        String blockName = zRow.get(x).asText("");
                        int idx = (y * length + z) * width + x;
                        ids[idx] = paletteIndexForBlock(palette, blockName);
                    }
                }
            }
            if (valid) return ids;
        }

        JsonNode placements = root.path("placements");
        if (!placements.isArray()) {
            placements = root.path("voxels");
        }
        if (placements.isArray()) {
            for (JsonNode node : placements) {
                int x = node.path("x").asInt(-1);
                int y = node.path("y").asInt(-1);
                int z = node.path("z").asInt(-1);
                if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= length) {
                    continue;
                }

                int id = -1;
                if (node.has("id")) {
                    id = node.path("id").asInt(-1);
                } else if (node.has("index")) {
                    id = node.path("index").asInt(-1);
                } else if (node.has("paletteId")) {
                    id = node.path("paletteId").asInt(-1);
                } else if (node.has("block")) {
                    id = paletteIndexForBlock(palette, node.path("block").asText(""));
                }

                if (id >= 0 && id < palette.size()) {
                    int idx = (y * length + z) * width + x;
                    ids[idx] = id;
                }
            }
            return ids;
        }

        throw new IllegalStateException("blueprint blocks format unsupported");
    }

    private static int checkedPaletteId(int id, int paletteSize, int index) {
        if (id < 0 || id >= paletteSize) {
            throw new IllegalStateException("blueprint block id out of palette range at index " + index);
        }
        return id;
    }

    private static boolean is3dBlockArray(JsonNode blocksNode, int width, int height, int length) {
        if (!blocksNode.isArray() || blocksNode.size() != height) {
            return false;
        }
        for (int y = 0; y < height; y++) {
            JsonNode yLayer = blocksNode.get(y);
            if (!yLayer.isArray() || yLayer.size() != length) {
                return false;
            }
            for (int z = 0; z < length; z++) {
                JsonNode zRow = yLayer.get(z);
                if (!zRow.isArray() || zRow.size() != width) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void flattenIntArray(JsonNode node, List<Integer> out) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isInt()) {
            out.add(node.asInt());
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                flattenIntArray(child, out);
            }
        }
    }

    private static int paletteIndexForBlock(List<String> palette, String blockName) {
        String target = blockName == null ? "" : blockName.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < palette.size(); i++) {
            String p = palette.get(i).toLowerCase(Locale.ROOT);
            if (p.equals(target) || p.startsWith(target + "[")) {
                return i;
            }
        }
        return 0;
    }

    private static byte[] buildProceduralHouseFallback(String request) throws Exception {
        int width = 9;
        int height = 6;
        int length = 9;
        int total = width * height * length;

        List<String> palette = List.of(
                "minecraft:air",
                "minecraft:cobblestone",
                "minecraft:oak_planks",
                "minecraft:oak_log[axis=y]",
                "minecraft:glass_pane",
                "minecraft:oak_door[half=lower,facing=north,hinge=left,open=false,powered=false]",
                "minecraft:oak_door[half=upper,facing=north,hinge=left,open=false,powered=false]"
        );
        Map<String, Integer> p = new HashMap<>();
        for (int i = 0; i < palette.size(); i++) {
            p.put(palette.get(i), i);
        }

        int[] ids = new int[total];
        int air = p.get("minecraft:air");
        for (int i = 0; i < total; i++) {
            ids[i] = air;
        }

        for (int z = 0; z < length; z++) {
            for (int x = 0; x < width; x++) {
                set(ids, width, length, x, 0, z, p.get("minecraft:cobblestone"));
            }
        }

        for (int y = 1; y <= 3; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    boolean wall = x == 0 || x == width - 1 || z == 0 || z == length - 1;
                    if (!wall) {
                        continue;
                    }
                    int block = ((x == 0 || x == width - 1) && (z == 0 || z == length - 1))
                            ? p.get("minecraft:oak_log[axis=y]")
                            : p.get("minecraft:oak_planks");
                    set(ids, width, length, x, y, z, block);
                }
            }
        }

        int cx = width / 2;
        set(ids, width, length, cx, 1, 0, p.get("minecraft:oak_door[half=lower,facing=north,hinge=left,open=false,powered=false]"));
        set(ids, width, length, cx, 2, 0, p.get("minecraft:oak_door[half=upper,facing=north,hinge=left,open=false,powered=false]"));

        set(ids, width, length, 2, 2, 0, p.get("minecraft:glass_pane"));
        set(ids, width, length, width - 3, 2, 0, p.get("minecraft:glass_pane"));
        set(ids, width, length, 0, 2, 2, p.get("minecraft:glass_pane"));
        set(ids, width, length, 0, 2, length - 3, p.get("minecraft:glass_pane"));
        set(ids, width, length, width - 1, 2, 2, p.get("minecraft:glass_pane"));
        set(ids, width, length, width - 1, 2, length - 3, p.get("minecraft:glass_pane"));

        for (int y = 4; y <= 5; y++) {
            int inset = y - 4;
            for (int z = inset; z < length - inset; z++) {
                for (int x = inset; x < width - inset; x++) {
                    set(ids, width, length, x, y, z, p.get("minecraft:oak_planks"));
                }
            }
        }

        NbtCompound nbt = new NbtCompound();
        nbt.putInt("Version", 2);
        nbt.putInt("DataVersion", 4325);
        nbt.putShort("Width", (short) width);
        nbt.putShort("Height", (short) height);
        nbt.putShort("Length", (short) length);
        nbt.putIntArray("Offset", new int[]{0, 0, 0});

        NbtCompound paletteNbt = new NbtCompound();
        for (int i = 0; i < palette.size(); i++) {
            paletteNbt.putInt(palette.get(i), i);
        }
        nbt.put("Palette", paletteNbt);
        nbt.putInt("PaletteMax", palette.size());
        nbt.putByteArray("BlockData", encodeVarIntArray(ids));

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            NbtIo.writeCompressed(nbt, out);
            return out.toByteArray();
        }
    }

    private static void set(int[] ids, int width, int length, int x, int y, int z, int paletteId) {
        int idx = (y * length + z) * width + x;
        ids[idx] = paletteId;
    }

    private static int readPositiveInt(JsonNode root, String direct, String parent, String child) {
        int value = root.path(direct).asInt(-1);
        if (value <= 0) {
            value = root.path(parent).path(child).asInt(-1);
        }
        if (value <= 0) {
            throw new IllegalStateException("missing or invalid '" + direct + "'");
        }
        return value;
    }

    private static void validateSchemPayload(byte[] bytes) {
        if (bytes == null || bytes.length < 2) {
            throw new IllegalStateException("decoded payload is empty or too small");
        }
        int b0 = bytes[0] & 0xFF;
        int b1 = bytes[1] & 0xFF;
        if (b0 != 0x1F || b1 != 0x8B) {
            throw new IllegalStateException("decoded payload is not gzip (header mismatch)");
        }

        final NbtCompound root;
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            root = NbtIo.readCompressed(in, NbtSizeTracker.ofUnlimitedBytes());
        } catch (Exception e) {
            throw new IllegalStateException("decoded payload is not valid compressed NBT: " + oneLine(e.getMessage()));
        }

        int width = Short.toUnsignedInt(root.getShort("Width").orElse((short) 0));
        int height = Short.toUnsignedInt(root.getShort("Height").orElse((short) 0));
        int length = Short.toUnsignedInt(root.getShort("Length").orElse((short) 0));
        if (width <= 0 || height <= 0 || length <= 0) {
            throw new IllegalStateException("schematic dimensions are invalid");
        }

        NbtCompound palette = root.getCompound("Palette").orElse(new NbtCompound());
        byte[] blockData = root.getByteArray("BlockData").orElse(new byte[0]);
        if (palette.getKeys().isEmpty()) {
            throw new IllegalStateException("schematic palette is empty");
        }
        if (blockData.length == 0) {
            throw new IllegalStateException("schematic BlockData is empty");
        }
    }

    private static String oneLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private static long elapsedMs(long startedAtNano) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNano);
    }

    private static class GeminiApi {
        private final Client client;
        private final String systemPrompt;
        private final String model;

        GeminiApi(String apiKey, String model) {
            String key = apiKey == null ? "" : apiKey.trim();
            if (key.isBlank()) {
                throw new IllegalStateException("API key is missing");
            }
            this.client = Client.builder().apiKey(key).build();
            this.systemPrompt = readResourceText("ConstructPrompt.txt");
            String normalized = normalizeModel(model);
            this.model = normalized == null ? DEFAULT_MODEL : normalized;
        }

        String chat(String userMessage) {
            long t0 = System.nanoTime();
            String fullPrompt = systemPrompt + "\n\nUser request: " + userMessage;
            LOGGER.info("AI_Call sending to model='{}': systemPromptChars={}, userMessageChars={}, totalChars={}",
                    model, systemPrompt.length(), userMessage.length(), fullPrompt.length());
            LOGGER.info("=== PROMPT SENT TO AI ===\n{}\n=== END PROMPT ===", fullPrompt);
            GenerateContentResponse response = client.models.generateContent(model, fullPrompt, null);
            String text = response.text();
            LOGGER.info("Gemini response: model='{}', durationMs={}, responseChars={}",
                    model, elapsedMs(t0), text == null ? 0 : text.length());
            if (text != null && !text.isBlank()) {
                LOGGER.info("=== RAW AI RESPONSE ===\n{}\n=== END RESPONSE ===", text);
            } else {
                LOGGER.warn("Gemini returned null or blank response: model='{}'", model);
            }
            return text;
        }

        private static String readResourceText(String resource) {
            ClassLoader classLoader = AI_Call.class.getClassLoader();
            try (InputStream inputStream = classLoader.getResourceAsStream(resource)) {
                if (inputStream == null) {
                    throw new IllegalStateException("Resource not found: " + resource);
                }
                byte[] bytes = inputStream.readAllBytes();
                return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new RuntimeException("Error reading resource: " + resource, e);
            }
        }
    }
}
