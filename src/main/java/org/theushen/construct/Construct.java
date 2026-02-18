package org.theushen.construct;

import java.util.concurrent.CompletableFuture;

import org.theushen.construct.utils.AI_Call;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

public class Construct implements ModInitializer {

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                CommandManager.literal("construct")
                        .then(CommandManager.argument("building", StringArgumentType.greedyString())
                                .executes(context -> {
                                    ServerCommandSource source = context.getSource();
                                    String building = StringArgumentType.getString(context, "building");

                                    source.sendFeedback(() -> Text.literal("Constructing: " + building), false);

                                    CompletableFuture.runAsync(() -> {
                                        try {
                                            AI_Call aiCall = new AI_Call();
                                            byte[] schemBytes = aiCall.generateSchemBytes(building);

                                            source.getServer().execute(() -> source.sendFeedback(
                                                    () -> Text.literal("Schem received: " + schemBytes.length + " bytes"),
                                                    false));
                                        } catch (Exception exception) {
                                            source.getServer().execute(() -> source.sendError(
                                                    Text.literal("Failed to generate schem: " + exception.getMessage())));
                                        }
                                    });

                                    return 1;
                                }))));
    }
}
