package com.deruy.plugin.bingo;

import com.deruy.plugin.DeruyPlugin;
import com.deruy.plugin.events.GameEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 빙고 이벤트: 5x5(사이즈 config화) 랜덤 빙고판을 "전체 바이옴+아이템 풀 - 제외리스트"로 생성.
 * 팀별로 각 칸 완료여부를 추적하고, 설정된 줄 수만큼 완성하면 승리.
 *
 * config:
 *   bingo.size                 : 보드 한 변 크기 (기본 5)
 *   bingo.lines-required       : 승리에 필요한 줄 수 (기본 1)
 *   bingo.excluded-biomes      : 제외할 바이옴 이름 목록
 *   bingo.excluded-items       : 제외할 아이템 Material 이름 목록
 */
public class BingoManager implements GameEvent {

    private final DeruyPlugin plugin;
    private boolean running = false;

    private int size;
    private List<Integer> lineMilestones = new ArrayList<>();
    private List<BingoCell> board = new ArrayList<>();

    // 팀 이름 -> 완료된 칸 인덱스 집합
    private final Map<String, Set<Integer>> teamProgress = new HashMap<>();
    // 플레이어 -> 팀 이름 (간단한 내부 팀 배정)
    private final Map<String, String> playerTeams = new HashMap<>();
    // 팀 -> 이미 방송한 마일스톤 줄 수 집합 (중복 알림 방지)
    private final Map<String, Set<Integer>> announcedMilestones = new HashMap<>();
    // 이벤트를 종료시킨(최종 목표 달성) 팀들
    private final Set<String> finishedTeams = new HashSet<>();

    /** 서바이벌에서 얻을 수 없는 아이템 (설정의 excluded-items 와 별개로 항상 제외) */
    static final Set<String> UNOBTAINABLE = Set.of(
            "BEDROCK", "BARRIER", "LIGHT", "STRUCTURE_BLOCK", "STRUCTURE_VOID", "JIGSAW", "COMMAND_BLOCK",
            "CHAIN_COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK", "COMMAND_BLOCK_MINECART", "DEBUG_STICK", "KNOWLEDGE_BOOK",
            "SPAWNER", "TRIAL_SPAWNER", "VAULT", "END_PORTAL_FRAME", "REINFORCED_DEEPSLATE", "BUDDING_AMETHYST",
            "PETRIFIED_OAK_SLAB", "PLAYER_HEAD", "FARMLAND", "DIRT_PATH", "FROGSPAWN", "TEST_BLOCK", "TEST_INSTANCE_BLOCK",
            "END_GATEWAY", "END_PORTAL", "NETHER_PORTAL", "BUBBLE_COLUMN", "CHORUS_PLANT", "TALL_SEAGRASS", "PISTON_HEAD",
            "MOVING_PISTON", "FIRE", "SOUL_FIRE", "WATER", "LAVA", "POWDER_SNOW", "BUNDLE_DEBUG");
    static boolean obtainable(Material m) {
        String n = m.name();
        return !UNOBTAINABLE.contains(n) && !n.endsWith("_SPAWN_EGG") && !n.startsWith("INFESTED_") && !n.contains("COMMAND_BLOCK");
    }
    /** 갈 수 없는 바이옴 */
    static final Set<String> UNREACHABLE_BIOMES = Set.of("THE_VOID", "CUSTOM");
    private org.bukkit.scheduler.BukkitTask scanTask;

    public BingoManager(DeruyPlugin plugin) {
        this.plugin = plugin;
        for (var entry : plugin.getDataStore().loadBingoTeams().entrySet()) {
            playerTeams.put(entry.getKey().toString(), entry.getValue());
        }
        if (!playerTeams.isEmpty()) {
            plugin.getLogger().info("빙고 팀배정 " + playerTeams.size() + "건을 data.yml에서 불러왔습니다.");
        }
    }

    @Override
    public String getName() {
        return "bingo";
    }

    // ---------------- 팀 배정 ----------------

