package com.deruy.plugin.replay.nms;

import com.deruy.plugin.misc.Online;
import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.storage.LevelResource;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * 카메라 자리에 두는 보이지 않는 관전자. 서버는 소리·파편·폭발을 근처 "플레이어"에게만 보내므로,
 * 사람이 없는 카메라도 이 관전자가 받은 것을 녹화에 넣는다.
 * <ul>
 *   <li>접속(네트워크) 없이 서버 안에서만 존재 — 받은 패킷은 메모리에서 바로 버리고 소리 계열만 꺼낸다</li>
 *   <li>관전자 모드 · 몹 스폰에 영향 없음 · 잠자기 인원에서 빠짐 · 시야/시뮬레이션 거리 최소</li>
 *   <li>모든 실제 플레이어에게서 숨김(탭 목록·엔티티), 접속·퇴장 메시지 없음, 플러그인 기능은 {@link Online} 으로 제외</li>
 *   <li>없앨 때 서버가 저장한 이 관전자의 플레이어 데이터 파일을 지운다</li>
 * </ul>
 */
public final class CameraEar {
    private final ServerPlayer handle;
    private final Player bukkit;
    private final EmbeddedChannel channel;
    private final PacketTap tap;
    private final ConcurrentLinkedQueue<Packet<?>> replies = new ConcurrentLinkedQueue<>();
    private boolean removed;

    /** 이름 앞머리 — 탭 목록엔 안 보이지만 로그·/list 에서 알아보게 */
    public static final String PREFIX = "~ear_";

    private CameraEar(ServerPlayer handle, EmbeddedChannel channel, PacketTap tap) {
        this.handle = handle;
        this.bukkit = handle.getBukkitEntity();
        this.channel = channel;
        this.tap = tap;
    }

    public static UUID uuidFor(String key) {
        return UUID.nameUUIDFromBytes(("deruy-camera-ear:" + key).getBytes(StandardCharsets.UTF_8));
    }

    /** 카메라 위치에 관전자를 놓는다. 서버 스레드에서만 */
    public static CameraEar spawn(Plugin plugin, String key, Location at, Codecs codecs, int viewDistance) {
        MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
        ServerLevel level = ((CraftWorld) at.getWorld()).getHandle();
        UUID id = uuidFor(key);
        if (Bukkit.getPlayer(id) != null) throw new IllegalStateException("같은 카메라 관전자가 이미 있음: " + key);
        String n = (PREFIX + key.replaceFirst("^cam-", "").replaceAll("[^0-9A-Za-z_]", "_"));
        GameProfile profile = new GameProfile(id, n.substring(0, Math.min(16, n.length())));

        PacketTap tap = new PacketTap(codecs, 20000);
        EmbeddedChannel ch = new EmbeddedChannel();
        CameraEar[] self = new CameraEar[1];
        // 쓰기는 꼬리→머리로 흐름: 탭(소리 꺼내기) → 싱크(응답할 것만 보고 버림)
        ch.pipeline().addLast("deruy_ear_sink", new ChannelOutboundHandlerAdapter() {
            @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                try { if (self[0] != null && msg instanceof Packet<?> p) self[0].watch(p); }
                finally { ReferenceCountUtil.release(msg); promise.trySuccess(); }
            }
        });
        ch.pipeline().addLast(PacketTap.NAME, tap);
        FakeConnection conn = new FakeConnection(ch);

