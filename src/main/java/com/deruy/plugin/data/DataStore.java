package com.deruy.plugin.data;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * config.yml과 분리된 런타임 상태 저장소. 커맨드로 계속 바뀌는 데이터
 * (KOTH 구역 좌표, 빙고 팀 배정, 바운티 페어, 서플라이드랍 구역)를 여기 담는다.
 * 파일: plugins/DeruyPlugin/data.yml
 */
public class DataStore {

    private final DeruyPlugin plugin;
    private final File file;
    private YamlConfiguration data;

    public DataStore(DeruyPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
        load();
    }

    private void load() {
        if (!file.exists()) {
            try {
                file.getParentFile().mkdirs();
                file.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().warning("data.yml 생성 실패: " + e.getMessage());
            }
        }
        data = YamlConfiguration.loadConfiguration(file);
    }

    public void save() {
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("data.yml 저장 실패: " + e.getMessage());
        }
    }

    // ---------------- 좌표 직렬화 헬퍼 ----------------

    private void writeLocation(String path, Location loc) {
        data.set(path + ".world", loc.getWorld().getName());
        data.set(path + ".x", loc.getX());
        data.set(path + ".y", loc.getY());
        data.set(path + ".z", loc.getZ());
    }

    private Location readLocation(String path) {
        if (!data.isConfigurationSection(path)) return null;
        String worldName = data.getString(path + ".world");
        World world = worldName != null ? Bukkit.getWorld(worldName) : null;
        if (world == null) return null;
        double x = data.getDouble(path + ".x");
        double y = data.getDouble(path + ".y");
        double z = data.getDouble(path + ".z");
        return new Location(world, x, y, z);
    }

    // ---------------- KOTH 구역 (namespace로 koth/superkoth 등 독립 관리) ----------------

    public void saveKothZone(String namespace, String id, Location corner1, Location corner2) {
        if (corner1 != null) writeLocation(namespace + ".zones." + id + ".corner1", corner1);
        if (corner2 != null) writeLocation(namespace + ".zones." + id + ".corner2", corner2);
        save();
    }

    public void saveKothZone(String id, Location corner1, Location corner2) {
        saveKothZone("koth", id, corner1, corner2);
    }

    public void removeKothZone(String namespace, String id) {
        data.set(namespace + ".zones." + id, null);
        save();
    }

    public void removeKothZone(String id) {
        removeKothZone("koth", id);
    }

    /** id -> [corner1, corner2] (둘 중 하나가 null일 수 있음) */
    public Map<String, Location[]> loadKothZones(String namespace) {
        Map<String, Location[]> result = new HashMap<>();
        ConfigurationSection zonesSection = data.getConfigurationSection(namespace + ".zones");
        if (zonesSection == null) return result;

        for (String id : zonesSection.getKeys(false)) {
            Location c1 = readLocation(namespace + ".zones." + id + ".corner1");
            Location c2 = readLocation(namespace + ".zones." + id + ".corner2");
            result.put(id, new Location[]{c1, c2});
        }
        return result;
    }

    public Map<String, Location[]> loadKothZones() {
        return loadKothZones("koth");
    }

    // ---------------- 빙고 팀 배정 ----------------

    public void saveBingoTeam(UUID playerId, String team) {
        data.set("bingo.teams." + playerId, team);
        save();
    }

    public Map<UUID, String> loadBingoTeams() {
        Map<UUID, String> result = new HashMap<>();
        ConfigurationSection section = data.getConfigurationSection("bingo.teams");
        if (section == null) return result;

        for (String key : section.getKeys(false)) {
            try {
                result.put(UUID.fromString(key), section.getString(key));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return result;
    }

    // ---------------- 바운티 페어 ----------------

    public void saveBountyPair(UUID a, UUID b) {
        data.set("bounty.pairs." + a, b.toString());
        data.set("bounty.pairs." + b, a.toString());
        save();
    }

    public void removeBountyPair(UUID a) {
        String other = data.getString("bounty.pairs." + a);
        data.set("bounty.pairs." + a, null);
        if (other != null) {
            data.set("bounty.pairs." + other, null);
        }
        save();
    }

    public Map<UUID, UUID> loadBountyPairs() {
        Map<UUID, UUID> result = new HashMap<>();
        ConfigurationSection section = data.getConfigurationSection("bounty.pairs");
        if (section == null) return result;

        for (String key : section.getKeys(false)) {
            try {
                result.put(UUID.fromString(key), UUID.fromString(section.getString(key)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return result;
    }

    // ---------------- 바운티: 서버 전체가 쫓는 단일 타겟 ----------------

    public void saveGlobalBountyTarget(UUID target) {
        data.set("bounty.global-target", target.toString());
        save();
    }

    public UUID loadGlobalBountyTarget() {
        String raw = data.getString("bounty.global-target");
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void clearGlobalBountyTarget() {
        data.set("bounty.global-target", null);
        save();
    }

    // ---------------- 서플라이드랍 구역 (일반/슈퍼 공용) ----------------

    public void saveSupplyRegion(String kind, Location corner1, Location corner2) {
        if (corner1 != null) writeLocation("supplydrop." + kind + ".corner1", corner1);
        if (corner2 != null) writeLocation("supplydrop." + kind + ".corner2", corner2);
        save();
    }

    /** [corner1, corner2] 반환, 설정 안 됐으면 null */
    public Location[] loadSupplyRegion(String kind) {
        Location c1 = readLocation("supplydrop." + kind + ".corner1");
        Location c2 = readLocation("supplydrop." + kind + ".corner2");
        if (c1 == null || c2 == null) return null;
        return new Location[]{c1, c2};
    }

    // ---------------- 제작개수 제한용: 개체별 UUID 추적 ----------------
    // 단순 카운터가 아니라 "지금 실제로 살아있는 개체의 UUID 목록"을 저장한다.
    // 파괴 이벤트가 감지되면 여기서 UUID를 제거해서 정확한 실시간 개수를 유지한다.

    public void addLimitedInstance(String recipeId, java.util.UUID instanceId) {
        java.util.List<String> list = new java.util.ArrayList<>(
                data.getStringList("item-limit-instances." + recipeId));
        list.add(instanceId.toString());
        data.set("item-limit-instances." + recipeId, list);
        save();
    }

    public void removeLimitedInstance(String recipeId, java.util.UUID instanceId) {
        java.util.List<String> list = new java.util.ArrayList<>(
                data.getStringList("item-limit-instances." + recipeId));
        list.remove(instanceId.toString());
        data.set("item-limit-instances." + recipeId, list);
        save();
    }

    public int getLimitedInstanceCount(String recipeId) {
        return data.getStringList("item-limit-instances." + recipeId).size();
    }

    /** 관리자 수동 보정용 - 해당 레시피의 추적 목록을 통째로 비운다 (전부 파괴된 걸 확인했을 때) */
    public void clearLimitedInstances(String recipeId) {
        data.set("item-limit-instances." + recipeId, null);
        save();
    }
}