    public void setTeam(Player player, String team) {
        playerTeams.put(player.getUniqueId().toString(), team);
        teamProgress.computeIfAbsent(team, t -> new HashSet<>());
        plugin.getDataStore().saveBingoTeam(player.getUniqueId(), team);
    }

    public String getTeam(Player player) {
        return playerTeams.get(player.getUniqueId().toString());
    }

    public void reloadMilestones() {
        lineMilestones = new ArrayList<>(plugin.getConfig().getIntegerList("bingo.line-milestones"));
        if (lineMilestones.isEmpty()) lineMilestones.add(plugin.getConfig().getInt("bingo.lines-required",1));
        Collections.sort(lineMilestones);
    }

    // ---------------- 보드 생성 ----------------

    private List<BingoCell> buildPool() {
        List<String> excludedBiomes = plugin.getConfig().getStringList("bingo.excluded-biomes");
        List<String> excludedItems = plugin.getConfig().getStringList("bingo.excluded-items");

        List<BingoCell> pool = new ArrayList<>();

        for (Biome biome : org.bukkit.Registry.BIOME) {
            String name = biome.getKey().getKey().toUpperCase();
            if (!UNREACHABLE_BIOMES.contains(name) && excludedBiomes.stream().noneMatch(n -> n.equalsIgnoreCase(name))) {
                pool.add(new BingoCell(BingoCell.Type.BIOME, name));
            }
        }

        for (Material material : Material.values()) {
            if (!material.isItem() || material.isLegacy() || material.isAir() || !obtainable(material)) continue;
            String name = material.name();
            if (excludedItems.stream().noneMatch(n -> n.equalsIgnoreCase(name))) {
                pool.add(new BingoCell(BingoCell.Type.ITEM, name));
            }
        }

        return pool;
    }

    @Override
    public void start() {
        if (running) return;

        size = plugin.getConfig().getInt("bingo.size", 5);
        lineMilestones = new ArrayList<>(plugin.getConfig().getIntegerList("bingo.line-milestones"));
        if (lineMilestones.isEmpty()) lineMilestones.add(plugin.getConfig().getInt("bingo.lines-required", 1));
        Collections.sort(lineMilestones);

        List<BingoCell> pool = buildPool();
        Collections.shuffle(pool);

        int needed = size * size;
        if (pool.size() < needed) {
            plugin.getLogger().warning("빙고 풀이 부족합니다 (필요 " + needed + ", 보유 " + pool.size() + ")");
            return;
        }

        board = new ArrayList<>(pool.subList(0, needed));
        teamProgress.clear();
        announcedMilestones.clear();
        finishedTeams.clear();
        for (String team : new HashSet<>(playerTeams.values())) {
            teamProgress.put(team, new HashSet<>());
        }

        running = true;
        startScan();
        saveState();
        Bukkit.broadcastMessage("§d§l빙고 §e이벤트가 시작되었습니다! (" + size + "x" + size + ", 목표 줄수: "
                + lineMilestones + ")");
    }

    @Override
    public void stop() {
        if (!running) return;
        running = false;
        if (scanTask != null) { scanTask.cancel(); scanTask = null; }
        board.clear();
        teamProgress.clear();
        announcedMilestones.clear();
        finishedTeams.clear();
        plugin.getDataStore().set("bingo.state", null);
        plugin.getDataStore().save();
        Bukkit.broadcastMessage("§d§l빙고 §e이벤트가 종료되었습니다.");
    }

