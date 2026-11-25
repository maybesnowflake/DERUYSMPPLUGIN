package com.deruy.plugin.replay.nms;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.*;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;

import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 플레이어 연결의 Netty 파이프라인에 끼워 넣는 녹화 탭.
 * 서버가 보내는 패킷 객체를 (인코더 직전에) 보고, 리플레이에 필요한 것만 같은 코덱으로 인코딩해 큐에 쌓는다.
 * 실제 전송은 절대 막지 않는다. 문제가 생기면 녹화만 멈춘다.
 */
public final class PacketTap extends ChannelOutboundHandlerAdapter {
    public static final String NAME = "deruy_replay_tap";

    /** 녹화에 안 넣는 패킷 (Flashback 녹화기와 같은 기준 + 서버 쪽에서 따로 만드는 것) */
    private static final Set<Class<?>> IGNORED = Set.of(
            ClientboundStoreCookiePacket.class, ClientboundCustomReportDetailsPacket.class, ClientboundServerLinksPacket.class,
            net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket.class, ClientboundDisconnectPacket.class, ClientboundPingPacket.class,
            ClientboundKeepAlivePacket.class, ClientboundTransferPacket.class, ClientboundClearDialogPacket.class,
            ClientboundShowDialogPacket.class, ClientboundFinishConfigurationPacket.class, ClientboundCustomPayloadPacket.class,
            ClientboundAwardStatsPacket.class, ClientboundRecipeBookAddPacket.class, ClientboundRecipeBookRemovePacket.class,
            ClientboundRecipeBookSettingsPacket.class, ClientboundOpenSignEditorPacket.class, ClientboundRotateHeadPacket.class,
            ClientboundMoveEntityPacket.Pos.class, ClientboundMoveEntityPacket.Rot.class, ClientboundMoveEntityPacket.PosRot.class,
            ClientboundPlayerPositionPacket.class, ClientboundPlayerRotationPacket.class, ClientboundDeleteChatPacket.class,
            ClientboundMoveMinecartPacket.class, ClientboundContainerClosePacket.class, ClientboundContainerSetContentPacket.class,
            ClientboundMountScreenOpenPacket.class, ClientboundContainerSetDataPacket.class, ClientboundContainerSetSlotPacket.class,
            ClientboundPlayerAbilitiesPacket.class, ClientboundSetCursorItemPacket.class, ClientboundSetPlayerInventoryPacket.class,
            ClientboundTickingStatePacket.class, ClientboundTickingStepPacket.class, ClientboundPlayerCombatEndPacket.class,
            ClientboundPlayerCombatEnterPacket.class, ClientboundPlayerCombatKillPacket.class, ClientboundSetCameraPacket.class,
            ClientboundCooldownPacket.class, ClientboundUpdateAdvancementsPacket.class, ClientboundSelectAdvancementsTabPacket.class,
            ClientboundPlaceGhostRecipePacket.class, ClientboundCommandsPacket.class, ClientboundCommandSuggestionsPacket.class,
            ClientboundUpdateRecipesPacket.class, ClientboundTagQueryPacket.class, ClientboundOpenBookPacket.class,
            ClientboundOpenScreenPacket.class, ClientboundMerchantOffersPacket.class, ClientboundSetChunkCacheRadiusPacket.class,
            ClientboundSetSimulationDistancePacket.class, ClientboundSetChunkCacheCenterPacket.class, ClientboundBlockChangedAckPacket.class,
            ClientboundCustomChatCompletionsPacket.class, ClientboundChunkBatchStartPacket.class,
            ClientboundChunkBatchFinishedPacket.class, ClientboundDebugSamplePacket.class, ClientboundPongResponsePacket.class,
            ClientboundTestInstanceBlockStatus.class, ClientboundTrackedWaypointPacket.class,
            ClientboundBundleDelimiterPacket.class, ClientboundPlayerLookAtPacket.class
    );

    /** 큐 항목: 원본 패킷(추적용)과 인코딩된 바이트 */
    public record Captured(Packet<?> packet, byte[] bytes) {}

    private final Codecs codecs;
    private final ConcurrentLinkedQueue<Captured> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final int maxQueued;
    private volatile boolean active = true;
    private volatile boolean configuring;
    private volatile Throwable failure;

    public PacketTap(Codecs codecs, int maxQueued) {
        this.codecs = codecs;
        this.maxQueued = maxQueued;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (active && msg instanceof Packet<?> packet) {
            try { capture(packet); }
            catch (Throwable t) { failure = t; active = false; queue.clear(); }
        }
        super.write(ctx, msg, promise);
    }

    private void capture(Packet<?> packet) {
        if (packet instanceof BundlePacket<?> bundle) {
            for (Packet<?> sub : bundle.subPackets()) capture(sub);
            return;
        }
        if (packet instanceof ClientboundStartConfigurationPacket) { configuring = true; return; }
        if (packet instanceof ClientboundLoginPacket) { configuring = false; return; } // 재설정 후 새 로그인 → 녹화기가 새로 시작
        if (configuring) return;
        // 추적만 하고 리플레이엔 안 넣는 것
        if (packet instanceof ClientboundForgetLevelChunkPacket) { offer(new Captured(packet, null)); return; }
        if (IGNORED.contains(packet.getClass())) return;
        if (packet instanceof ClientboundPlayerChatPacket chat) {
            Component content = chat.unsignedContent() != null ? chat.unsignedContent() : Component.literal(chat.body().content());
            packet = new ClientboundSystemChatPacket(chat.chatType().decorate(content), false);
        }
        offer(new Captured(packet, codecs.game(packet)));
    }

    private void offer(Captured c) {
        if (queued.incrementAndGet() > maxQueued) { // 서버 스레드가 못 따라옴 → 이 녹화는 포기
            active = false; failure = new IllegalStateException("녹화 큐가 넘침(" + maxQueued + ")"); queue.clear();
            return;
        }
        queue.add(c);
    }

    public Captured poll() {
        Captured c = queue.poll();
        if (c != null) queued.decrementAndGet();
        return c;
    }

    public boolean active() { return active; }
    public boolean configuring() { return configuring; }
    public Throwable failure() { return failure; }
    public void stop() { active = false; queue.clear(); }
}
