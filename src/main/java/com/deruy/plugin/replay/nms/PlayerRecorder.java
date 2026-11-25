package com.deruy.plugin.replay.nms;

import com.deruy.plugin.replay.buffer.ChunkStore;
import com.deruy.plugin.replay.buffer.PlayerBuffer;
import com.deruy.plugin.replay.buffer.Segment;
import com.deruy.plugin.replay.format.FbOut;
import com.deruy.plugin.replay.format.FlashbackFormat;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.scoreboard.CraftScoreboard;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * 한 플레이어 시점의 서버 쪽 녹화기 (서버 스레드 전용, PacketTap 이 모은 패킷을 매 틱 비운다).
 * 구간마다 "그 시점 화면 상태" 스냅샷을 서버 상태로 직접 만들어서, 버퍼 어디서부터든 Flashback 이 재생할 수 있게 한다.
 */
public final class PlayerRecorder implements com.deruy.plugin.replay.buffer.Recorder {
    private record Pos(double x, double y, double z, float yaw, float pitch, float head, boolean onGround) {}
    private static final class ChunkEntry { String hash; boolean dirty; }
    private static final class Boss { byte[] add; final LinkedHashMap<String, byte[]> updates = new LinkedHashMap<>(); }

    public final UUID uuid;
    public final String name;
    private final Player bukkit;
    private final ServerPlayer handle;
    private final PacketTap tap;
    private final Codecs codecs;
    private final ChunkStore store;
    private final PlayerBuffer buffer;
    private final String configBlob;
    private final int segmentTicks;

    private final Map<Long, ChunkEntry> chunks = new HashMap<>();
    private final LinkedHashMap<UUID, Boss> bosses = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, ClientboundResourcePackPushPacket> packs = new LinkedHashMap<>();
    private byte[] tabList;
    private final IntSet entityIds = new IntOpenHashSet();
    private final Map<Integer, Pos> lastPositions = new HashMap<>();
    private final Map<EquipmentSlot, ItemStack> lastEquipment = new EnumMap<>(EquipmentSlot.class);
    private final ItemStack[] lastHotbar = new ItemStack[9];
    private int lastHeldSlot = -1;
    private boolean swingMain, swingOff;
    private boolean needSnapshot = true;
    /** 이 사람이 받은 소리 계열을 다른 녹화와 나누는 곳 (서비스가 설정) */
    private java.util.function.Consumer<SoundShare> soundOut = s -> { };
    /** 최근 3틱 동안 받은 소리 해시 — 빌려 온 소리와 겹치지 않게 */
    private final ArrayDeque<it.unimi.dsi.fastutil.longs.LongOpenHashSet> heard = new ArrayDeque<>();
    private static final double HEAR = 32;
    private String stoppedReason;

    public PlayerRecorder(Player player, Codecs codecs, ChunkStore store, PlayerBuffer buffer, String configBlob, int segmentTicks, int maxQueued) {
        this.bukkit = player;
        this.uuid = player.getUniqueId();
        this.name = player.getName();
        this.handle = ((CraftPlayer) player).getHandle();
        this.codecs = codecs;
        this.store = store;
        this.buffer = buffer;
        this.configBlob = configBlob;
        this.segmentTicks = segmentTicks;
        this.tap = new PacketTap(codecs, maxQueued);
        var pipeline = handle.connection.connection.channel.pipeline();
        if (pipeline.get(PacketTap.NAME) != null) pipeline.remove(PacketTap.NAME);
        pipeline.addAfter("encoder", PacketTap.NAME, tap);
    }

    @Override public String name() { return name; }
    @Override public PlayerBuffer buffer() { return buffer; }
    public Player player() { return bukkit; }
    @Override public String stoppedReason() { return stoppedReason; }
    @Override public boolean alive() { return stoppedReason == null; }

    public void soundOut(java.util.function.Consumer<SoundShare> out) { this.soundOut = out; }

    /** 근처 다른 사람이 받은 소리 중 이 사람은 못 받은 것(본인 발소리 등)을 넣는다. tick() 전에 호출 */
    public void injectSounds(List<SoundShare> shared) {
        Segment seg = buffer.current();
        if (shared.isEmpty() || seg == null || seg.closed() || needSnapshot || stoppedReason != null) return;
        String dim = handle.level().dimension().identifier().toString();
        if (heard.isEmpty()) heard.addLast(new it.unimi.dsi.fastutil.longs.LongOpenHashSet());
        for (SoundShare s : shared) {
            if (!s.near(dim, handle.getX(), handle.getY(), handle.getZ(), HEAR) || heardRecently(s.hash())) continue;
            seg.action(FlashbackFormat.ID_GAME_PACKET, s.bytes());
            heard.peekLast().add(s.hash());
        }
    }

