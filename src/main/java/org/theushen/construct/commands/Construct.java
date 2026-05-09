package org.theushen.construct.commands;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.theushen.construct.net.ApiKeyPayload;
import org.theushen.construct.utils.AI_Call;
import org.theushen.construct.utils.Progress_Bar;
import org.theushen.construct.utils.SchemService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public final class Construct {
    private static final Logger LOGGER = LoggerFactory.getLogger(Construct.class);
    private static final int MAX_KEY_LENGTH = 256;
    private static final ConcurrentHashMap<UUID, AI_Call.ProviderKeys> PLAYER_KEYS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, String> PLAYER_GEMINI_MODELS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, String> PLAYER_HACKCLUB_MODELS = new ConcurrentHashMap<>();
    private static volatile String SERVER_GEMINI_MODEL_OVERRIDE = null;
    private static volatile String SERVER_HACKCLUB_MODEL_OVERRIDE = null;
    private static boolean registered = false;

    private Construct() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        PayloadTypeRegistry.playC2S().register(ApiKeyPayload.ID, ApiKeyPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(ApiKeyPayload.ID, (payload, context) -> context.server().execute(() -> {
            ServerPlayerEntity player = context.player();
            String geminiKey = sanitizeKey(payload.geminiKey());
            String hackClubKey = sanitizeKey(payload.hackClubKey());

            if (geminiKey != null && geminiKey.length() > MAX_KEY_LENGTH) {
                player.sendMessage(Text.literal("Construct: Gemini API key is too long (max " + MAX_KEY_LENGTH + ")."), false);
                LOGGER.warn("Construct Gemini key rejected: player='{}', length={} exceeds max={}",
                        player.getName().getString(), geminiKey.length(), MAX_KEY_LENGTH);
                return;
            }
            if (hackClubKey != null && hackClubKey.length() > MAX_KEY_LENGTH) {
                player.sendMessage(Text.literal("Construct: Hack Club API key is too long (max " + MAX_KEY_LENGTH + ")."), false);
                LOGGER.warn("Construct Hack Club key rejected: player='{}', length={} exceeds max={}",
                        player.getName().getString(), hackClubKey.length(), MAX_KEY_LENGTH);
                return;
            }

            LOGGER.info("Construct key update received: player='{}', hackClubSet={}, geminiSet={}",
                    player.getName().getString(), hackClubKey != null, geminiKey != null);

            if (hackClubKey == null && geminiKey == null) {
                PLAYER_KEYS.remove(player.getUuid());
                player.sendMessage(Text.literal("Construct: session keys cleared."), false);
                LOGGER.info("Construct keys cleared for player='{}'", player.getName().getString());
                return;
            }

            PLAYER_KEYS.put(player.getUuid(), new AI_Call.ProviderKeys(hackClubKey, geminiKey));
            player.sendMessage(Text.literal("Construct: session keys updated. Hack Club="
                    + statusLabel(hackClubKey != null) + ", Gemini=" + statusLabel(geminiKey != null) + "."), false);
            LOGGER.info("Construct keys stored for player='{}' (session-only)", player.getName().getString());
        }));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                CommandManager.literal("construct")
                        .then(CommandManager.literal("model")
                                .executes(context -> {
                                    ServerCommandSource source = context.getSource();
                                    String hackClubModel = resolveHackClubModel(source);
                                    String geminiModel = resolveGeminiModel(source);
                                    source.sendFeedback(() -> Text.literal("Construct models -> Hack Club: "
                                            + hackClubModel + " | Gemini: " + geminiModel), false);
                                    return 1;
                                })
                                .then(CommandManager.argument("name", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            ServerCommandSource source = context.getSource();
                                            String raw = StringArgumentType.getString(context, "name");
                                            String normalized = AI_Call.normalizeGeminiModel(raw);
                                            if (normalized == null) {
                                                source.sendError(Text.literal("Unknown model. Use 2.5, 3, or a full gemini-* model id."));
                                                return 0;
                                            }

                                            if (source.getEntity() instanceof ServerPlayerEntity player) {
                                                PLAYER_GEMINI_MODELS.put(player.getUuid(), normalized);
                                                source.sendFeedback(() -> Text.literal("Construct Gemini model set to: " + normalized), false);
                                                LOGGER.info("Construct Gemini model set: player='{}', model='{}'",
                                                        player.getName().getString(), normalized);
                                            } else {
                                                SERVER_GEMINI_MODEL_OVERRIDE = normalized;
                                                source.sendFeedback(() -> Text.literal("Construct Gemini model set to: " + normalized + " (server override)"), false);
                                                LOGGER.info("Construct Gemini model set: source='server', model='{}'", normalized);
                                            }
                                            return 1;
                                        }))
                                .then(CommandManager.literal("gemini")
                                        .then(CommandManager.argument("name", StringArgumentType.greedyString())
                                                .executes(context -> setGeminiModel(context.getSource(),
                                                        StringArgumentType.getString(context, "name")))))
                                .then(CommandManager.literal("hackclub")
                                        .then(CommandManager.argument("name", StringArgumentType.greedyString())
                                                .executes(context -> setHackClubModel(context.getSource(),
                                                        StringArgumentType.getString(context, "name"))))))
                        .then(CommandManager.argument("building", StringArgumentType.greedyString())
                                .executes(context -> {
                                    ServerCommandSource source = context.getSource();
                                    String building = StringArgumentType.getString(context, "building");
                                    String jobId = UUID.randomUUID().toString().substring(0, 8);
                                    long t0 = System.nanoTime();
                                    AI_Call.ProviderKeys keys = resolveProviderKeys(source);
                                    AI_Call.ProviderModels models = new AI_Call.ProviderModels(
                                            resolveHackClubModel(source),
                                            resolveGeminiModel(source));
                                    String actor = source.getName();

                                    LOGGER.info("Construct job={} accepted: actor='{}', request='{}'",
                                            jobId, actor, oneLine(building));

                                    if (!keys.hasHackClubKey() && !keys.hasGeminiKey()) {
                                        source.sendError(Text.literal("No AI key found. Use /constructhackclubkey and/or /constructgeminikey on the client."));
                                        LOGGER.warn("Construct job={} rejected: actor='{}', reason='missing-api-key'",
                                                jobId, actor);
                                        return 0;
                                    }

                                    History.Entry entry = History.start(source, building);
                                    LOGGER.info("Construct job={} history entry created: status={}",
                                            jobId, entry.status);
                                    Progress_Bar.start(source, "Construct");
                                    Progress_Bar.update(source, 0.05, "queued");

                                    source.sendFeedback(() -> Text.literal("Constructing: " + building), false);
                                    LOGGER.info("Construct job={} queued", jobId);

                                    CompletableFuture.runAsync(() -> {
                                        try {
                                            long stageAiStart = System.nanoTime();
                                            History.updateStatus(entry, History.Status.CALLING_AI, null);
                                            source.getServer().execute(() -> Progress_Bar.update(source, 0.25, "calling AI"));
                                            LOGGER.info("Construct job={} stage=calling_ai start", jobId);

                                            AI_Call aiCall = new AI_Call(keys, models);
                                            AI_Call.SchemResult schemResult = aiCall.generateSchemBytes(building);
                                            byte[] schemBytes = schemResult.bytes();
                                            long stageAiMs = elapsedMs(stageAiStart);
                                            LOGGER.info("Construct job={} stage=calling_ai done: durationMs={}, bytes={}, fallback={}",
                                                    jobId, stageAiMs, schemBytes.length, schemResult.isFallback());

                                            if (schemResult.isFallback()) {
                                                LOGGER.warn("Construct job={} AI FAILED — FALLBACK HOUSE PLACED:\n{}",
                                                        jobId, schemResult.diagnosticReport());
                                            }

                                            source.getServer().execute(() -> {
                                                try {
                                                    long stagePlaceStart = System.nanoTime();
                                                    Progress_Bar.update(source, 0.95, "received schem");

                                                    if (schemResult.isFallback()) {
                                                        source.sendError(Text.literal(
                                                                "[Construct] AI could not generate your build. Placed fallback house instead."));
                                                        String report = schemResult.diagnosticReport();
                                                        if (report != null) {
                                                            for (String line : report.split("\n")) {
                                                                String t = line.trim();
                                                                if (!t.isBlank()) {
                                                                    source.sendError(Text.literal("  " + t));
                                                                }
                                                            }
                                                        }
                                                    }

                                                    if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
                                                        throw new IllegalStateException("Construct placement requires a player context.");
                                                    }

                                                    SchemService.PlaceResult result = SchemService.saveAndPlace(source, player, building, schemBytes);
                                                    History.finishSuccess(entry, schemBytes.length);
                                                    long stagePlaceMs = elapsedMs(stagePlaceStart);
                                                    long totalMs = elapsedMs(t0);

                                                    Progress_Bar.finish(source, "Done");
                                                    source.sendFeedback(
                                                            () -> Text.literal("Schem received: " + schemBytes.length + " bytes"),
                                                            false);
                                                    source.sendFeedback(
                                                            () -> Text.literal("Placed " + result.blocksPlaced() + " blocks in front of you."),
                                                            false);
                                                    source.sendFeedback(
                                                            () -> Text.literal("Saved schematic: " + result.savedPath().toAbsolutePath()),
                                                            false);
                                                    LOGGER.info("Construct job={} stage=place done: durationMs={}, blocksPlaced={}, savedPath='{}'",
                                                            jobId, stagePlaceMs, result.blocksPlaced(), result.savedPath().toAbsolutePath());
                                                    LOGGER.info("Construct job={} completed: totalDurationMs={}", jobId, totalMs);
                                                } catch (Exception placeException) {
                                                    History.finishError(entry, placeException.getMessage());
                                                    Progress_Bar.finish(source, "Failed");
                                                    source.sendError(Text.literal("Failed to place schem: " + placeException.getMessage()));
                                                    LOGGER.error("Construct job={} failed in place stage: {}", jobId, placeException.getMessage(), placeException);
                                                }
                                            });
                                        } catch (Exception exception) {
                                            History.finishError(entry, exception.getMessage());

                                            source.getServer().execute(() -> {
                                                Progress_Bar.finish(source, "Failed");
                                                source.sendError(Text.literal("Failed to generate schem: " + exception.getMessage()));
                                            });
                                            LOGGER.error("Construct job={} failed in ai stage: {}", jobId, exception.getMessage(), exception);
                                        }
                                    });

                                    return 1;
                                }))));

        if (org.theushen.construct.Construct.ENABLE_TEST_COMMAND) {
            CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                    CommandManager.literal("construct-test")
                            .executes(context -> {
                                ServerCommandSource source = context.getSource();
                                if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
                                    source.sendError(Text.literal("This command requires a player context."));
                                    return 0;
                                }

                                try {
                                    LOGGER.info("Construct test command start: player='{}'", player.getName().getString());
                                    SchemService.PlaceResult result = SchemService.placeExampleSquem(source, player);
                                    source.sendFeedback(() -> Text.literal("Construct test success. Placed "
                                            + result.blocksPlaced() + " blocks."), false);
                                    source.sendFeedback(() -> Text.literal("Example file: "
                                            + SchemService.exampleSquemPath().toAbsolutePath()), false);
                                    source.sendFeedback(() -> Text.literal("Saved schematic: "
                                            + result.savedPath().toAbsolutePath()), false);
                                    LOGGER.info("Construct test command complete: player='{}', blocksPlaced={}, examplePath='{}'",
                                            player.getName().getString(), result.blocksPlaced(), SchemService.exampleSquemPath().toAbsolutePath());
                                    return 1;
                                } catch (Exception e) {
                                    source.sendError(Text.literal("Construct test failed: " + e.getMessage()));
                                    LOGGER.error("Construct test command failed: {}", e.getMessage(), e);
                                    return 0;
                                }
                            })));
        }
    }

    private static AI_Call.ProviderKeys resolveProviderKeys(ServerCommandSource source) {
        AI_Call.ProviderKeys defaults = AI_Call.resolveDefaultProviderKeys();
        String hackClubKey = defaults.sanitizedHackClubKey();
        String geminiKey = defaults.sanitizedGeminiKey();

        if (source.getEntity() instanceof ServerPlayerEntity player) {
            AI_Call.ProviderKeys playerKeys = PLAYER_KEYS.get(player.getUuid());
            if (playerKeys != null) {
                if (playerKeys.sanitizedHackClubKey() != null) {
                    hackClubKey = playerKeys.sanitizedHackClubKey();
                }
                if (playerKeys.sanitizedGeminiKey() != null) {
                    geminiKey = playerKeys.sanitizedGeminiKey();
                }
                LOGGER.debug("Construct key resolution: actor='{}', source='player-session-key'", source.getName());
            }
        }

        return new AI_Call.ProviderKeys(hackClubKey, geminiKey);
    }

    private static String resolveGeminiModel(ServerCommandSource source) {
        if (source.getEntity() instanceof ServerPlayerEntity player) {
            String playerModel = PLAYER_GEMINI_MODELS.get(player.getUuid());
            if (playerModel != null && !playerModel.isBlank()) {
                LOGGER.debug("Construct Gemini model resolution: actor='{}', source='player-session-model'",
                        source.getName());
                return playerModel;
            }
        }

        if (SERVER_GEMINI_MODEL_OVERRIDE != null && !SERVER_GEMINI_MODEL_OVERRIDE.isBlank()) {
            LOGGER.debug("Construct Gemini model resolution: actor='{}', source='server-override'",
                    source.getName());
            return SERVER_GEMINI_MODEL_OVERRIDE;
        }

        LOGGER.debug("Construct Gemini model resolution: actor='{}', source='default'",
                source.getName());
        return AI_Call.resolveDefaultModel();
    }

    private static String resolveHackClubModel(ServerCommandSource source) {
        if (source.getEntity() instanceof ServerPlayerEntity player) {
            String playerModel = PLAYER_HACKCLUB_MODELS.get(player.getUuid());
            if (playerModel != null && !playerModel.isBlank()) {
                LOGGER.debug("Construct Hack Club model resolution: actor='{}', source='player-session-model'",
                        source.getName());
                return playerModel;
            }
        }

        if (SERVER_HACKCLUB_MODEL_OVERRIDE != null && !SERVER_HACKCLUB_MODEL_OVERRIDE.isBlank()) {
            LOGGER.debug("Construct Hack Club model resolution: actor='{}', source='server-override'",
                    source.getName());
            return SERVER_HACKCLUB_MODEL_OVERRIDE;
        }

        LOGGER.debug("Construct Hack Club model resolution: actor='{}', source='default'",
                source.getName());
        return AI_Call.resolveDefaultHackClubModel();
    }

    private static int setGeminiModel(ServerCommandSource source, String raw) {
        String normalized = AI_Call.normalizeGeminiModel(raw);
        if (normalized == null) {
            source.sendError(Text.literal("Unknown Gemini model. Use 2.5, 3, or a full gemini-* model id."));
            return 0;
        }

        if (source.getEntity() instanceof ServerPlayerEntity player) {
            PLAYER_GEMINI_MODELS.put(player.getUuid(), normalized);
            source.sendFeedback(() -> Text.literal("Construct Gemini model set to: " + normalized), false);
            LOGGER.info("Construct Gemini model set: player='{}', model='{}'", player.getName().getString(), normalized);
        } else {
            SERVER_GEMINI_MODEL_OVERRIDE = normalized;
            source.sendFeedback(() -> Text.literal("Construct Gemini model set to: " + normalized + " (server override)"), false);
            LOGGER.info("Construct Gemini model set: source='server', model='{}'", normalized);
        }
        return 1;
    }

    private static int setHackClubModel(ServerCommandSource source, String raw) {
        String normalized = AI_Call.normalizeHackClubModel(raw);
        if (normalized == null) {
            source.sendError(Text.literal("Hack Club model cannot be blank."));
            return 0;
        }

        if (source.getEntity() instanceof ServerPlayerEntity player) {
            PLAYER_HACKCLUB_MODELS.put(player.getUuid(), normalized);
            source.sendFeedback(() -> Text.literal("Construct Hack Club model set to: " + normalized), false);
            LOGGER.info("Construct Hack Club model set: player='{}', model='{}'", player.getName().getString(), normalized);
        } else {
            SERVER_HACKCLUB_MODEL_OVERRIDE = normalized;
            source.sendFeedback(() -> Text.literal("Construct Hack Club model set to: " + normalized + " (server override)"), false);
            LOGGER.info("Construct Hack Club model set: source='server', model='{}'", normalized);
        }
        return 1;
    }

    private static String sanitizeKey(String key) {
        if (key == null) {
            return null;
        }
        String trimmed = key.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private static String statusLabel(boolean set) {
        return set ? "set" : "unset";
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
}
