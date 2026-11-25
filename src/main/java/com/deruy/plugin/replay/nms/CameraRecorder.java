package com.deruy.plugin.replay.nms;

import com.deruy.plugin.replay.buffer.ChunkStore;
import com.deruy.plugin.replay.buffer.PlayerBuffer;
import com.deruy.plugin.replay.buffer.Recorder;
import com.deruy.plugin.replay.buffer.Segment;
import com.deruy.plugin.replay.format.FbOut;
import com.deruy.plugin.replay.format.FlashbackFormat;
import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftWorld;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

/**
 * 플레이어 없이 한 지점(고정 카메라) 또는 움직이는 대상(추적 카메라)을 녹화하는 가상 시청자.
 * 패킷을 받아 적을 연결이 없으므로 서버 상태를 매 틱 직접 읽어 Flashback 액션으로 만든다:
 * 반경 안 엔티티 출입·위치·데이터·장비, 피격·사망·팔 휘두르기, 블록 변화(청크는 스냅샷마다 다시).
 * 소리는 들어가지 않는다(서버가 실제 플레이어에게만 보냄).
 */
public final class CameraRecorder implements Recorder {
    private record Pos(double x, double y, double z, float yaw, float pitch, float head, boolean onGround) {}
    private static final class Seen {
        final Map<Integer, Object> data = new HashMap<>();
        final Map<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
        Pos pos;
        List<Integer> passengers = List.of();
    }

    private final String name;
    private final Codecs codecs;
    private final ChunkStore store;
    private final PlayerBuffer buffer;
    private final String configBlob;
    private final int segmentTicks;
    private final Supplier<Location> where;
    private final double radius;
    private final int chunkRadius;
    private final int cameraId;
    private final GameProfile profile;
    private final float fixedYaw, fixedPitch;
    private final boolean follow;

    private ServerLevel level;
    private final Map<Long, String> chunks = new HashMap<>();
    private final Int2ObjectOpenHashMap<Seen> seen = new Int2ObjectOpenHashMap<>();
    private final LongOpenHashSet changedBlocks = new LongOpenHashSet();
    private final List<Packet<?>> pending = new ArrayList<>();
    private Pos cameraPos;
    private boolean needSnapshot = true;
    private String stoppedReason;
    private int missing;
    private int refreshClock;

    /**
     * @param where 카메라 위치 (고정이면 늘 같은 값, 추적이면 대상 위치). null 을 돌려주면 대상 없음
     * @param follow true 면 대상 위 6칸·뒤에서 내려다보는 자리로 따라간다
     */
    public CameraRecorder(String name, int index, Codecs codecs, ChunkStore store, PlayerBuffer buffer, String configBlob,
                          int segmentTicks, Supplier<Location> where, double radius, boolean follow) {
        this.name = name;
        this.codecs = codecs;
        this.store = store;
        this.buffer = buffer;
        this.configBlob = configBlob;
        this.segmentTicks = segmentTicks;
        this.where = where;
        this.radius = radius;
        this.follow = follow;
        this.chunkRadius = (int) Math.ceil(radius / 16.0) + 1;
        this.cameraId = Integer.MAX_VALUE - 1000 - index; // 실제 엔티티 번호와 안 겹치게
        String n = ("CAM_" + name.replaceAll("[^0-9A-Za-z_]", "_"));
        this.profile = new GameProfile(UUID.nameUUIDFromBytes(("deruy-camera:" + name).getBytes(StandardCharsets.UTF_8)), n.substring(0, Math.min(16, n.length())));
        Location l = where.get();
        this.fixedYaw = l == null ? 0 : l.getYaw();
        this.fixedPitch = l == null ? 0 : l.getPitch();
    }

    @Override public String name() { return name; }
    @Override public PlayerBuffer buffer() { return buffer; }
    @Override public boolean alive() { return stoppedReason == null; }
    @Override public String stoppedReason() { return stoppedReason; }

    /** 서비스가 이벤트로 알려 주는 것들 (반경 밖이면 무시) */
    public void blockChanged(org.bukkit.World w, int x, int y, int z) {
        if (level == null || ((CraftWorld) w).getHandle() != level) return;
        if (!chunks.containsKey(ChunkPos.asLong(x >> 4, z >> 4))) return;
        changedBlocks.add(BlockPos.asLong(x, y, z));
    }

