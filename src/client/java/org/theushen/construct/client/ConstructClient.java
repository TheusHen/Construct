package org.theushen.construct.client;

import org.theushen.construct.net.ApiKeyPayload;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

public class ConstructClient implements ClientModInitializer {

    private static volatile boolean shouldResendOnTick = false;

    @Override
    public void onInitializeClient() {
        ConstructKeyConfig.load();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("constructapikey")
                        .executes(context -> {
                            openKeyScreen("hackclub");
                            return 1;
                        })
                        .then(ClientCommandManager.literal("clear")
                                .executes(context -> {
                                    ConstructKeyConfig.setKeys("", "");
                                    sendKeysToServer("", "");
                                    return 1;
                                }))));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("constructgeminikey")
                        .executes(context -> {
                            openKeyScreen("gemini");
                            return 1;
                        })
                        .then(ClientCommandManager.literal("clear")
                                .executes(context -> {
                                    ConstructKeyConfig.setGeminiKey("");
                                    sendKeysToServer(ConstructKeyConfig.getHackClubKey(), "");
                                    return 1;
                                }))));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("constructhackclubkey")
                        .executes(context -> {
                            openKeyScreen("hackclub");
                            return 1;
                        })
                        .then(ClientCommandManager.literal("clear")
                                .executes(context -> {
                                    ConstructKeyConfig.setHackClubKey("");
                                    sendKeysToServer("", ConstructKeyConfig.getGeminiKey());
                                    return 1;
                                }))));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            shouldResendOnTick = true;
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!shouldResendOnTick) return;
            shouldResendOnTick = false;

            String hackClubKey = ConstructKeyConfig.getHackClubKey();
            String geminiKey = ConstructKeyConfig.getGeminiKey();
            if ((hackClubKey != null && !hackClubKey.isBlank()) || (geminiKey != null && !geminiKey.isBlank())) {
                sendKeysToServer(hackClubKey, geminiKey);
            }
        });

        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.player == null) return;

            int x = 6;
            int y = 6;
            drawContext.drawText(client.textRenderer,
                    "Construct HC: " + statusLabel(ConstructKeyConfig.getHackClubKey())
                            + " | Gemini: " + statusLabel(ConstructKeyConfig.getGeminiKey()),
                    x, y, 0xFFFFFF, true);
        });
    }

    private static void openKeyScreen(String focusTarget) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        client.execute(() -> client.setScreen(new ConstructApiKeyScreen(client.currentScreen, focusTarget)));
    }

    private static void sendKeysToServer(String hackClubKey, String geminiKey) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() == null) {
            showClientMessage("Construct: join a world/server first.");
            return;
        }
        if (!ClientPlayNetworking.canSend(ApiKeyPayload.ID)) {
            showClientMessage("Construct: server does not support API key sync.");
            return;
        }

        String safeHackClubKey = hackClubKey == null ? "" : hackClubKey.trim();
        String safeGeminiKey = geminiKey == null ? "" : geminiKey.trim();
        ClientPlayNetworking.send(new ApiKeyPayload(safeGeminiKey, safeHackClubKey));

        showClientMessage("Construct: keys synced. Hack Club="
                + statusLabel(safeHackClubKey) + ", Gemini=" + statusLabel(safeGeminiKey) + ".");
    }

    static void saveAndSend(String hackClubKey, String geminiKey) {
        ConstructKeyConfig.setKeys(hackClubKey, geminiKey);
        sendKeysToServer(hackClubKey, geminiKey);
    }

    private static void showClientMessage(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(message), false);
        }
    }

    private static String statusLabel(String key) {
        return key == null || key.isBlank() ? "unset" : "set";
    }
}
