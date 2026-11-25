package com.deruy.plugin.replay.nms;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.MinecraftServer;

import java.util.function.Consumer;

/** 서버가 클라이언트에 보내는 것과 같은 방식(패킷번호+내용)으로 인코딩. Flashback 도 같은 코덱으로 읽는다. */
public final class Codecs {
    public final RegistryAccess access;
    private final StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> game;
    private final StreamCodec<ByteBuf, Packet<? super ClientConfigurationPacketListener>> config;

    public Codecs(MinecraftServer server) {
        this.access = server.registryAccess();
        this.game = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(access)).codec();
        this.config = ConfigurationProtocols.CLIENTBOUND.codec();
    }

    @SuppressWarnings("unchecked")
    public byte[] game(Packet<?> packet) {
        ByteBuf buf = Unpooled.buffer(256);
        try {
            game.encode(buf, (Packet<? super ClientGamePacketListener>) packet);
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally { buf.release(); }
    }

    @SuppressWarnings("unchecked")
    public byte[] config(Packet<?> packet) {
        ByteBuf buf = Unpooled.buffer(256);
        try {
            config.encode(buf, (Packet<? super ClientConfigurationPacketListener>) packet);
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally { buf.release(); }
    }

    /** 검증용: 바이트를 실제로 디코드해 본다(끝까지 다 읽어야 정상) */
    public Packet<?> decodeGame(byte[] data, int off, int len) {
        ByteBuf buf = Unpooled.wrappedBuffer(data, off, len);
        Packet<?> p = game.decode(buf);
        if (buf.isReadable()) throw new IllegalStateException(p.type() + " 뒤에 " + buf.readableBytes() + "바이트 남음");
        return p;
    }

    public Packet<?> decodeConfig(byte[] data, int off, int len) {
        ByteBuf buf = Unpooled.wrappedBuffer(data, off, len);
        Packet<?> p = config.decode(buf);
        if (buf.isReadable()) throw new IllegalStateException(p.type() + " 뒤에 " + buf.readableBytes() + "바이트 남음");
        return p;
    }

    /** NMS FriendlyByteBuf 로 직접 쓰는 액션 내용(로컬 플레이어 등) */
    public byte[] write(Consumer<RegistryFriendlyByteBuf> writer) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(128), access);
        try {
            writer.accept(buf);
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally { buf.release(); }
    }

    public static int dataVersion() { return SharedConstants.getCurrentVersion().dataVersion().version(); }
    public static int protocolVersion() { return SharedConstants.getProtocolVersion(); }
    public static String versionName() { return SharedConstants.getCurrentVersion().name(); }
}