    public void blockBroken(org.bukkit.block.Block b, org.bukkit.block.data.BlockData before) {
        if (level == null || ((CraftWorld) b.getWorld()).getHandle() != level || !chunks.containsKey(ChunkPos.asLong(b.getX() >> 4, b.getZ() >> 4))) return;
        var state = ((org.bukkit.craftbukkit.block.data.CraftBlockData) before).getState();
        pending.add(new ClientboundLevelEventPacket(2001, new BlockPos(b.getX(), b.getY(), b.getZ()), Block.getId(state), false)); // 부서지는 파편
        changedBlocks.add(BlockPos.asLong(b.getX(), b.getY(), b.getZ()));
    }

    public void entityEvent(org.bukkit.entity.Entity e, String kind) {
        Entity h = ((org.bukkit.craftbukkit.entity.CraftEntity) e).getHandle();
        if (!seen.containsKey(h.getId())) return;
        switch (kind) {
            case "hurt" -> { if (h instanceof LivingEntity l) pending.add(new ClientboundHurtAnimationPacket(l)); }
            case "death" -> pending.add(new ClientboundEntityEventPacket(h, (byte) 3));
            case "swing" -> pending.add(new ClientboundAnimatePacket(h, ClientboundAnimatePacket.SWING_MAIN_HAND));
            case "swing-off" -> pending.add(new ClientboundAnimatePacket(h, ClientboundAnimatePacket.SWING_OFF_HAND));
            default -> { }
        }
    }

    @Override public List<PlayerBuffer.Clip> tick(long now) {
        if (stoppedReason != null) return List.of();
        Location at = where.get();
        if (at == null || at.getWorld() == null) {
            // 10초 넘게 대상이 없으면(운송 중지 등) 지금까지를 저장하고 끝
            if (++missing > 200) return finish(now);
            return finishIfDue(now);
        }
        missing = 0;
        ServerLevel lvl = ((CraftWorld) at.getWorld()).getHandle();
        if (lvl != level) { level = lvl; needSnapshot = true; }
        Segment seg = buffer.current();
        if (needSnapshot || seg == null) { startSegment(now, at); seg = buffer.current(); }
        else update(seg, at); // 구간이 바뀌기 직전 틱의 변화도 이 구간 액션으로 남긴다(연속 재생용)
        seg.nextTick();
        if (seg.ticks() >= segmentTicks) startSegment(now + 1, at); // 다음 구간은 지금 상태 그대로의 스냅샷으로(탐색용)
        return finishIfDue(now);
    }

    private List<PlayerBuffer.Clip> finishIfDue(long now) {
        List<PlayerBuffer.Clip> clips = new ArrayList<>(1);
        if (buffer.current() != null && buffer.exportDue(now + 1)) {
            Location at = where.get();
            Segment cur = buffer.current();
            boolean justRolled = cur.startTick == now + 1 && cur.ticks() == 0;
            if (justRolled) { /* 방금 새 구간을 열었음 */ }
            else if (at != null && at.getWorld() != null && level != null) startSegment(now + 1, at);
            else cur.close();
            PlayerBuffer.Clip c = buffer.takeClip(now + 1);
            if (c != null) clips.add(c);
        }
        buffer.purge(now + 1);
        return clips;
    }

    // ------------------------------------------------------------------ 매 틱

