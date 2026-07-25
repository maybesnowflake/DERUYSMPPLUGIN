package com.deruy.plugin.combatzone;

import com.deruy.plugin.DeruyPlugin;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 컴벳태그 상태인 플레이어가 진입금지 구역(WorldGuard 리전) 경계 근처로 다가가면
 * 그 경계면을 빨간 유리(클라이언트 전용 가짜블록)로 표시해준다. 실제 블록은 바뀌지 않는다.
 *
 * config:
 *   combat-zone.visualize-enabled
 *   combat-zone.visualize-radius (경계 탐색 반경, 기본 8)
 */
public class CombatZoneVisualizer {

    private final DeruyPlugin plugin;
    private BukkitTask task;

    private final Map<UUID, Set<Location>> shownFakeBlocks = new HashMap<>();

    public CombatZoneVisualizer(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 10L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (var entry : shownFakeBlocks.entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p != null) revert(p, entry.getValue());
        }
        shownFakeBlocks.clear();
    }

    private void tick() {
        if (!plugin.isWorldGuardPresent()) return;
        if (!plugin.getConfig().getBoolean("combat-zone.visualize-enabled", true)) return;

        int radius = plugin.getConfig().getInt("combat-zone.visualize-radius", 8);
        List<String> protectedNames = plugin.getConfig().getStringList("combat-zone.protected-regions");

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            Set<Location> previous = shownFakeBlocks.getOrDefault(uuid, Set.of());

            if (!plugin.getLifeStealManager().isCombatTagged(uuid)) {
                if (!previous.isEmpty()) {
                    revert(player, previous);
                    shownFakeBlocks.remove(uuid);
                }
                continue;
            }

            Set<Location> current = findNearbyBoundaryBlocks(player, radius, protectedNames);

            for (Location loc : previous) {
                if (!current.contains(loc)) {
                    player.sendBlockChange(loc, loc.getBlock().getBlockData());
                }
            }

            for (Location loc : current) {
                if (!previous.contains(loc)) {
                    player.sendBlockChange(loc, Material.RED_STAINED_GLASS.createBlockData());
                }
            }

            if (current.isEmpty()) {
                shownFakeBlocks.remove(uuid);
            } else {
                shownFakeBlocks.put(uuid, current);
            }
        }
    }

    private void revert(Player player, Set<Location> locations) {
        for (Location loc : locations) {
            player.sendBlockChange(loc, loc.getBlock().getBlockData());
        }
    }

    private Set<Location> findNearbyBoundaryBlocks(Player player, int radius, List<String> protectedNames) {
        Set<Location> result = new HashSet<>();
        if (protectedNames.isEmpty()) return result;

        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        var regionManager = container.get(BukkitAdapter.adapt(player.getWorld()));
        if (regionManager == null) return result;

        Location center = player.getLocation();
        int px = center.getBlockX();
        int py = center.getBlockY();
        int pz = center.getBlockZ();

        for (String name : protectedNames) {
            ProtectedRegion region = regionManager.getRegion(name);
            if (region == null) continue;

            BlockVector3 min = region.getMinimumPoint();
            BlockVector3 max = region.getMaximumPoint();

            int minX = Math.max(min.getX(), px - radius);
            int maxX = Math.min(max.getX(), px + radius);
            int minY = Math.max(min.getY(), py - radius);
            int maxY = Math.min(max.getY(), py + radius);
            int minZ = Math.max(min.getZ(), pz - radius);
            int maxZ = Math.min(max.getZ(), pz + radius);

            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        boolean onShell = (x == min.getX() || x == max.getX()
                                || y == min.getY() || y == max.getY()
                                || z == min.getZ() || z == max.getZ());
                        if (!onShell) continue;

                        // 완전체(solid) 블록은 무시하고, 공기/잡초 등 비-solid 블록만 표시.
                        Material currentType = player.getWorld().getBlockAt(x, y, z).getType();
                        if (currentType.isSolid()) continue;

                        result.add(new Location(player.getWorld(), x, y, z));
                    }
                }
            }
        }

        return result;
    }
}
