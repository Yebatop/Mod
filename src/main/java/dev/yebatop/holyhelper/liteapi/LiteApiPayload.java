package dev.yebatop.holyhelper.liteapi;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;

/**
 * Сырой UTF-8 JSON на канале LiteAPI.
 * <p>
 * Кодек читает буфер целиком и пишет байты без префикса длины: сервер шлёт голую
 * строку, а не строку в формате Minecraft. Именно поэтому здесь нельзя пользоваться
 * {@code buf.writeString} — он добавит VarInt с длиной, и сервер не разберёт пакет.
 */
public record LiteApiPayload(String json) implements CustomPayload {

    public static final Identifier FEATURE_CONTROL_CHANNEL =
            Identifier.of("liteapi", "feature-control");

    public static final CustomPayload.Id<LiteApiPayload> FEATURE_CONTROL =
            new CustomPayload.Id<>(FEATURE_CONTROL_CHANNEL);

    public static final PacketCodec<PacketByteBuf, LiteApiPayload> CODEC = CustomPayload.codecOf(
            (payload, buf) -> buf.writeBytes(payload.json().getBytes(StandardCharsets.UTF_8)),
            buf -> {
                byte[] bytes = new byte[buf.readableBytes()];
                buf.readBytes(bytes);
                return new LiteApiPayload(new String(bytes, StandardCharsets.UTF_8));
            });

    @Override
    public Id<? extends CustomPayload> getId() {
        return FEATURE_CONTROL;
    }
}
