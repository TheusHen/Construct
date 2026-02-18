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
                            openKeyScreen();
                            return 1;
                        })
                        .then(ClientCommandManager.literal("clear")
                                .executes(context -> {
                                    ConstructKeyConfig.setKey("");
                                    sendApiKeyToServer("");
                                    return 1;
                                }))));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            shouldResendOnTick = true;
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!shouldResendOnTick) return;
            shouldResendOnTick = false;

            String key = ConstructKeyConfig.getKey();
            if (key != null && !key.isBlank()) {
                sendApiKeyToServer(key);
            }
        });

        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.player == null) return;

            String key = ConstructKeyConfig.getKey();
            boolean set = key != null && !key.isBlank();

            int x = 6;
            int y = 6;
            drawContext.drawText(client.textRenderer,
                    "Construct API: " + (set ? "set" : "unset"),
                    x, y, 0xFFFFFF, true);
        });
    }

    private static void openKeyScreen() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        client.execute(() -> client.setScreen(new ConstructApiKeyScreen(client.currentScreen)));
    }

    private static void sendApiKeyToServer(String key) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() == null) {
            showClientMessage("Construct: join a world/server first.");
            return;
        }
        if (!ClientPlayNetworking.canSend(ApiKeyPayload.ID)) {
            showClientMessage("Construct: server does not support API key sync.");
            return;
        }

        ClientPlayNetworking.send(new ApiKeyPayload(key));

        if (key.isBlank()) {
            showClientMessage("Construct: API key cleared.");
        } else {
            showClientMessage("Construct: API key sent to server.");
        }
    }

    static void saveAndSend(String key) {
        ConstructKeyConfig.setKey(key);
        sendApiKeyToServer(key);
    }

    private static void showClientMessage(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(message), false);
        }
    }
}