    /** 서버 종료·리로드용: 타이머만 멈추고 판·진행은 data.yml 에 남겨 켜질 때 이어서 한다. */
    public void shutdown() {
        if (!running) return;
        if (scanTask != null) { scanTask.cancel(); scanTask = null; }
        saveState();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // ---------------- 진행 체크 (리스너에서 호출) ----------------

    public void checkBiome(Player player, Biome biome) {
        if (!running) return;
        String team = getTeam(player);
        if (team == null) return;

        String name = biome.getKey().getKey().toUpperCase();
        markIfMatch(team, player, BingoCell.Type.BIOME, name);
    }

    public void checkItem(Player player, Material material) {
        if (!running) return;
        String team = getTeam(player);
        if (team == null) return;

        markIfMatch(team, player, BingoCell.Type.ITEM, material.name());
    }

    private void markIfMatch(String team, Player player, BingoCell.Type type, String value) {
        if (finishedTeams.contains(team)) return;

        for (int i = 0; i < board.size(); i++) {
            BingoCell cell = board.get(i);
            if (cell.getType() != type || !cell.getValue().equalsIgnoreCase(value)) continue;

            Set<Integer> progress = teamProgress.computeIfAbsent(team, t -> new HashSet<>());
            if (progress.add(i)) {
                Bukkit.broadcastMessage("§d[빙고] §e팀 '" + team + "'§7(" + player.getName() + ")§e가 칸을 완료했습니다: " + cell.display());
                checkLines(team, player);
                if (running) saveState();
            }
        }
    }

    private void checkLines(String team, Player achiever) {
        Set<Integer> progress = teamProgress.get(team);
        if (progress == null) return;

        int completedLines = countCompletedLines(progress);

        // 아직 방송 안 한 마일스톤 중, 이번에 달성한 것들을 오름차순으로 전부 방송
        Set<Integer> announced = announcedMilestones.computeIfAbsent(team, t -> new HashSet<>());
        for (int milestone : lineMilestones) {
            if (completedLines >= milestone && announced.add(milestone)) {
                Bukkit.broadcastMessage("§d[빙고] §a" + team + "팀이 " + milestone + "줄을 달성했습니다!");
                plugin.spawnRainbowFirework(achiever.getLocation());
                giveRewardsToTeam(team);
            }
        }

        int finalMilestone = lineMilestones.get(lineMilestones.size() - 1);
        boolean endOnBingo = plugin.getConfig().getBoolean("bingo.end-on-bingo", true);

        if (completedLines >= finalMilestone && endOnBingo && finishedTeams.add(team)) {
            Bukkit.broadcastMessage("§d§l[빙고] §a§l팀 '" + team + "'이(가) 최종 목표(" + finalMilestone + "줄)를 달성하여 빙고 이벤트가 종료됩니다!");
            stop();
        }
    }

    private int countCompletedLines(Set<Integer> progress) {
        int completedLines = 0;

        // 가로
        for (int r = 0; r < size; r++) {
            boolean full = true;
            for (int c = 0; c < size; c++) {
                if (!progress.contains(r * size + c)) { full = false; break; }
            }
            if (full) completedLines++;
        }
        // 세로
        for (int c = 0; c < size; c++) {
            boolean full = true;
            for (int r = 0; r < size; r++) {
                if (!progress.contains(r * size + c)) { full = false; break; }
            }
            if (full) completedLines++;
        }
        // 대각선
        boolean diag1 = true, diag2 = true;
        for (int i = 0; i < size; i++) {
            if (!progress.contains(i * size + i)) diag1 = false;
            if (!progress.contains(i * size + (size - 1 - i))) diag2 = false;
        }
        if (diag1) completedLines++;
        if (diag2) completedLines++;

        return completedLines;
    }

    private void giveRewardsToTeam(String team) {
        var rewardEntries = plugin.getConfig().getStringList("bingo.reward-items");

        for (var onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (!team.equals(getTeam(onlinePlayer))) continue;

            var items = plugin.getSupplyChestRegistry().rollRewards(rewardEntries, plugin.getLogger());
            for (var item : items) {
                var leftover = onlinePlayer.getInventory().addItem(item);
                leftover.values().forEach(remain ->
                        onlinePlayer.getWorld().dropItemNaturally(onlinePlayer.getLocation(), remain));
            }
            if (!items.isEmpty()) {
                onlinePlayer.sendMessage("§d[빙고] §e빙고 승리 보상을 획득했습니다!");
            }
        }
    }

    public List<BingoCell> getBoard() {
        return board;
    }

    public int getSize() {
        return size;
    }

    public Set<Integer> getProgress(String team) {
        return teamProgress.getOrDefault(team, Set.of());
    }

    /** 줍기만 세던 것을 보완: 제작·화로·거래·상자에서 꺼낸 아이템도 2초마다 인벤토리를 훑어 인정 */
    private void startScan() {
        if (scanTask != null) scanTask.cancel();
        scanTask = Bukkit.getScheduler().runTaskTimer(plugin, this::scanInventories, 40L, 40L);
    }

    void scanInventories() {
        if (!running) return;
        Set<Material> wanted = new HashSet<>();
        for (BingoCell c : board) if (c.getType() == BingoCell.Type.ITEM) {
            Material m = Material.matchMaterial(c.getValue());
            if (m != null) wanted.add(m);
        }
        if (wanted.isEmpty()) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (getTeam(p) == null) continue;
            Set<Material> seen = new HashSet<>();
            for (var item : p.getInventory().getContents())
                if (item != null && wanted.contains(item.getType()) && seen.add(item.getType())) checkItem(p, item.getType());
            if (!running) return; // 최종 목표 달성으로 끝났으면 중단
        }
    }