    private void update(Segment seg, Location at) {
        // 새로 범위에 들어온 청크
        int cx = at.getBlockX() >> 4, cz = at.getBlockZ() >> 4;
        for (int x = cx - chunkRadius; x <= cx + chunkRadius; x++)
            for (int z = cz - chunkRadius; z <= cz + chunkRadius; z++) {
                long key = ChunkPos.asLong(x, z);
                if (chunks.containsKey(key)) continue;
                String h = chunk(x, z);
                if (h != null) seg.chunkRef(h);
            }
        // 이벤트 없이 바뀐 블록(다른 플러그인의 setType, /fill 등) 대비: 5초마다 범위 청크를 다시 만들어 달라졌으면 통째로 교체
        if (++refreshClock % 100 == 0) {
            for (var en : List.copyOf(chunks.entrySet())) {
                String old = en.getValue();
                String h = chunk(ChunkPos.getX(en.getKey()), ChunkPos.getZ(en.getKey()));
                if (h != null && !h.equals(old)) seg.chunkRef(h);
            }
        }
        // 블록 변화
        for (long p : changedBlocks) {
            BlockPos pos = BlockPos.of(p);
            if (level.isLoaded(pos)) game(seg, new ClientboundBlockUpdatePacket(level, pos));
        }
        changedBlocks.clear();

        // 엔티티 출입·상태
        Map<Integer, Entity> now = inRange(at);
        List<Integer> gone = new ArrayList<>();
        for (int id : seen.keySet()) if (!now.containsKey(id)) gone.add(id);
        if (!gone.isEmpty()) {
            game(seg, new ClientboundRemoveEntitiesPacket(gone.stream().mapToInt(Integer::intValue).toArray()));
            gone.forEach(seen::remove);
        }
        FbOut moves = new FbOut(256);
        int moved = cameraMove(moves, at);
        for (Entity e : now.values()) {
            Seen s = seen.get(e.getId());
            if (s == null) { pair(seg, e); continue; }
            diffData(seg, e, s);
            if (e instanceof LivingEntity l) diffEquipment(seg, l, s);
            List<Integer> pass = e.getPassengers().stream().map(Entity::getId).toList();
            if (!pass.equals(s.passengers)) { s.passengers = pass; game(seg, new ClientboundSetPassengersPacket(e)); }
            moved += move(moves, e, s);
        }
        for (Packet<?> p : pending) game(seg, p);
        pending.clear();
        if (moved > 0) writeMoves(seg, moves, moved);
    }

    private Map<Integer, Entity> inRange(Location at) {
        Map<Integer, Entity> out = new LinkedHashMap<>();
        AABB box = new AABB(at.getX() - radius, at.getY() - radius, at.getZ() - radius, at.getX() + radius, at.getY() + radius, at.getZ() + radius);
        for (Entity e : level.getEntities((Entity) null, box, e -> !e.isRemoved() && e.moonrise$getTrackedEntity() != null)) out.put(e.getId(), e);
        return out;
    }

