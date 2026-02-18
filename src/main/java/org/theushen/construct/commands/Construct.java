package org.theushen.construct.commands;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.theushen.construct.net.ApiKeyPayload;
import org.theushen.construct.utils.AI_Call;
import org.theushen.construct.utils.Progress_Bar;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public final class Construct {
    private static final int MAX_KEY_LENGTH = 256;
    private static final ConcurrentHashMap<UUID, String> PLAYER_API_KEYS = new ConcurrentHashMap<>();
    private static boolean registered = false;

    private Construct() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        PayloadTypeRegistry.playC2S().register(ApiKeyPayload.ID, ApiKeyPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(ApiKeyPayload.ID, (payload, context) -> {
            String apiKey = payload.apiKey() == null ? "" : payload.apiKey().trim();
            context.server().execute(() -> {
                ServerPlayerEntity player = context.player();
                if (apiKey.isBlank()) {
                    PLAYER_API_KEYS.remove(player.getUuid());
                    player.sendMessage(Text.literal("Construct: API key cleared."), false);
                    return;
                }
                if (apiKey.length() > MAX_KEY_LENGTH) {
                    player.sendMessage(Text.literal("Construct: API key is too long (max " + MAX_KEY_LENGTH + ")."), false);
                    return;
                }

                PLAYER_API_KEYS.put(player.getUuid(), apiKey);
                player.sendMessage(Text.literal("Construct: API key saved for this session."), false);
            });
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                CommandManager.literal("construct")
                        .then(CommandManager.argument("building", StringArgumentType.greedyString())
                                .executes(context -> {
                                    ServerCommandSource source = context.getSource();
                                    String building = StringArgumentType.getString(context, "building");
                                    String apiKey = resolveApiKey(source);

                                    if (apiKey == null || apiKey.isBlank()) {
                                        source.sendError(Text.literal("No API key found. Open /constructapikey in client and save your key."));
                                        return 0;
                                    }

                                    History.Entry entry = History.start(source, building);
                                    Progress_Bar.start(source, "Construct");
                                    Progress_Bar.update(source, 0.05, "queued");

                                    source.sendFeedback(() -> Text.literal("Constructing: " + building), false);

                                    CompletableFuture.runAsync(() -> {
                                        try {
                                            History.updateStatus(entry, History.Status.CALLING_AI, null);
                                            source.getServer().execute(() -> Progress_Bar.update(source, 0.25, "calling AI"));

                                            AI_Call aiCall = new AI_Call(apiKey);
                                            byte[] schemBytes = aiCall.generateSchemBytes(building);

                                            History.finishSuccess(entry, schemBytes.length);

                                            source.getServer().execute(() -> {
                                                Progress_Bar.update(source, 0.95, "received schem");
                                                Progress_Bar.finish(source, "Done");
                                                source.sendFeedback(
                                                        () -> Text.literal("Schem received: " + schemBytes.length + " bytes"),
                                                        false);
                                            });
                                        } catch (Exception exception) {
                                            History.finishError(entry, exception.getMessage());

                                            source.getServer().execute(() -> {
                                                Progress_Bar.finish(source, "Failed");
                                                source.sendError(Text.literal("Failed to generate schem: " + exception.getMessage()));
                                            });
                                        }
                                    });

                                    return 1;
                                }))));
    }

    private static String resolveApiKey(ServerCommandSource source) {
        if (source.getEntity() instanceof ServerPlayerEntity player) {
            String playerKey = PLAYER_API_KEYS.get(player.getUuid());
            if (playerKey != null && !playerKey.isBlank()) {
                return playerKey;
            }
        }

        return AI_Call.resolveDefaultApiKey();
    }
}