    /** 재시작해도 이어서 하도록 판·진행 상황을 data.yml 에 저장 */
    private void saveState() {
        var ds = plugin.getDataStore();
        ds.set("bingo.state", null);
        ds.set("bingo.state.size", size);
        List<String> cells = new ArrayList<>();
        for (BingoCell c : board) cells.add(c.getType() + ":" + c.getValue());
        ds.set("bingo.state.board", cells);
        ds.set("bingo.state.milestones", lineMilestones);
        // 팀 이름에 '.' 이 들어가도 경로가 갈라지지 않게 이름을 값으로 저장한다.
        Set<String> teams = new java.util.LinkedHashSet<>(teamProgress.keySet());
        teams.addAll(announcedMilestones.keySet());
        List<Map<String, Object>> records = new ArrayList<>();
        for (String t : teams) {
            Map<String, Object> r = new java.util.LinkedHashMap<>();
            r.put("name", t);
            r.put("progress", new ArrayList<>(teamProgress.getOrDefault(t, Set.of())));
            r.put("announced", new ArrayList<>(announcedMilestones.getOrDefault(t, Set.of())));
            records.add(r);
        }
        ds.set("bingo.state.teams", records);
        ds.set("bingo.state.finished", new ArrayList<>(finishedTeams));
        ds.save();
    }

    private static Set<Integer> ints(Object raw) {
        Set<Integer> out = new HashSet<>();
        if (raw instanceof List<?> list) for (Object o : list) if (o instanceof Number n) out.add(n.intValue());
        return out;
    }

    /** onEnable 에서 호출: 저장된 진행 중 빙고가 있으면 이어서 */
    public boolean restoreState() {
        var st = plugin.getDataStore().section("bingo.state");
        if (st == null || running) return false;
        List<BingoCell> cells = new ArrayList<>();
        for (String raw : st.getStringList("board")) {
            int i = raw.indexOf(':');
            if (i < 0) return false;
            cells.add(new BingoCell(BingoCell.Type.valueOf(raw.substring(0, i)), raw.substring(i + 1)));
        }
        int n = st.getInt("size", 5);
        if (cells.size() != n * n) return false;
        size = n; board = cells;
        lineMilestones = new ArrayList<>(st.getIntegerList("milestones"));
        if (lineMilestones.isEmpty()) reloadMilestones();
        teamProgress.clear(); announcedMilestones.clear(); finishedTeams.clear();
        for (Map<?, ?> r : st.getMapList("teams")) {
            if (!(r.get("name") instanceof String t)) continue;
            teamProgress.put(t, ints(r.get("progress")));
            Set<Integer> announced = ints(r.get("announced"));
            if (!announced.isEmpty()) announcedMilestones.put(t, announced);
        }
        finishedTeams.addAll(st.getStringList("finished"));
        running = true;
        startScan();
        plugin.getLogger().info("진행 중이던 빙고를 이어서 시작합니다 (" + size + "x" + size + ")");
        return true;
    }
}