    private boolean heardRecently(long h) {
        for (var set : heard) if (set.contains(h)) return true;
        return false;
    }

    public void swing(boolean mainHand) { if (mainHand) swingMain = true; else swingOff = true; }

    /** 매 틱 (서버 스레드). 반환: 내보낼 클립들 */
    @Override public List<PlayerBuffer.Clip> tick(long now) {
        if (stoppedReason != null) return List.of();
        if (!tap.active()) { stop("패킷 수집 중단: " + tap.failure()); return List.of(); }
        if (tap.configuring()) { drainDiscard(); needSnapshot = true; return List.of(); }

        heard.addLast(new it.unimi.dsi.fastutil.longs.LongOpenHashSet());
        while (heard.size() > 3) heard.removeFirst();
        Segment seg = buffer.current();
        if (seg != null && !needSnapshot) drain(seg); else drainDiscard();
        if (needSnapshot || seg == null || seg.ticks() >= segmentTicks) {
            startSegment(now);
            seg = buffer.current();
        }
        writeLocal(seg);
        writePositions(seg);
        seg.nextTick();

        List<PlayerBuffer.Clip> clips = new ArrayList<>(1);
        boolean window = buffer.exportDue(now + 1), extra = buffer.extraDue(now + 1);
        if (window || extra) {
            startSegment(now + 1); // 지금까지를 닫고 새 스냅샷으로 이어감
            if (extra) clips.addAll(buffer.takeExtras(now + 1));
            if (window) { PlayerBuffer.Clip c = buffer.takeClip(now + 1); if (c != null) clips.add(c); }
        }
        buffer.purge(now + 1);
        return clips;
    }

    private void drainDiscard() { while (tap.poll() != null) { } }

    private void drain(Segment seg) {
        PacketTap.Captured c;
        while ((c = tap.poll()) != null) {
            Packet<?> p = c.packet();
            if (p instanceof ClientboundForgetLevelChunkPacket f) { forget(f.pos()); continue; }
            if (p instanceof ClientboundLevelChunkWithLightPacket lc) {
                String h = store.put(c.bytes());
                ChunkEntry e = chunks.computeIfAbsent(ChunkPos.asLong(lc.getX(), lc.getZ()), k -> new ChunkEntry());
                if (e.hash != null) store.release(e.hash);
                e.hash = h; e.dirty = false;
                seg.chunkRef(h);
                continue;
            }
            track(p, c.bytes());
            seg.action(FlashbackFormat.ID_GAME_PACKET, c.bytes());
            SoundShare share = SoundShare.of(p, c.bytes(), handle.level());
            if (share != null) { heard.peekLast().add(share.hash()); soundOut.accept(share); }
        }
    }

    private void track(Packet<?> p, byte[] bytes) {
        switch (p) {
            case ClientboundBlockUpdatePacket b -> dirty(b.getPos());
            case ClientboundSectionBlocksUpdatePacket s -> s.runUpdates((pos, state) -> dirty(pos));
            case ClientboundBlockEntityDataPacket b -> dirty(b.getPos());
            case ClientboundLightUpdatePacket l -> dirty(ChunkPos.asLong(l.getX(), l.getZ()));
            case ClientboundChunksBiomesPacket b -> b.chunkBiomeData().forEach(d -> dirty(d.pos().toLong()));
            case ClientboundRespawnPacket r -> { clearChunks(); entityIds.clear(); lastPositions.clear(); }
            case ClientboundAddEntityPacket a -> entityIds.add(a.getId());
            case ClientboundRemoveEntitiesPacket r -> r.getEntityIds().forEach(id -> { entityIds.remove(id); lastPositions.remove(id); });
            case ClientboundTabListPacket t -> tabList = bytes;
            case ClientboundResourcePackPushPacket push -> packs.put(push.id(), push);
            case ClientboundResourcePackPopPacket pop -> { if (pop.id().isPresent()) packs.remove(pop.id().get()); else packs.clear(); }
            case ClientboundBossEventPacket boss -> boss.dispatch(new ClientboundBossEventPacket.Handler() {
                @Override public void add(UUID id, Component n, float pr, BossEvent.BossBarColor c, BossEvent.BossBarOverlay o, boolean a, boolean b, boolean f) {
                    Boss x = new Boss(); x.add = bytes; bosses.put(id, x); }
                @Override public void remove(UUID id) { bosses.remove(id); }
                @Override public void updateProgress(UUID id, float v) { upd(id, "progress"); }
                @Override public void updateName(UUID id, Component n) { upd(id, "name"); }
                @Override public void updateStyle(UUID id, BossEvent.BossBarColor c, BossEvent.BossBarOverlay o) { upd(id, "style"); }
                @Override public void updateProperties(UUID id, boolean a, boolean b, boolean c) { upd(id, "props"); }
                private void upd(UUID id, String key) { Boss x = bosses.get(id); if (x != null) x.updates.put(key, bytes); }
            });
            default -> { }
        }
    }

