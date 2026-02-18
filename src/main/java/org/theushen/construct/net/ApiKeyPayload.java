package org.theushen.construct.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record ApiKeyPayload(String apiKey) implements CustomPayload {
    public static final Id<ApiKeyPayload> ID = new Id<>(Identifier.of("construct", "api_key_update"));
    public static final PacketCodec<RegistryByteBuf, ApiKeyPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, ApiKeyPayload::apiKey, ApiKeyPayload::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