        Online.markFake(id); // 접속 이벤트보다 먼저 — 다른 기능이 처음부터 무시하게
        ServerPlayer sp;
        try {
            sp = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
            // 들어오기 전에 숨겨 둬야 접속 순간 탭 목록에도 안 뜬다
            for (Player other : Bukkit.getOnlinePlayers()) other.hidePlayer(plugin, sp.getBukkitEntity());
            server.getPlayerList().placeNewPlayer(conn, sp, CommonListenerCookie.createInitial(profile, false));
        } catch (RuntimeException | LinkageError e) {
            Online.unmarkFake(id);
            ch.close();
            throw e;
        }
        CameraEar ear = new CameraEar(sp, ch, tap);
        self[0] = ear;
        Player p = ear.bukkit;
        p.setGameMode(GameMode.SPECTATOR);
        p.setAffectsSpawning(false);
        p.setSleepingIgnored(true);
        p.setInvulnerable(true);
        p.setCollidable(false);
        p.setSilent(true);
        int vd = Math.max(2, Math.min(viewDistance, 32));
        try { p.setViewDistance(vd); p.setSimulationDistance(2); p.setSendViewDistance(2); } catch (RuntimeException ignored) { }
        for (Player other : Bukkit.getOnlinePlayers()) if (other != p && other.canSee(p)) other.hidePlayer(plugin, p);
        ear.moveTo(at);
        return ear;
    }

    /** 새로 들어온 실제 플레이어에게서 숨긴다 (접속 이벤트에서) */
    public void hideFrom(Plugin plugin, Player joined) { if (joined != bukkit) joined.hidePlayer(plugin, bukkit); }

    public UUID uuid() { return handle.getUUID(); }
    public Player player() { return bukkit; }
    public boolean alive() { return !removed && !handle.isRemoved() && handle.connection != null; }

    /** 카메라가 움직이면(추적 카메라) 따라간다. 같은 월드 안 작은 움직임은 순간이동 없이 */
    public void moveTo(Location at) {
        if (at == null || at.getWorld() == null || removed) return;
        Location cur = bukkit.getLocation();
        if (cur.getWorld() == at.getWorld() && cur.distanceSquared(at) < 0.25) return;
        bukkit.teleport(at);
    }

    /** 이번 틱까지 받은 것 중 소리 계열만 내보내고, 클라이언트가 했어야 할 응답(접속 유지·순간이동 확인)을 대신 한다. 서버 스레드에서만 */
    public void drain(Consumer<SoundShare> out) {
        Packet<?> r;
        while ((r = replies.poll()) != null) answer(r);
        if (!tap.active()) return;
        PacketTap.Captured c;
        ServerLevel level = (ServerLevel) handle.level();
        while ((c = tap.poll()) != null) {
            if (c.bytes() == null) continue;
            SoundShare s = SoundShare.of(c.packet(), c.bytes(), level);
            if (s != null) out.accept(s);
        }
    }

    private void watch(Packet<?> p) {
        if (p instanceof BundlePacket<?> b) { for (Packet<?> sub : b.subPackets()) watch(sub); return; }
        if (p instanceof ClientboundKeepAlivePacket || p instanceof ClientboundPlayerPositionPacket) replies.add(p);
    }

    private void answer(Packet<?> p) {
        var listener = handle.connection;
        if (listener == null) return;
        try {
            if (p instanceof ClientboundKeepAlivePacket k) listener.handleKeepAlive(new ServerboundKeepAlivePacket(k.getId()));
            else if (p instanceof ClientboundPlayerPositionPacket pos) listener.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(pos.id()));
        } catch (RuntimeException ignored) { } // 응답 실패는 다음 것으로 충분
    }

    /** 서버에서 빼고 이 관전자 데이터 파일을 지운다. 서버 스레드에서만 */
    public void remove() {
        if (removed) return;
        removed = true;
        tap.stop();
        MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
        UUID id = handle.getUUID();
        try {
            if (handle.connection != null && server.getPlayerList().players.contains(handle)) server.getPlayerList().remove(handle);
        } finally {
            channel.close();
            Online.unmarkFake(id);
            for (Path f : List.of(
                    server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(id + ".dat"),
                    server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(id + ".dat_old"),
                    server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(id + ".json"),
                    server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).resolve(id + ".json")))
                try { Files.deleteIfExists(f); } catch (Exception ignored) { }
        }
    }

    /** 네트워크 없는 연결: 프로토콜 전환·끊김 처리를 하지 않는다 */
    private static final class FakeConnection extends Connection {
        FakeConnection(EmbeddedChannel ch) {
            super(PacketFlow.SERVERBOUND);
            this.channel = ch;
            this.address = new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0); // 주소를 읽는 플러그인 대비
        }
        @Override public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> info, T listener) { }
        @Override public void setupOutboundProtocol(ProtocolInfo<?> info) { }
        @Override public void setReadOnly() { }
        @Override public void handleDisconnection() { }
    }
}