    /** 처음 보는 엔티티: 서버가 플레이어에게 처음 보여 줄 때와 같은 묶음 */
    private void pair(Segment seg, Entity e) {
        // 플레이어는 생성 직전에 항상 프로필을 보낸다 (스냅샷에만 있던 프로필은 연속 재생에선 적용되지 않음)
        if (e instanceof ServerPlayer sp) game(seg, ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(sp)));
        var te = e.moonrise$getTrackedEntity();
        if (te == null) return;
        game(seg, e.getAddEntityPacket(te.serverEntity));
        Seen s = new Seen();
        List<SynchedEntityData.DataValue<?>> data = e.getEntityData().getNonDefaultValues();
        if (data != null && !data.isEmpty()) game(seg, new ClientboundSetEntityDataPacket(e.getId(), data));
        for (var v : e.getEntityData().packAll()) s.data.put(v.id(), copy(v.value()));
        if (e instanceof LivingEntity l) {
            var attrs = l.getAttributes().getSyncableAttributes();
            if (!attrs.isEmpty()) game(seg, new ClientboundUpdateAttributesPacket(e.getId(), attrs));
            List<Pair<EquipmentSlot, ItemStack>> eq = new ArrayList<>();
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack it = l.getItemBySlot(slot).copy();
                s.equipment.put(slot, it);
                if (!it.isEmpty()) eq.add(Pair.of(slot, it.copy()));
            }
            if (!eq.isEmpty()) game(seg, new ClientboundSetEquipmentPacket(e.getId(), eq));
            for (var effect : l.getActiveEffects()) game(seg, new ClientboundUpdateMobEffectPacket(e.getId(), new net.minecraft.world.effect.MobEffectInstance(effect), false));
        }
        s.passengers = e.getPassengers().stream().map(Entity::getId).toList();
        if (!s.passengers.isEmpty()) game(seg, new ClientboundSetPassengersPacket(e));
        seen.put(e.getId(), s);
    }

    private void diffData(Segment seg, Entity e, Seen s) {
        List<SynchedEntityData.DataValue<?>> changed = null;
        for (var v : e.getEntityData().packAll()) {
            Object old = s.data.get(v.id());
            if (same(old, v.value())) continue;
            s.data.put(v.id(), copy(v.value()));
            if (changed == null) changed = new ArrayList<>();
            changed.add(v);
        }
        if (changed != null) game(seg, new ClientboundSetEntityDataPacket(e.getId(), changed));
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof ItemStack x && b instanceof ItemStack y) return ItemStack.matches(x, y);
        return Objects.equals(a, b);
    }

    private static Object copy(Object v) { return v instanceof ItemStack i ? i.copy() : v; }

    private void diffEquipment(Segment seg, LivingEntity l, Seen s) {
        List<Pair<EquipmentSlot, ItemStack>> changed = null;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack now = l.getItemBySlot(slot);
            ItemStack last = s.equipment.get(slot);
            if (last != null && ItemStack.matches(last, now)) continue;
            ItemStack c = now.copy();
            s.equipment.put(slot, c);
            if (changed == null) changed = new ArrayList<>();
            changed.add(Pair.of(slot, c.copy()));
        }
        if (changed != null) game(seg, new ClientboundSetEquipmentPacket(l.getId(), changed));
    }

    private int move(FbOut out, Entity e, Seen s) {
        float head = e instanceof LivingEntity l ? l.getYHeadRot() : e.getYRot();
        Pos p = new Pos(e.getX(), e.getY(), e.getZ(), e.getYRot(), e.getXRot(), head, e.onGround());
        if (p.equals(s.pos)) return 0;
        s.pos = p;
        writePos(out, e.getId(), p);
        return 1;
    }

    private int cameraMove(FbOut out, Location at) {
        Pos p = cameraPose(at);
        if (p.equals(cameraPos)) return 0;
        cameraPos = p;
        writePos(out, cameraId, p);
        return 1;
    }

    /** 고정: 저장한 위치·방향 그대로 / 추적: 대상 뒤 위쪽에서 내려다봄 */
    private Pos cameraPose(Location at) {
        if (!follow) return new Pos(at.getX(), at.getY(), at.getZ(), fixedYaw, fixedPitch, fixedYaw, false);
        return new Pos(at.getX(), at.getY() + 6, at.getZ() - 8, 0f, 35f, 0f, false);
    }

    private static void writePos(FbOut out, int id, Pos p) {
        out.writeVarInt(id);
        out.writeDouble(p.x); out.writeDouble(p.y); out.writeDouble(p.z);
        out.writeFloat(p.yaw); out.writeFloat(p.pitch); out.writeFloat(p.head);
        out.writeBoolean(p.onGround);
    }

    private void writeMoves(Segment seg, FbOut list, int count) {
        FbOut out = new FbOut(list.size() + 64);
        out.writeVarInt(1);
        out.writeUtf(level.dimension().identifier().toString());
        out.writeVarInt(count);
        out.writeBytes(list.array(), 0, list.size());
        seg.action(FlashbackFormat.ID_MOVE_ENTITIES, out.array(), 0, out.size());
    }

    private void game(Segment seg, Packet<?> p) { seg.action(FlashbackFormat.ID_GAME_PACKET, codecs.game(p)); }
    private void game(FbOut out, Packet<?> p) { FlashbackFormat.action(out, FlashbackFormat.ID_GAME_PACKET, codecs.game(p)); }

    /** 청크를 서버에서 만들어 저장소에 넣는다 (같은 내용이면 공유). 반환: 해시, 안 불러와진 청크면 null */
    private String chunk(int x, int z) {
        LevelChunk lc = level.getChunkSource().getChunkNow(x, z);
        if (lc == null) return null;
        String h = store.put(codecs.game(new ClientboundLevelChunkWithLightPacket(lc, level.getLightEngine(), null, null, false)));
        String old = chunks.put(ChunkPos.asLong(x, z), h);
        if (old != null) store.release(old);
        return h;
    }

    // ------------------------------------------------------------------ 스냅샷

    private void startSegment(long now, Location at) {
        FbOut out = new FbOut(1 << 16);
        Set<String> refs = new HashSet<>();
        MinecraftServer server = level.getServer();
        FlashbackFormat.action(out, FlashbackFormat.ID_BLOB_REF, HexFormat.of().parseHex(configBlob));
        refs.add(configBlob);
        FlashbackFormat.action(out, FlashbackFormat.ID_CONFIGURATION_PACKET, codecs.config(new ClientboundResourcePackPopPacket(Optional.empty())));

        var spawn = new CommonPlayerSpawnInfo(level.dimensionTypeRegistration(), level.dimension(), net.minecraft.world.level.biome.BiomeManager.obfuscateSeed(level.getSeed()),
                GameType.SPECTATOR, null, level.isDebug(), level.isFlat(), Optional.empty(), 0, level.getSeaLevel());
        game(out, new ClientboundLoginPacket(cameraId, level.getLevelData().isHardcore(), server.levelKeys(), server.getMaxPlayers(),
                chunkRadius, chunkRadius, false, true, false, spawn, false));
        Pos pose = cameraPose(at);
        cameraPos = pose;
        FlashbackFormat.action(out, FlashbackFormat.ID_CREATE_LOCAL_PLAYER, codecs.write(buf -> {
            buf.writeUUID(profile.id());
            buf.writeDouble(pose.x); buf.writeDouble(pose.y); buf.writeDouble(pose.z);
            buf.writeFloat(pose.pitch); buf.writeFloat(pose.yaw); buf.writeFloat(pose.head);
            buf.writeVec3(net.minecraft.world.phys.Vec3.ZERO);
            ByteBufCodecs.GAME_PROFILE.encode(buf, profile);
            buf.writeVarInt(GameType.SPECTATOR.getId());
        }));
        List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers());
        game(out, ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(players));
        game(out, new ClientboundTabListPacket(Component.empty(), Component.empty()));
        game(out, new ClientboundInitializeBorderPacket(level.getWorldBorder()));
        game(out, new ClientboundSetTimePacket(level.getLevelData().getGameTime(), level.getDayTime(),
                level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME)));
        game(out, new ClientboundSetDefaultSpawnPositionPacket(level.getRespawnData()));
        game(out, new ClientboundGameEventPacket(level.isRaining() ? ClientboundGameEventPacket.START_RAINING : ClientboundGameEventPacket.STOP_RAINING, 0));
        game(out, new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, level.getRainLevel(1f)));
        game(out, new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, level.getThunderLevel(1f)));

        // 청크: 범위 안을 전부 지금 상태로 (바뀐 블록도 여기서 반영됨)
        for (String h : chunks.values()) store.release(h);
        chunks.clear();
        changedBlocks.clear();
        int cx = at.getBlockX() >> 4, cz = at.getBlockZ() >> 4;
        for (int x = cx - chunkRadius; x <= cx + chunkRadius; x++)
            for (int z = cz - chunkRadius; z <= cz + chunkRadius; z++) {
                String h = chunk(x, z);
                if (h == null) continue;
                FlashbackFormat.action(out, FlashbackFormat.ID_CHUNK_REF, HexFormat.of().parseHex(h));
                refs.add(h);
            }

        // 엔티티: 지금 범위 안
        seen.clear();
        pending.clear();
        Segment tmp = new Segment(store, now, new byte[0], Set.of());
        for (Entity e : inRange(at).values()) pair(tmp, e);
        tmp.close();
        out.writeBytes(tmp.actionsCopy());
        tmp.release();

        buffer.startSegment(new Segment(store, now, out.toByteArray(), refs));
        needSnapshot = false;
    }

    // ------------------------------------------------------------------ 정리

    @Override public void stop(String reason) { if (stoppedReason == null) stoppedReason = reason; }

    @Override public List<PlayerBuffer.Clip> finish(long now) {
        List<PlayerBuffer.Clip> clips = new ArrayList<>();
        if (stoppedReason == null && buffer.recording() && buffer.current() != null) {
            buffer.highlight(buffer.labels().iterator().next(), now, now, null);
            buffer.current().close();
            PlayerBuffer.Clip c = buffer.takeClip(now);
            if (c != null) clips.add(c);
        }
        stop("끝");
        return clips;
    }

    @Override public void release() {
        buffer.clear();
        for (String h : chunks.values()) store.release(h);
        chunks.clear();
    }
}
