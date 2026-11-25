package com.deruy.plugin.replay;

import com.deruy.plugin.DeruyPlugin;
import com.deruy.plugin.replay.buffer.ChunkStore;
import com.deruy.plugin.replay.buffer.PlayerBuffer;
import com.deruy.plugin.replay.buffer.Segment;
import com.deruy.plugin.replay.format.ReplayArchive;
import com.deruy.plugin.replay.format.ReplayVerifier;
import com.deruy.plugin.replay.nms.Codecs;
import com.deruy.plugin.replay.nms.PlayerRecorder;
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
    private record ActiveEvent(String label, Supplier<List<Location>> where, double radius, long until) {}

    private final DeruyPlugin plugin;
    private final Codecs codecs;
    private final ChunkStore store = new ChunkStore();
    private final String configBlob;
    private final Map<UUID, PlayerRecorder> recorders = new HashMap<>();
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
        folder = plugin.getDataFolder().toPath().resolve(c.getString("folder", "replays"));
        var server = ((CraftServer) Bukkit.getServer()).getServer();
        codecs = new Codecs(server);
        configBlob = store.put(ConfigBlob.build(server, codecs)); // 서비스가 참조 하나를 계속 들고 있음
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
        for (Player p : Bukkit.getOnlinePlayers()) attach(p);
        plugin.getLogger().info("서버 녹화 켜짐: 접속자 되감기 " + preRollTicks / 20 + "초, 구간 " + segmentTicks / 20 + "초 → " + folder);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        HandlerList.unregisterAll(this);
        long now = tick;
        for (PlayerRecorder r : List.copyOf(recorders.values())) {
            try {
                PlayerBuffer.Clip clip = r.finish(now);
                if (clip != null) export(r.name, clip);
            } catch (RuntimeException | LinkageError e) { plugin.getLogger().warning("서버 녹화: " + r.name + " 마무리 실패 — " + e); }
        }
        io.shutdown();
        try { if (!io.awaitTermination(60, TimeUnit.SECONDS)) plugin.getLogger().warning("서버 녹화: 저장이 60초 안에 끝나지 않았습니다."); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        recorders.values().forEach(PlayerRecorder::release);
        recorders.clear();
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
        for (var it = recorders.values().iterator(); it.hasNext(); ) {
            PlayerRecorder r = it.next();
            PlayerBuffer.Clip clip;
            try { clip = r.tick(tick); }
            catch (RuntimeException | LinkageError e) {
                plugin.getLogger().warning("서버 녹화: " + r.name + " 녹화 중 오류로 이 사람 녹화를 멈춥니다 — " + e);
                r.stop("오류: " + e); clip = null;
            }
            if (clip != null) export(r.name, clip);
            if (!r.alive()) {
                if (!"퇴장".equals(r.stoppedReason())) plugin.getLogger().warning("서버 녹화: " + r.name + " 중단 — " + r.stoppedReason());
                it.remove();
                io.execute(r::release);
            }
        }
        if (tick % 20 == 0) {
            eventTick();
            memoryTick();
        }
    }

    private void memoryTick() {
        List<Segment> closed = new ArrayList<>(); // 구간 목록은 서버 스레드에서만 만진다
        for (PlayerRecorder r : recorders.values()) for (Segment s : r.buffer().segments()) if (s.closed()) closed.add(s);
        io.execute(() -> { store.compressPending(); closed.forEach(Segment::compress); });
        if (memoryBytes() > maxBufferBytes) {
            // 넘치면 녹화 중이 아닌 사람부터 되감기 분량을 버린다
            for (PlayerRecorder r : recorders.values()) if (!r.buffer().recording()) r.buffer().purge(tick + preRollTicks);
        }
    }

    public long memoryBytes() {
        long n = store.memoryBytes();
        for (PlayerRecorder r : recorders.values()) n += r.buffer().memoryBytes();
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
            if (r != null && r.buffer().recording()) highlight(p, r.buffer().labels().iterator().next(), tick, tick + tailTicks * 2L, text, 0xAA0000, victim.getLocation());
        }
    }

    /** 타임라인에 남는 일과 같은 이름으로 이벤트를 연다/닫는다 (Timeline.log 에서 호출) */
    public void onTimeline(String type, String who, String detail, Location at) {
        if (type.startsWith("REPLAY")) return;
        ConfigurationSection ev = plugin.getConfig().getConfigurationSection("replay.events");
        switch (type) {
            case "RANDOM_EVENT_START" -> {
                if (ev == null || ev.getBoolean("random-event", true))
                    beginEvent("random", "랜덤 이벤트 " + detail, null, 0, 20 * 60 * 30);
            }
            case "RANDOM_EVENT_END" -> endEvent("random");
            case "TRANSPORT_START" -> {
                if (ev == null || ev.getBoolean("transport", true)) {
                    var t = plugin.getTransportManager();
                    beginEvent("transport", "하트 운송전", () -> { Location l = t == null ? null : t.currentLocation(); return l == null ? List.of() : List.of(l); },
                            ev == null ? 64 : ev.getDouble("transport-radius", 64), 20 * 60 * 40);
                }
            }
            case "TRANSPORT_ARRIVED", "TRANSPORT_DESTROYED" -> endEvent("transport");
            case "KOTH_START" -> {
                if (ev == null || ev.getBoolean("koth", true))
                    beginEvent("koth", "KOTH " + who, this::kothCenters, ev == null ? 48 : ev.getDouble("koth-radius", 48), 20 * 60 * 40);
            }
            case "KOTH_END" -> endEvent("koth");
            case "SUPPLY_DROP" -> {
                if (ev == null || ev.getBoolean("supply-drop", true)) {
                    int minutes = 10;
                    try { minutes = Integer.parseInt(detail.replaceAll(".*/\\s*(\\d+)분.*", "$1")); } catch (NumberFormatException ignored) { }
                    var reg = plugin.getSupplyChestRegistry();
                    beginEvent("supply-" + who, "보급 " + who, () -> reg == null ? List.of() : reg.locations(),
                            ev == null ? 32 : ev.getDouble("supply-radius", 32), Math.max(1, minutes) * 1200L);
                }
            }
            default -> { }
        }
        // 위치가 있는 일은 근처에서 녹화 중인 사람 파일에 마커로
        if (at != null && at.getWorld() != null) {
            for (PlayerRecorder r : recorders.values()) {
                if (!r.buffer().recording()) continue;
                Player p = r.player();
                if (p.getWorld() != at.getWorld() || p.getLocation().distanceSquared(at) > markerRadius * markerRadius) continue;
                highlight(p, r.buffer().labels().iterator().next(), tick, tick, type + " " + who + (detail.isEmpty() ? "" : " " + detail), 0xFFAA00, at);
            }
        }
    }

    private List<Location> kothCenters() {
        List<Location> out = new ArrayList<>();
        for (var k : List.of(plugin.getKothManager(), plugin.getSuperKothManager())) {
            if (k == null || !k.isRunning()) continue;
            for (String id : k.getActiveZoneIds()) {
                var z = k.getZones().get(id);
                if (z == null || !z.isComplete()) continue;
                out.add(z.getCorner1().clone().add(z.getCorner2()).multiply(0.5));
            }
        }
        return out;
    }

    public void beginEvent(String key, String label, Supplier<List<Location>> where, double radius, long maxTicks) {
        events.put(key, new ActiveEvent(label, where, radius, tick + maxTicks));
        eventTick(); // 시작 순간에 있던 사람 바로 포함 + 시작 마커
        for (PlayerRecorder r : recorders.values()) {
            if (r.buffer().labels().contains(label)) highlight(r.player(), label, tick, tick, label + " 시작", 0x33AAFF, null);
        }
    }

    public void endEvent(String key) {
        ActiveEvent e = events.remove(key);
        if (e == null) return;
        for (PlayerRecorder r : recorders.values()) if (r.buffer().labels().contains(e.label()))
            highlight(r.player(), e.label(), tick, tick + tailTicks, e.label() + " 끝", 0x33AAFF, null);
    }

    private void eventTick() {
        events.values().removeIf(e -> tick > e.until());
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

    // ------------------------------------------------------------------ 저장

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
        Path out = folder.resolve(DAY.format(now)).resolve(file);
        String name = labels + " - " + player + " - " + now.format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) + (clip.continued() ? " (이어서)" : "");
        List<ReplayArchive.Marker> markers = new ArrayList<>();
        for (PlayerBuffer.Marker m : clip.markers())
            markers.add(new ReplayArchive.Marker((int) (m.tick() - clip.startTick()), m.colour(), m.description(), m.dimension(), m.x(), m.y(), m.z()));
        // 구간 바이트는 저장 스레드에서 꺼낸다 (닫힌 구간은 바뀌지 않음)
        io.execute(() -> {
            try {
                List<ReplayArchive.Part> parts = new ArrayList<>();
                for (Segment s : clip.segments()) parts.add(new ReplayArchive.Part(s.snapshot(), s.actionsCopy(), s.ticks()));
                int written = ReplayArchive.write(out, new ReplayArchive.Meta(UUID.randomUUID(), name, Bukkit.getMotd(),
                        Codecs.versionName(), Codecs.dataVersion(), Codecs.protocolVersion()), parts, markers, store::get, (int) ticks);
                long size = Files.size(out);
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
            PlayerBuffer.Clip clip = r.finish(tick);
            if (clip != null) export(r.name, clip);
        } catch (RuntimeException | LinkageError ex) {
            plugin.getLogger().warning("서버 녹화: " + r.name + " 퇴장 처리 실패 — " + ex);
        }
        io.execute(r::release);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent e) {
        PlayerRecorder r = recorders.get(e.getPlayer().getUniqueId());
        if (r != null) r.swing(e.getAnimationType() != PlayerAnimationType.OFF_ARM_SWING);
    }
}
