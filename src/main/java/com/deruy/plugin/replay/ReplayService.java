package com.deruy.plugin.replay;

import com.deruy.plugin.DeruyPlugin;
import com.deruy.plugin.replay.buffer.ChunkStore;
import com.deruy.plugin.replay.buffer.PlayerBuffer;
import com.deruy.plugin.replay.buffer.Segment;
import com.deruy.plugin.replay.format.ReplayArchive;
import com.deruy.plugin.replay.format.ReplayVerifier;
import com.deruy.plugin.replay.nms.Codecs;
import com.deruy.plugin.replay.nms.CameraRecorder;
import com.deruy.plugin.replay.nms.PlayerRecorder;
import com.deruy.plugin.replay.buffer.Recorder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 서버 녹화 하이라이트.
 * 모든 접속자를 서버에서 계속 되감기 버퍼에 녹화하다가, 플레이어끼리 때리면 1분 전부터 싸움이 끝날 때까지,
 * 이벤트가 열리면 그 근처 플레이어를 이벤트 동안 Flashback 리플레이(zip)로 저장한다.
 * 여러 싸움·이벤트가 동시에 나도 플레이어마다 따로 저장되고, 같은 플레이어에 겹치면 한 파일로 합친다.
 */
public final class ReplayService implements Listener {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("HHmmss");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 진행 중인 이벤트: where 가 null 이면 모든 접속자 */
    private record ActiveEvent(String label, String cameraType, Supplier<List<Location>> where, double radius, long until) {}

    /** /camera 로 저장한 고정 카메라 */
    public record CameraDef(String name, String event, Location at, double radius) {}