    private void dirty(BlockPos pos) { dirty(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4)); }
    private void dirty(long key) { ChunkEntry e = chunks.get(key); if (e != null) e.dirty = true; }
    private void forget(ChunkPos pos) { ChunkEntry e = chunks.remove(pos.toLong()); if (e != null && e.hash != null) store.release(e.hash); }
    private void clearChunks() { for (ChunkEntry e : chunks.values()) if (e.hash != null) store.release(e.hash); chunks.clear(); }

    // ---------------------------------------------------------------- 매 틱: 본인 상태 + 위치

    private void writeLocal(Segment seg) {
        List<Pair<EquipmentSlot, ItemStack>> changed = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack now = handle.getItemBySlot(slot);
            ItemStack last = lastEquipment.get(slot);
            if (last == null || !ItemStack.matches(last, now)) {
                ItemStack copy = now.copy();
                lastEquipment.put(slot, copy);
                changed.add(Pair.of(slot, copy));
            }
        }
        if (!changed.isEmpty()) game(seg, new ClientboundSetEquipmentPacket(handle.getId(), changed));
        var inv = handle.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack now = inv.getItem(i);
            if (lastHotbar[i] == null || !ItemStack.matches(lastHotbar[i], now)) {
                lastHotbar[i] = now.copy();
                game(seg, new ClientboundContainerSetSlotPacket(0, 0, i, lastHotbar[i].copy()));
            }
        }
        int held = inv.getSelectedSlot();
        if (held != lastHeldSlot) { lastHeldSlot = held; game(seg, new ClientboundSetHeldSlotPacket(held)); }
        if (swingMain) game(seg, new ClientboundAnimatePacket(handle, ClientboundAnimatePacket.SWING_MAIN_HAND));
        if (swingOff) game(seg, new ClientboundAnimatePacket(handle, ClientboundAnimatePacket.SWING_OFF_HAND));
        swingMain = swingOff = false;
    }

    private void writePositions(Segment seg) {
        ServerLevel level = handle.level();
        FbOut list = new FbOut(256);
        int count = 0;
        count += position(list, handle);
        for (var it = entityIds.iterator(); it.hasNext(); ) {
            Entity e = level.getEntity(it.nextInt());
            if (e != null) count += position(list, e);
        }
        if (count == 0) return;
        FbOut out = new FbOut(list.size() + 64);
        out.writeVarInt(1);
        out.writeUtf(level.dimension().identifier().toString());
        out.writeVarInt(count);
        out.writeBytes(list.array(), 0, list.size());
        seg.action(FlashbackFormat.ID_MOVE_ENTITIES, out.array(), 0, out.size());
    }

    private int position(FbOut out, Entity e) {
        float head = e instanceof LivingEntity l ? l.getYHeadRot() : e.getYRot();
        Pos p = new Pos(e.getX(), e.getY(), e.getZ(), e.getYRot(), e.getXRot(), head, e.onGround());
        if (p.equals(lastPositions.put(e.getId(), p))) return 0;
        out.writeVarInt(e.getId());
        out.writeDouble(p.x); out.writeDouble(p.y); out.writeDouble(p.z);
        out.writeFloat(p.yaw); out.writeFloat(p.pitch); out.writeFloat(p.head);
        out.writeBoolean(p.onGround);
        return 1;
    }

    private void game(Segment seg, Packet<?> p) { seg.action(FlashbackFormat.ID_GAME_PACKET, codecs.game(p)); }
    private void game(FbOut out, Packet<?> p) { FlashbackFormat.action(out, FlashbackFormat.ID_GAME_PACKET, codecs.game(p)); }

    // ---------------------------------------------------------------- 스냅샷

    private void startSegment(long now) {
        FbOut snap = new FbOut(1 << 16);
        Set<String> refs = new HashSet<>();
        buildSnapshot(snap, refs);
        buffer.startSegment(new Segment(store, now, snap.toByteArray(), refs));
        needSnapshot = false;
    }

    private void buildSnapshot(FbOut out, Set<String> refs) {
        MinecraftServer server = handle.level().getServer();
        ServerLevel level = handle.level();

        // 설정 단계: 레지스트리·태그·기능(서버 공통 묶음) + 리소스팩
        FlashbackFormat.action(out, FlashbackFormat.ID_BLOB_REF, HexFormat.of().parseHex(configBlob));
        refs.add(configBlob);
        FlashbackFormat.action(out, FlashbackFormat.ID_CONFIGURATION_PACKET, codecs.config(new ClientboundResourcePackPopPacket(Optional.empty())));
        for (var push : packs.values()) FlashbackFormat.action(out, FlashbackFormat.ID_CONFIGURATION_PACKET, codecs.config(push));

        // 접속
        game(out, new ClientboundLoginPacket(handle.getId(), level.getLevelData().isHardcore(), server.levelKeys(),
                server.getMaxPlayers(), bukkit.getViewDistance(), bukkit.getSimulationDistance(), false, true, false,
                handle.createCommonSpawnInfo(level), false));
        FlashbackFormat.action(out, FlashbackFormat.ID_CREATE_LOCAL_PLAYER, codecs.write(buf -> {
            buf.writeUUID(handle.getUUID());
            buf.writeDouble(handle.getX()); buf.writeDouble(handle.getY()); buf.writeDouble(handle.getZ());
            buf.writeFloat(handle.getXRot()); buf.writeFloat(handle.getYRot()); buf.writeFloat(handle.getYHeadRot());
            buf.writeVec3(handle.getDeltaMovement());
            ByteBufCodecs.GAME_PROFILE.encode(buf, handle.getGameProfile());
            buf.writeVarInt(handle.gameMode.getGameModeForPlayer().getId());
        }));

        // 탭 목록(보이는 플레이어)
        List<ServerPlayer> visible = new ArrayList<>();
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) if (sp == handle || bukkit.canSee(sp.getBukkitEntity())) visible.add(sp);
        game(out, ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(visible));
        FlashbackFormat.action(out, FlashbackFormat.ID_GAME_PACKET,
                tabList != null ? tabList : codecs.game(new ClientboundTabListPacket(Component.empty(), Component.empty())));
        for (Boss b : bosses.values()) {
            FlashbackFormat.action(out, FlashbackFormat.ID_GAME_PACKET, b.add);
            for (byte[] u : b.updates.values()) FlashbackFormat.action(out, FlashbackFormat.ID_GAME_PACKET, u);
        }
        scoreboard(out);

        // 월드 상태
        game(out, new ClientboundInitializeBorderPacket(level.getWorldBorder()));
        game(out, new ClientboundSetTimePacket(level.getLevelData().getGameTime(), level.getDayTime(),
                level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME)));
        game(out, new ClientboundSetDefaultSpawnPositionPacket(level.getRespawnData()));
        game(out, new ClientboundGameEventPacket(level.isRaining() ? ClientboundGameEventPacket.START_RAINING : ClientboundGameEventPacket.STOP_RAINING, 0));
        game(out, new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, level.getRainLevel(1f)));
        game(out, new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, level.getThunderLevel(1f)));

        // 청크: 받은 적 있는데 기록에 없거나, 그 뒤로 바뀐 청크는 서버에서 다시 만든다
        for (org.bukkit.Chunk c : bukkit.getSentChunks()) chunks.computeIfAbsent(ChunkPos.asLong(c.getX(), c.getZ()), k -> { ChunkEntry e = new ChunkEntry(); e.dirty = true; return e; });
        for (var it = chunks.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            ChunkEntry e = en.getValue();
            if (e.dirty || e.hash == null) {
                LevelChunk lc = level.getChunkSource().getChunkNow(ChunkPos.getX(en.getKey()), ChunkPos.getZ(en.getKey()));
                if (lc == null) { if (e.hash == null) it.remove(); else e.dirty = false; if (e.hash == null) continue; }
                else {
                    String h = store.put(codecs.game(new ClientboundLevelChunkWithLightPacket(lc, level.getLightEngine(), null, null, false)));
                    if (e.hash != null) store.release(e.hash);
                    e.hash = h; e.dirty = false;
                }
            }
            FlashbackFormat.action(out, FlashbackFormat.ID_CHUNK_REF, HexFormat.of().parseHex(e.hash));
            refs.add(e.hash);
        }

        // 본인 상태
        var food = handle.getFoodData();
        game(out, new ClientboundSetHealthPacket(handle.getHealth(), food.getFoodLevel(), food.getSaturationLevel()));
        game(out, new ClientboundSetExperiencePacket(handle.experienceProgress, handle.totalExperience, handle.experienceLevel));
        var inv = handle.getInventory();
        lastHeldSlot = inv.getSelectedSlot();
        game(out, new ClientboundSetHeldSlotPacket(lastHeldSlot));
        for (int i = 0; i < 9; i++) { lastHotbar[i] = inv.getItem(i).copy(); game(out, new ClientboundContainerSetSlotPacket(0, 0, i, lastHotbar[i].copy())); }
        entityState(out, handle);

        // 보이는 엔티티: 서버가 처음 보여 줄 때 보내는 것과 똑같이
        entityIds.clear();
        lastPositions.clear();
        ChunkMap map = level.getChunkSource().chunkMap;
        for (ChunkMap.TrackedEntity te : map.entityMap.values()) {
            if (!te.seenBy.contains(handle.connection)) continue;
            te.serverEntity.sendPairingData(handle, p -> {
                if (p instanceof ClientboundAddEntityPacket a) entityIds.add(a.getId());
                game(out, p);
            });
        }
    }

    private void entityState(FbOut out, LivingEntity e) {
        List<SynchedEntityData.DataValue<?>> data = e.getEntityData().getNonDefaultValues();
        if (data != null && !data.isEmpty()) game(out, new ClientboundSetEntityDataPacket(e.getId(), data));
        var attrs = e.getAttributes().getSyncableAttributes();
        if (!attrs.isEmpty()) game(out, new ClientboundUpdateAttributesPacket(e.getId(), attrs));
        List<Pair<EquipmentSlot, ItemStack>> eq = new ArrayList<>();
        lastEquipment.clear();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack s = e.getItemBySlot(slot).copy();
            lastEquipment.put(slot, s);
            if (!s.isEmpty()) eq.add(Pair.of(slot, s.copy()));
        }
        if (!eq.isEmpty()) game(out, new ClientboundSetEquipmentPacket(e.getId(), eq));
        // 걸려 있는 효과(야간 투시·실명 등) — 남은 시간 그대로
        for (var effect : e.getActiveEffects()) game(out, new ClientboundUpdateMobEffectPacket(e.getId(), new net.minecraft.world.effect.MobEffectInstance(effect), false));
    }

    private void scoreboard(FbOut out) {
        Scoreboard sb = ((CraftScoreboard) bukkit.getScoreboard()).getHandle();
        for (PlayerTeam team : sb.getPlayerTeams()) game(out, ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true));
        Set<Objective> done = new HashSet<>();
        for (DisplaySlot slot : DisplaySlot.values()) {
            Objective o = sb.getDisplayObjective(slot);
            if (o == null || !done.add(o)) continue;
            game(out, new ClientboundSetObjectivePacket(o, 0));
            for (DisplaySlot s2 : DisplaySlot.values()) if (sb.getDisplayObjective(s2) == o) game(out, new ClientboundSetDisplayObjectivePacket(s2, o));
            for (PlayerScoreEntry e : sb.listPlayerScores(o))
                game(out, new ClientboundSetScorePacket(e.owner(), o.getName(), e.value(), Optional.ofNullable(e.display()), Optional.ofNullable(e.numberFormatOverride())));
        }
    }

    // ---------------------------------------------------------------- 정리

    @Override public void stop(String reason) {
        if (stoppedReason != null) return;
        stoppedReason = reason;
        tap.stop();
        try {
            var pipeline = handle.connection.connection.channel.pipeline();
            if (pipeline.get(PacketTap.NAME) != null) pipeline.remove(PacketTap.NAME);
        } catch (RuntimeException ignored) { }
    }

    /** 나갈 때: 진행 중인 하이라이트·킬캠이 있으면 그때까지를 클립으로 */
    @Override public List<PlayerBuffer.Clip> finish(long now) {
        List<PlayerBuffer.Clip> clips = new ArrayList<>();
        if (stoppedReason == null && buffer.current() != null && (buffer.recording() || buffer.hasExtras())) {
            buffer.current().close();
            clips.addAll(buffer.takeExtras(Long.MAX_VALUE));
            if (buffer.recording()) {
                buffer.highlight("퇴장", now, now, null);
                PlayerBuffer.Clip c = buffer.takeClip(now);
                if (c != null) clips.add(c);
            }
        }
        stop("퇴장");
        clearChunks();
        return clips;
    }

    @Override public void release() { buffer.clear(); clearChunks(); }
}