    private final DeruyPlugin plugin;
    private final Codecs codecs;
    private final ChunkStore store = new ChunkStore();
    private final String configBlob;
    private final Map<UUID, PlayerRecorder> recorders = new HashMap<>();
    /** 지금 돌고 있는 카메라(고정·추적), 이름 → 녹화기 */
    private final Map<String, CameraRecorder> cameras = new LinkedHashMap<>();
    private final Map<String, CameraDef> cameraDefs = new LinkedHashMap<>();
    private int cameraIndex;
    private final Map<String, ActiveEvent> events = new LinkedHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Deruy-Replay"); t.setDaemon(true); t.setPriority(Thread.NORM_PRIORITY - 1); return t;
    });
    private final Path folder;
    private BukkitTask task;
    private long tick;
    private int saved;
    private String lastSaved;

    // 설정
    private final int segmentTicks, preRollTicks, maxClipTicks, fightEndTicks, tailTicks, maxQueued;
    private final long maxBufferBytes, maxDiskBytes;
    private final double markerRadius;
    private final String pvpTrigger;
    private final boolean killcam;
    private final int killcamBefore, killcamAfter;
    private final int edlFps;

    /** 이 버전의 서버 내부 구조에 맞춰 만들었다. 다른 마인크래프트 버전에서는 켜지지 않는다 */
    public static final String SUPPORTED_VERSION = "1.21.11";

    public ReplayService(DeruyPlugin plugin) {
        this.plugin = plugin;
        if (!SUPPORTED_VERSION.equals(Bukkit.getMinecraftVersion()))
            throw new IllegalStateException("서버 녹화는 " + SUPPORTED_VERSION + " 전용입니다 (지금 " + Bukkit.getMinecraftVersion() + ")");
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("replay");
        if (c == null) c = plugin.getConfig().createSection("replay");
        segmentTicks = Math.max(200, c.getInt("segment-seconds", 30) * 20);
        preRollTicks = Math.max(0, c.getInt("pvp.before-seconds", 60) * 20);
        fightEndTicks = Math.max(20, c.getInt("pvp.end-after-seconds", 15) * 20);
        tailTicks = Math.max(0, c.getInt("after-seconds", 5) * 20);
        maxClipTicks = Math.max(segmentTicks * 2, c.getInt("max-clip-minutes", 15) * 1200);
        maxQueued = Math.max(1000, c.getInt("max-queued-packets", 50000));
        maxBufferBytes = Math.max(64, c.getLong("max-buffer-mb", 768)) << 20;
        maxDiskBytes = Math.max(1, c.getLong("max-disk-gb", 20)) << 30;
        markerRadius = c.getDouble("marker-radius", 96);
        pvpTrigger = c.getString("pvp.trigger", "both").toLowerCase(Locale.ROOT);
        killcam = c.getBoolean("killcam.enabled", true);
        killcamBefore = Math.max(1, c.getInt("killcam.before-seconds", 15)) * 20;
        killcamAfter = Math.max(0, c.getInt("killcam.after-seconds", 3)) * 20;
        edlFps = Math.max(1, c.getInt("edl-fps", 60));
        folder = plugin.getDataFolder().toPath().resolve(c.getString("folder", "replays"));
        var server = ((CraftServer) Bukkit.getServer()).getServer();
        codecs = new Codecs(server);
        configBlob = store.put(ConfigBlob.build(server, codecs)); // 서비스가 참조 하나를 계속 들고 있음
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
        for (Player p : Bukkit.getOnlinePlayers()) attach(p);
        loadCameras();
        plugin.getLogger().info("서버 녹화 켜짐: 접속자 되감기 " + preRollTicks / 20 + "초, 구간 " + segmentTicks / 20 + "초 → " + folder);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        HandlerList.unregisterAll(this);
        long now = tick;
        List<Recorder> all = new ArrayList<>(recorders.values());
        all.addAll(cameras.values());
        for (Recorder r : all) {
            try { for (PlayerBuffer.Clip clip : r.finish(now)) export(r, clip); }
            catch (RuntimeException | LinkageError e) { plugin.getLogger().warning("서버 녹화: " + r.name() + " 마무리 실패 — " + e); }
        }
        io.shutdown();
        try { if (!io.awaitTermination(60, TimeUnit.SECONDS)) plugin.getLogger().warning("서버 녹화: 저장이 60초 안에 끝나지 않았습니다."); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        all.forEach(Recorder::release);
        recorders.clear();
        cameras.clear();
        List.copyOf(held.keySet()).forEach(this::releaseChunks);
    }

    // ------------------------------------------------------------------ 녹화기

    private void attach(Player p) {
        PlayerRecorder old = recorders.remove(p.getUniqueId());
        if (old != null) { old.stop("다시 접속"); old.release(); }
        try {
            recorders.put(p.getUniqueId(), new PlayerRecorder(p, codecs, store, new PlayerBuffer(preRollTicks, maxClipTicks), configBlob, segmentTicks, maxQueued));
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().warning("서버 녹화: " + p.getName() + " 녹화를 시작하지 못했습니다 — " + e);
        }
    }

    private void tick() {
        tick++;
        tickAll(recorders.values().iterator(), "퇴장");
        tickAll(cameras.values().iterator(), "끝");
        if (tick % 20 == 0) {
            eventTick();
            combatTagTick();
            memoryTick();
        }
    }

    private void tickAll(Iterator<? extends Recorder> it, String normalStop) {
        while (it.hasNext()) {
            Recorder r = it.next();
            try { for (PlayerBuffer.Clip clip : r.tick(tick)) export(r, clip); }
            catch (RuntimeException | LinkageError e) {
                plugin.getLogger().warning("서버 녹화: " + r.name() + " 녹화 중 오류로 이 녹화를 멈춥니다 — " + e);
                r.stop("오류: " + e);
            }
            // 카메라는 하이라이트가 끝나 저장까지 마치면 쉰다
            if (r instanceof CameraRecorder cam && r.alive() && !cam.buffer().recording()) cam.stop("끝");
            if (!r.alive()) {
                if (!normalStop.equals(r.stoppedReason())) plugin.getLogger().warning("서버 녹화: " + r.name() + " 중단 — " + r.stoppedReason());
                it.remove();
                if (r instanceof CameraRecorder) { releaseChunks(r.name()); cameraUntil.remove(r.name()); }
                io.execute(r::release);
            }
        }
    }

    private void memoryTick() {
        List<Segment> closed = new ArrayList<>(); // 구간 목록은 서버 스레드에서만 만진다
        for (Recorder r : allRecorders()) for (Segment s : r.buffer().segments()) if (s.closed()) closed.add(s);
        io.execute(() -> { store.compressPending(); closed.forEach(Segment::compress); });
        if (memoryBytes() > maxBufferBytes) {
            // 넘치면 녹화 중이 아닌 사람부터 되감기 분량을 버린다
            for (PlayerRecorder r : recorders.values()) if (!r.buffer().recording()) r.buffer().purge(tick + preRollTicks);
        }
    }

    private List<Recorder> allRecorders() {
        List<Recorder> all = new ArrayList<>(recorders.values());
        all.addAll(cameras.values());
        return all;
    }

    public long memoryBytes() {
        long n = store.memoryBytes();
        for (Recorder r : allRecorders()) n += r.buffer().memoryBytes();
        return n;
    }

    // ------------------------------------------------------------------ 하이라이트 걸기

    private void highlight(Player p, String label, long from, long until, String marker, int colour, Location at) {
        PlayerRecorder r = recorders.get(p.getUniqueId());
        if (r == null || !r.alive()) return;
        PlayerBuffer.Marker m = marker == null ? null : new PlayerBuffer.Marker(tick, colour, marker,
                at == null ? null : dimension(at), at == null ? 0 : at.getX(), at == null ? 0 : at.getY(), at == null ? 0 : at.getZ());
        r.buffer().highlight(label, from, until, m);
    }

    private PlayerBuffer.Marker marker(String text, int colour, Location at) {
        return new PlayerBuffer.Marker(tick, colour, text, at == null ? null : dimension(at), at == null ? 0 : at.getX(), at == null ? 0 : at.getY(), at == null ? 0 : at.getZ());
    }

    // ---------------------------------------------------------------- 전투 태그 기준 녹화

    private boolean combatTagAvailable() {
        var ls = plugin.getLifeStealManager();
        return ls != null && ls.isSystemEnabled();
    }

    /** 전투 태그가 붙어 있는 동안 계속 녹화 (태그 중 로그아웃도 퇴장 처리로 저장됨) */
    private void combatTagTick() {
        if (pvpTrigger.equals("hit") || !combatTagAvailable()) return;
        var ls = plugin.getLifeStealManager();
        for (PlayerRecorder r : recorders.values()) {
            Player p = r.player();
            if (!ls.isCombatTagged(p.getUniqueId())) continue;
            boolean first = !r.buffer().labels().contains("PvP");
            highlight(p, "PvP", tick - preRollTicks, tick + 20 + tailTicks, first ? "전투 태그: " + p.getName() : null, 0xFF3333, p.getLocation());
        }
    }

    private static String dimension(Location at) {
        var w = at.getWorld();
        return w == null ? null : w.getKey().toString();
    }

    /** 플레이어끼리 때림 → 둘 다 1분 전부터, 마지막 타격 뒤 end-after 초까지 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = e.getDamager() instanceof Player x ? x
                : e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player x ? x : null;
        if (attacker == null || attacker == victim) return;
        if (pvpTrigger.equals("combat-tag") && combatTagAvailable()) return; // 전투 태그 기준만
        long until = tick + fightEndTicks + tailTicks;
        for (Player p : List.of(attacker, victim)) {
            PlayerRecorder r = recorders.get(p.getUniqueId());
            boolean first = r != null && !r.buffer().labels().contains("PvP");
            Player other = p == attacker ? victim : attacker;
            highlight(p, "PvP", tick - preRollTicks, until, first ? "싸움 시작: " + attacker.getName() + " → " + victim.getName() : null, 0xFF3333, victim.getLocation());
            if (first && r != null) plugin.getLogger().fine("서버 녹화: " + p.getName() + " vs " + other.getName());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getPlayer();
        Player killer = victim.getKiller();
        String text = killer == null ? "사망: " + victim.getName() : "처치: " + killer.getName() + " → " + victim.getName();
        for (Player p : killer == null ? List.of(victim) : List.of(victim, killer)) {
            PlayerRecorder r = recorders.get(p.getUniqueId());
            if (r == null || !r.alive()) continue;
            if (r.buffer().recording()) highlight(p, r.buffer().labels().iterator().next(), tick, tick + tailTicks * 2L, text, 0xAA0000, victim.getLocation());
            // 킬캠: 플레이어에게 죽었을 때 두 사람 시점으로 마지막 장면만 짧게 따로
            if (killcam && killer != null && killer != victim)
                r.buffer().requestExtra("킬캠", tick - killcamBefore, tick + killcamAfter, marker(text, 0xAA0000, victim.getLocation()));
        }
        for (CameraRecorder cam : cameras.values()) cam.entityEvent(victim, "death");
    }

    /** 타임라인에 남는 일과 같은 이름으로 이벤트를 연다/닫는다 (Timeline.log 에서 호출) */
    public void onTimeline(String type, String who, String detail, Location at) {
        if (type.startsWith("REPLAY")) return;
        ConfigurationSection ev = plugin.getConfig().getConfigurationSection("replay.events");
        switch (type) {
            case "RANDOM_EVENT_START" -> {
                if (ev == null || ev.getBoolean("random-event", true))
                    beginEvent("random", "randomevent", "랜덤 이벤트 " + detail, null, 0, 20 * 60 * 30);
            }
            case "RANDOM_EVENT_END" -> endEvent("random");
            case "TRANSPORT_START" -> {
                if (ev == null || ev.getBoolean("transport", true)) {
                    var t = plugin.getTransportManager();
                    beginEvent("transport", "transport", "하트 운송전", () -> { Location l = t == null ? null : t.currentLocation(); return l == null ? List.of() : List.of(l); },
                            ev == null ? 64 : ev.getDouble("transport-radius", 64), 20 * 60 * 40);
                }
                if (followEnabled("transport") && plugin.getTransportManager() != null) {
                    var t = plugin.getTransportManager();
                    startCamera("follow-transport", "하트 운송전", t::currentLocation, followRadius(), true, 20 * 60 * 40);
                }
            }
            case "TRANSPORT_ARRIVED", "TRANSPORT_DESTROYED" -> { endEvent("transport"); endCamera("follow-transport"); }
            case "KOTH_START" -> { // 일반·슈퍼 KOTH 가 동시에 열릴 수 있어 이름(who)별로 따로
                if (ev == null || ev.getBoolean("koth", true))
                    beginEvent("koth-" + who, "koth", "KOTH " + who, () -> kothCenters(who), ev == null ? 48 : ev.getDouble("koth-radius", 48), 20 * 60 * 40);
            }
            case "KOTH_END" -> endEvent("koth-" + who);
            case "SUPPLY_DROP" -> {
                if (ev == null || ev.getBoolean("supply-drop", true)) {
                    int minutes = 10;
                    try { minutes = Integer.parseInt(detail.replaceAll(".*/\\s*(\\d+)분.*", "$1")); } catch (NumberFormatException ignored) { }
                    var reg = plugin.getSupplyChestRegistry();
                    beginEvent("supply-" + who, "supply", "보급 " + who, () -> reg == null ? List.of() : reg.locations(),
                            ev == null ? 32 : ev.getDouble("supply-radius", 32), Math.max(1, minutes) * 1200L);
                }
            }
            default -> { }
        }
        // 위치가 있는 일은 근처에서 녹화 중인 사람 파일에 마커로 (하트 증감은 너무 잦아 제외)
        if (at != null && at.getWorld() != null && !type.equals("HEART")) {
            for (PlayerRecorder r : recorders.values()) {
                if (!r.buffer().recording()) continue;
                Player p = r.player();
                if (p.getWorld() != at.getWorld() || p.getLocation().distanceSquared(at) > markerRadius * markerRadius) continue;
                highlight(p, r.buffer().labels().iterator().next(), tick, tick, type + " " + who + (detail.isEmpty() ? "" : " " + detail), 0xFFAA00, at);
            }
        }
    }

    private List<Location> kothCenters(String label) {
        List<Location> out = new ArrayList<>();
        for (var k : List.of(plugin.getKothManager(), plugin.getSuperKothManager())) {
            if (k == null || !k.isRunning() || !label.equals(k.getLabel())) continue;
            for (String id : k.getActiveZoneIds()) {
                var z = k.getZones().get(id);
                if (z == null || !z.isComplete()) continue;
                out.add(z.getCorner1().clone().add(z.getCorner2()).multiply(0.5));
            }
        }
        return out;
    }

    /** @param cameraType 이 이벤트에 붙여 둔 고정 카메라 종류(/camera add 의 이벤트 이름) */
    public void beginEvent(String key, String cameraType, String label, Supplier<List<Location>> where, double radius, long maxTicks) {
        ActiveEvent old = events.get(key);
        events.put(key, new ActiveEvent(label, cameraType, where, radius, tick + maxTicks));
        if (old != null && old.label().equals(label)) { // 이어지는 같은 이벤트(드래곤 등): 붙은 카메라 기한도 함께 연장
            for (CameraDef d : cameraDefs.values())
                if (d.event().equalsIgnoreCase(cameraType) && cameraUntil.containsKey("cam-" + d.name())) cameraUntil.put("cam-" + d.name(), tick + maxTicks);
            return;
        }
        for (CameraDef d : cameraDefs.values())
            if (d.event().equalsIgnoreCase(cameraType)) startCamera("cam-" + d.name(), label, () -> d.at().clone(), d.radius(), false, maxTicks);
        eventTick(); // 시작 순간에 있던 사람 바로 포함 + 시작 마커
        for (PlayerRecorder r : recorders.values()) {
            if (r.buffer().labels().contains(label)) highlight(r.player(), label, tick, tick, label + " 시작", 0x33AAFF, null);
        }
    }

    public void endEvent(String key) {
        ActiveEvent e = events.remove(key);
        if (e == null) return;
        endTypeCamerasIfIdle(e.cameraType());
        for (PlayerRecorder r : recorders.values()) if (r.buffer().labels().contains(e.label()))
            highlight(r.player(), e.label(), tick, tick + tailTicks, e.label() + " 끝", 0x33AAFF, null);
    }

    private void eventTick() {
        for (var it = events.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            if (tick <= en.getValue().until()) continue;
            it.remove(); // 기한이 지난 이벤트(끝 신호를 못 받음)는 카메라도 정리
            endTypeCamerasIfIdle(en.getValue().cameraType());
            if (en.getKey().equals("dragon")) endCamera("follow-dragon");
        }
        // 돌고 있는 카메라: 이벤트 동안 계속 녹화
        for (CameraRecorder cam : cameras.values()) {
            Long until = cameraUntil.get(cam.name());
            if (until != null && tick <= until) cam.buffer().highlight(cam.buffer().recording() ? cam.buffer().labels().iterator().next() : cameraLabel.get(cam.name()),
                    tick, tick + 40 + tailTicks, null);
        }
        for (ActiveEvent e : events.values()) {
            List<Location> where = e.where() == null ? null : e.where().get();
            for (PlayerRecorder r : recorders.values()) {
                Player p = r.player();
                if (where != null && where.stream().noneMatch(l -> l.getWorld() == p.getWorld() && l.distanceSquared(p.getLocation()) <= e.radius() * e.radius())) continue;
                // 이벤트는 들어온 순간 30초 전부터 보이게, 떠나도 꼬리만큼 더
                highlight(p, e.label(), tick - Math.min(preRollTicks, 600), tick + 40 + tailTicks, null, 0, null);
            }
        }
    }

    // ------------------------------------------------------------------ 카메라

    private final Map<String, Long> cameraUntil = new HashMap<>();
    private final Map<String, String> cameraLabel = new HashMap<>();

    private boolean followEnabled(String what) { return plugin.getConfig().getBoolean("replay.follow." + what, true); }
    private double followRadius() { return plugin.getConfig().getDouble("replay.follow.radius", 48); }

    /** 카메라 녹화 시작(이미 돌고 있으면 기한만 연장). 시작점은 지금 — 카메라는 되감기 버퍼가 없다 */
    public boolean startCamera(String key, String label, Supplier<Location> where, double radius, boolean follow, long maxTicks) {
        CameraRecorder cam = cameras.get(key);
        if (cam == null || !cam.alive()) {
            if (where.get() == null) return false;
            try {
                cam = new CameraRecorder(key, cameraIndex++ % 1000, codecs, store, new PlayerBuffer(0, maxClipTicks), configBlob, segmentTicks, where, radius, follow);
            } catch (RuntimeException | LinkageError e) {
                plugin.getLogger().warning("서버 녹화: 카메라 " + key + " 시작 실패 — " + e);
                return false;
            }
            cameras.put(key, cam);
            if (!follow) holdChunks(key, where.get(), radius); // 사람이 없어도 지형이 불러와져 있도록
            cam.tick(tick); // 첫 스냅샷
            cam.buffer().highlight(label, tick, tick + 40 + tailTicks, new PlayerBuffer.Marker(tick, 0x33AAFF, label + " 시작", null, 0, 0, 0));
        }
        cameraLabel.put(key, label);
        cameraUntil.put(key, tick + maxTicks);
        return true;
    }

    /** 같은 종류(예: 일반·슈퍼 KOTH)의 다른 이벤트가 아직 진행 중이면 그 종류 카메라는 계속 */
    private void endTypeCamerasIfIdle(String cameraType) {
        if (cameraType == null) return;
        for (ActiveEvent other : events.values()) if (cameraType.equalsIgnoreCase(other.cameraType())) return;
        for (CameraDef d : cameraDefs.values()) if (d.event().equalsIgnoreCase(cameraType)) endCamera("cam-" + d.name());
    }

    /** 이벤트 끝: 꼬리만큼 더 찍고 저장되면 카메라는 멈춘다 */
    public void endCamera(String key) {
        cameraUntil.remove(key);
        CameraRecorder cam = cameras.get(key);
        if (cam != null && cam.buffer().recording())
            cam.buffer().highlight(cam.buffer().labels().iterator().next(), tick, tick + tailTicks, new PlayerBuffer.Marker(tick, 0x33AAFF, "끝", null, 0, 0, 0));
    }

    /** 고정 카메라 범위 청크를 불러와 붙잡아 둔다. 여러 카메라가 같은 청크를 보면 마지막 카메라가 멈출 때만 놓는다 */
    private final Map<String, List<org.bukkit.Chunk>> held = new HashMap<>();
    private final Map<org.bukkit.Chunk, Integer> heldCount = new HashMap<>();

    private void holdChunks(String key, Location at, double radius) {
        if (at == null || at.getWorld() == null) return;
        int r = (int) Math.ceil(radius / 16.0) + 1, cx = at.getBlockX() >> 4, cz = at.getBlockZ() >> 4;
        List<org.bukkit.Chunk> list = new ArrayList<>();
        held.put(key, list);
        for (int x = cx - r; x <= cx + r; x++) for (int z = cz - r; z <= cz + r; z++)
            at.getWorld().getChunkAtAsync(x, z).thenAccept(c -> {
                if (held.get(key) != list) return; // 그 사이 멈춤
                if (heldCount.merge(c, 1, Integer::sum) == 1) c.addPluginChunkTicket(plugin);
                list.add(c);
            });
    }

    private void releaseChunks(String key) {
        List<org.bukkit.Chunk> list = held.remove(key);
        if (list == null) return;
        for (var c : list) {
            Integer n = heldCount.merge(c, -1, Integer::sum);
            if (n == null || n <= 0) { heldCount.remove(c); c.removePluginChunkTicket(plugin); }
        }
    }

    /** 정의를 바꾸면 돌고 있던 녹화기는 지금까지를 저장하고 새로 시작 */
    private void retireCamera(String key) {
        CameraRecorder cam = cameras.remove(key);
        cameraUntil.remove(key);
        releaseChunks(key);
        if (cam == null) return;
        try { for (PlayerBuffer.Clip clip : cam.finish(tick)) export(cam, clip); }
        catch (RuntimeException | LinkageError e) { plugin.getLogger().warning("서버 녹화: 카메라 " + key + " 마무리 실패 — " + e); }
        io.execute(cam::release);
    }

    private void loadCameras() {
        cameraDefs.clear();
        var sec = plugin.getDataStore() == null ? null : plugin.getDataStore().section("replay-cameras");
        if (sec == null) return;
        for (String name : sec.getKeys(false)) {
            var c = sec.getConfigurationSection(name);
            Location at = c == null ? null : c.getLocation("at");
            if (at == null || at.getWorld() == null) { plugin.getLogger().warning("서버 녹화: 카메라 " + name + " 위치를 읽지 못함(월드 없음?)"); continue; }
            cameraDefs.put(name, new CameraDef(name, c.getString("event", "always"), at, c.getDouble("radius", 32)));
        }
        for (CameraDef d : cameraDefs.values()) if (d.event().equalsIgnoreCase("always")) startCamera("cam-" + d.name(), "카메라 " + d.name(), () -> d.at().clone(), d.radius(), false, Long.MAX_VALUE / 4);
    }

    public static final List<String> CAMERA_EVENTS = List.of("koth", "transport", "randomevent", "supply", "dragon", "always");

    public void addCamera(String name, String event, Location at, double radius) {
        var ds = plugin.getDataStore();
        ds.set("replay-cameras." + name + ".event", event.toLowerCase(Locale.ROOT));
        ds.set("replay-cameras." + name + ".at", at.clone());
        ds.set("replay-cameras." + name + ".radius", radius);
        ds.save();
        retireCamera("cam-" + name);
        loadCameras();
        // 이미 진행 중인 같은 종류 이벤트가 있으면 새 카메라도 바로 합류
        for (ActiveEvent e : events.values()) if (event.equalsIgnoreCase(e.cameraType())) {
            CameraDef d = cameraDefs.get(name);
            if (d != null) startCamera("cam-" + name, e.label(), () -> d.at().clone(), d.radius(), false, e.until() - tick);
        }
    }

    public boolean removeCamera(String name) {
        if (!cameraDefs.containsKey(name)) return false;
        var ds = plugin.getDataStore();
        ds.set("replay-cameras." + name, null);
        ds.save();
        retireCamera("cam-" + name);
        cameraDefs.remove(name);
        return true;
    }

    public Collection<CameraDef> cameraDefs() { return Collections.unmodifiableCollection(cameraDefs.values()); }

    /** 시험: 이벤트와 상관없이 지금부터 seconds 초 녹화 */
    public boolean testCamera(String name, int seconds) {
        CameraDef d = cameraDefs.get(name);
        if (d == null) return false;
        boolean ok = startCamera("cam-" + d.name(), "카메라 시험 " + d.name(), () -> d.at().clone(), d.radius(), false, seconds * 20L);
        if (ok) Bukkit.getScheduler().runTaskLater(plugin, () -> endCamera("cam-" + d.name()), seconds * 20L);
        return ok;
    }

    // 엔더 드래곤: 플레이어가 처음 때리면 이벤트 시작, 2분간 아무도 안 때리거나 죽으면 끝
    private void dragonHit(org.bukkit.entity.EnderDragon dragon) {
        if (!plugin.getConfig().getBoolean("replay.events.dragon", true)) return;
        var ref = new java.lang.ref.WeakReference<>(dragon);
        Supplier<Location> at = () -> { var d = ref.get(); return d == null || !d.isValid() ? null : d.getLocation(); };
        beginEvent("dragon", "dragon", "엔더 드래곤", () -> { Location l = at.get(); return l == null ? List.of() : List.of(l); },
                plugin.getConfig().getDouble("replay.events.dragon-radius", 128), 20 * 120);
        if (followEnabled("dragon")) startCamera("follow-dragon", "엔더 드래곤", at, followRadius(), true, 20 * 120);
    }

    // 카메라용 블록·엔티티 소식 (반경 밖이면 각 카메라가 무시)
    private void blocks(org.bukkit.block.Block... bs) {
        if (cameras.isEmpty()) return;
        for (CameraRecorder cam : cameras.values()) for (var b : bs) cam.blockChanged(b.getWorld(), b.getX(), b.getY(), b.getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(org.bukkit.event.block.BlockBreakEvent e) {
        if (cameras.isEmpty()) return;
        var before = e.getBlock().getBlockData().clone();
        for (CameraRecorder cam : cameras.values()) cam.blockBroken(e.getBlock(), before);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onPlace(org.bukkit.event.block.BlockPlaceEvent e) { blocks(e.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onExplode(org.bukkit.event.entity.EntityExplodeEvent e) { blocks(e.blockList().toArray(org.bukkit.block.Block[]::new)); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onBlockExplode(org.bukkit.event.block.BlockExplodeEvent e) { blocks(e.blockList().toArray(org.bukkit.block.Block[]::new)); blocks(e.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onBurn(org.bukkit.event.block.BlockBurnEvent e) { blocks(e.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onFade(org.bukkit.event.block.BlockFadeEvent e) { blocks(e.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onForm(org.bukkit.event.block.BlockFormEvent e) { blocks(e.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onFlow(org.bukkit.event.block.BlockFromToEvent e) { blocks(e.getToBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onIgnite(org.bukkit.event.block.BlockIgniteEvent e) { blocks(e.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onChange(org.bukkit.event.entity.EntityChangeBlockEvent e) { blocks(e.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onPistonOut(org.bukkit.event.block.BlockPistonExtendEvent e) {
        List<org.bukkit.block.Block> list = new ArrayList<>(e.getBlocks()); list.add(e.getBlock());
        for (var b : e.getBlocks()) list.add(b.getRelative(e.getDirection()));
        list.add(e.getBlock().getRelative(e.getDirection()));
        blocks(list.toArray(org.bukkit.block.Block[]::new));
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onPistonIn(org.bukkit.event.block.BlockPistonRetractEvent e) {
        List<org.bukkit.block.Block> list = new ArrayList<>(e.getBlocks()); list.add(e.getBlock());
        for (var b : e.getBlocks()) list.add(b.getRelative(e.getDirection()));
        list.add(e.getBlock().getRelative(e.getDirection().getOppositeFace()));
        blocks(list.toArray(org.bukkit.block.Block[]::new));
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onInteract(org.bukkit.event.player.PlayerInteractEvent e) {
        if (e.getClickedBlock() != null) Bukkit.getScheduler().runTask(plugin, () -> blocks(e.getClickedBlock())); // 문·레버·버튼 등은 다음 틱 상태로
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnyDamage(org.bukkit.event.entity.EntityDamageEvent e) {
        if (e.getEntity() instanceof org.bukkit.entity.EnderDragon d && e instanceof EntityDamageByEntityEvent by
                && (by.getDamager() instanceof Player || by.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player)) dragonHit(d);
        if (e.getEntity() instanceof org.bukkit.entity.EnderDragonPart part && part.getParent() instanceof org.bukkit.entity.EnderDragon d) dragonHit(d);
        for (CameraRecorder cam : cameras.values()) cam.entityEvent(e.getEntity(), "hurt");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(org.bukkit.event.entity.EntityDeathEvent e) {
        if (e.getEntity() instanceof Player) return; // 플레이어는 onDeath
        for (CameraRecorder cam : cameras.values()) cam.entityEvent(e.getEntity(), "death");
        if (e.getEntity() instanceof org.bukkit.entity.EnderDragon) { endEvent("dragon"); endCamera("follow-dragon"); }
    }

    // ------------------------------------------------------------------ 저장

    private void export(Recorder r, PlayerBuffer.Clip clip) { export(r.name(), clip); }

    /** 저장 예약. 어떤 경우에도 예외를 밖으로 던지지 않고, 실패해도 청크 참조는 돌려준다 */
    private void export(String player, PlayerBuffer.Clip clip) {
        try { exportUnsafe(player, clip); }
        catch (RuntimeException e) {
            clip.done();
            plugin.getLogger().warning("서버 녹화 저장 준비 실패 (" + player + "): " + e);
        }
    }

    private void exportUnsafe(String player, PlayerBuffer.Clip clip) {
        LocalDateTime now = LocalDateTime.now();
        String labels = String.join("+", clip.labels());
        long ticks = clip.endTick() - clip.startTick();
        // 파일 이름은 영문만(서버 OS 문자 설정에 따라 한글 경로가 깨질 수 있음). 한글 이름은 리플레이 제목에 들어간다
        String file = FILE_TIME.format(now) + "_" + clip.labels().stream().map(ReplayService::tag).distinct().reduce((a, b) -> a + "+" + b).orElse("clip")
                + "_" + safe(player) + (clip.continued() ? "_cont" : "") + ".zip";
        Path dir = folder.resolve(DAY.format(now));
        String name = labels + " - " + player + " - " + now.format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) + (clip.continued() ? " (이어서)" : "");
        List<ReplayArchive.Marker> markers = new ArrayList<>();
        for (PlayerBuffer.Marker m : clip.markers())
            markers.add(new ReplayArchive.Marker((int) (m.tick() - clip.startTick()), m.colour(), m.description(), m.dimension(), m.x(), m.y(), m.z()));
        // 구간 바이트는 저장 스레드에서 꺼낸다 (닫힌 구간은 바뀌지 않음)
        io.execute(() -> {
            try {
                Path out = unique(dir, file); // 같은 초·같은 사람 저장이 겹쳐도 덮어쓰지 않게 (저장 스레드 하나라 경쟁 없음)
                List<ReplayArchive.Part> parts = new ArrayList<>();
                for (Segment s : clip.segments()) parts.add(new ReplayArchive.Part(s.snapshot(), s.actionsCopy(), s.ticks()));
                int written = ReplayArchive.write(out, new ReplayArchive.Meta(UUID.randomUUID(), name, Bukkit.getMotd(),
                        Codecs.versionName(), Codecs.dataVersion(), Codecs.protocolVersion()), parts, markers, store::get, (int) ticks);
                long size = Files.size(out);
                bundle(out, name, markers, labels, player, written);
                trimDisk();
                String rel = folder.relativize(out).toString().replace('\\', '/');
                plugin.getLogger().info("서버 녹화 저장: " + rel + " (" + written / 20 + "초, " + size / 1024 / 1024 + "MB)");
                if (!plugin.isEnabled()) return; // 서버 종료 중: 파일만 남기고 알림은 생략
                try { Bukkit.getScheduler().runTask(plugin, () -> {
                    saved++;
                    lastSaved = rel;
                    String msg = "§d[서버 녹화] §f" + rel + " §7(" + written / 20 + "초, " + size / 1024 / 1024 + "MB)";
                    Bukkit.getOnlinePlayers().stream().filter(p -> p.hasPermission("deruy.admin")).forEach(p -> p.sendMessage(msg));
                    com.deruy.plugin.timeline.Timeline.log(plugin, "REPLAY_SAVED", player, rel, null);
                }); } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) { } // 그 사이 꺼짐
            } catch (Exception e) {
                plugin.getLogger().warning("서버 녹화 저장 실패 (" + player + "): " + e);
            } finally {
                clip.done();
            }
        });
    }

    /** 편집용 묶음: 리플레이 옆에 Resolve 마커(.edl), 날짜 폴더에 index.csv·그날 타임라인 사본 */
    private void bundle(Path zip, String title, List<ReplayArchive.Marker> markers, String labels, String who, int ticks) {
        try {
            Path dir = zip.getParent();
            String base = zip.getFileName().toString().replaceAll("\\.zip$", "");
            if (!markers.isEmpty()) Files.writeString(dir.resolve(base + ".edl"), com.deruy.plugin.replay.format.EditBundle.edl(title, markers, edlFps), java.nio.charset.StandardCharsets.UTF_8);
            com.deruy.plugin.replay.format.EditBundle.appendIndex(dir, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    zip.getFileName().toString(), labels, who, ticks / 20, markers.size());
            var tl = plugin.getTimeline();
            if (tl != null) {
                tl.flush();
                Path src = tl.fileFor(java.time.LocalDate.parse(dir.getFileName().toString()));
                if (Files.exists(src)) Files.copy(src, dir.resolve("timeline.csv"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) { plugin.getLogger().warning("서버 녹화: 편집용 묶음 쓰기 실패 — " + e); }
    }

    public static Path unique(Path dir, String file) {
        Path p = dir.resolve(file);
        String base = file.substring(0, file.length() - 4);
        for (int i = 2; Files.exists(p) || Files.exists(p.resolveSibling(p.getFileName() + ".part")); i++) p = dir.resolve(base + "_" + i + ".zip");
        return p;
    }

    private static String safe(String s) { String t = s.replaceAll("[^0-9A-Za-z_-]", ""); return t.isEmpty() ? "player" : t; }

    /** 하이라이트 이름 → 파일 이름용 영문 */
    public static String tag(String label) {
        if (label.equals("PvP")) return "pvp";
        if (label.startsWith("랜덤 이벤트")) return "randomevent";
        if (label.startsWith("하트 운송전")) return "transport";
        if (label.startsWith("KOTH")) return "koth";
        if (label.startsWith("보급")) return "supply";
        if (label.equals("수동")) return "manual";
        if (label.equals("퇴장")) return "quit";
        if (label.equals("킬캠")) return "killcam";
        if (label.startsWith("엔더 드래곤")) return "dragon";
        if (label.startsWith("카메라")) return "camera";
        String t = label.replaceAll("[^0-9A-Za-z_-]", "").toLowerCase(Locale.ROOT);
        return t.isEmpty() ? "event" : t;
    }

    private void trimDisk() {
        try (Stream<Path> walk = Files.walk(folder)) {
            List<Path> zips = walk.filter(p -> p.toString().endsWith(".zip")).sorted(Comparator.comparingLong(p -> p.toFile().lastModified())).toList();
            long total = 0;
            for (Path p : zips) total += Files.size(p);
            for (Path p : zips) {
                if (total <= maxDiskBytes) break;
                total -= Files.size(p);
                Files.deleteIfExists(p);
                plugin.getLogger().info("서버 녹화: 용량 상한으로 오래된 파일 삭제 " + p.getFileName());
            }
        } catch (IOException e) { plugin.getLogger().warning("서버 녹화: 용량 정리 실패 " + e); }
    }

    // ------------------------------------------------------------------ 명령 지원

    /** /deruy replay save <플레이어>: 지금 그 사람의 되감기 버퍼를 바로 저장 */
    public boolean saveNow(Player p, String label) {
        PlayerRecorder r = recorders.get(p.getUniqueId());
        if (r == null || !r.alive()) return false;
        r.buffer().highlight(label, tick - preRollTicks, tick, new PlayerBuffer.Marker(tick, 0x55FF55, "수동 저장", null, 0, 0, 0));
        return true;
    }

    public List<String> status() {
        List<String> out = new ArrayList<>();
        out.add("§d[서버 녹화] §f녹화 중 " + recorders.size() + "명 §7/ 버퍼 " + memoryBytes() / 1024 / 1024 + "MB (청크 " + store.size() + "개) §7/ 저장 " + saved + "개");
        for (PlayerRecorder r : recorders.values()) {
            var b = r.buffer();
            if (b.recording()) out.add("§7- §f" + r.name + " §c● " + String.join("+", b.labels()) + " §7(" + (tick - b.windowStart()) / 20 + "초째)");
        }
        if (!events.isEmpty()) out.add("§7진행 중 이벤트: §f" + events.values().stream().map(ActiveEvent::label).toList());
        for (CameraRecorder cam : cameras.values())
            out.add("§7- §b" + cam.name() + " §c● " + String.join("+", cam.buffer().labels()) + (cam.buffer().recording() ? " §7(" + (tick - cam.buffer().windowStart()) / 20 + "초째)" : ""));
        out.add("§7카메라 " + cameraDefs.size() + "대 등록 §7/ 킬캠 " + (killcam ? "§a켜짐" : "§7꺼짐") + " §7/ PvP 기준 §f" + pvpTrigger);
        if (lastSaved != null) out.add("§7마지막 저장: §f" + lastSaved);
        out.add("§7폴더: §fplugins/" + plugin.getName() + "/" + plugin.getDataFolder().toPath().relativize(folder));
        return out;
    }

    public List<String> recent(int n) {
        if (!Files.isDirectory(folder)) return List.of();
        try (Stream<Path> walk = Files.walk(folder)) {
            return walk.filter(p -> p.toString().endsWith(".zip")).sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                    .limit(n).map(p -> folder.relativize(p).toString().replace('\\', '/')).toList();
        } catch (IOException e) { return List.of(); }
    }

    /** 저장된 파일을 끝까지 읽고, 모든 패킷을 서버 코덱으로 디코드해 본다 */
    public ReplayVerifier.Report verify(String relative) throws IOException {
        Path p = folder.resolve(relative).normalize();
        if (!p.startsWith(folder) || !Files.isRegularFile(p)) throw new IOException("파일이 없습니다: " + relative);
        return ReplayVerifier.verify(p, (kind, data, off, len) -> {
            switch (kind) {
                case "config" -> codecs.decodeConfig(data, off, len);
                default -> codecs.decodeGame(data, off, len);
            }
        });
    }

    public Path folder() { return folder; }

    // ------------------------------------------------------------------ 접속·퇴장·팔 휘두르기

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) { attach(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        PlayerRecorder r = recorders.remove(e.getPlayer().getUniqueId());
        if (r == null) return;
        try {
            for (PlayerBuffer.Clip clip : r.finish(tick)) export(r, clip);
        } catch (RuntimeException | LinkageError ex) {
            plugin.getLogger().warning("서버 녹화: " + r.name + " 퇴장 처리 실패 — " + ex);
        }
        io.execute(r::release);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent e) {
        PlayerRecorder r = recorders.get(e.getPlayer().getUniqueId());
        boolean main = e.getAnimationType() != PlayerAnimationType.OFF_ARM_SWING;
        if (r != null) r.swing(main);
        for (CameraRecorder cam : cameras.values()) cam.entityEvent(e.getPlayer(), main ? "swing" : "swing-off");
    }
}
