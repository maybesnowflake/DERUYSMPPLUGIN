package com.deruy.plugin.combatzone;

import com.deruy.plugin.DeruyPlugin;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.List;

/**
 * 컴벳태그 상태인 플레이어가 config에 등록된 WorldGuard 리전(예: 스폰, 세이프존)
 * 안으로 들어오는 것을 막는다. 리전 정의/편집은 WorldGuard 자체 명령어로 하고,
 * 여기서는 "이 리전 이름들은 컴벳중 진입 금지"만 우리 쪽 config로 관리한다.
 *
 * config: combat-zone.protected-regions (리전 이름 목록)
 *         combat-zone.enabled
 */
public class CombatZoneListener implements Listener {

    private final DeruyPlugin plugin;

    public CombatZoneListener(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean isProtectedRegionName(String name) {
        List<String> protectedNames = plugin.getConfig().getStringList("combat-zone.protected-regions");
        return protectedNames.stream().anyMatch(n -> n.equalsIgnoreCase(name));
    }

    private boolean isEnteringProtectedRegion(Location from, Location to) {
        if (to.getWorld() == null) return false;

        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        RegionQuery query = container.createQuery();

        var toSet = query.getApplicableRegions(BukkitAdapter.adapt(to));
        boolean toInProtected = toSet.getRegions().stream()
                .anyMatch(r -> isProtectedRegionName(r.getId()));

        if (!toInProtected) return false;
        // 다른 월드에서 넘어오면(포탈·명령어 텔레포트) 무조건 진입
        if (from.getWorld() == null || !from.getWorld().equals(to.getWorld())) return true;

        // from이 이미 같은 보호구역 안이었다면(구역 내 이동) 막지 않음 - 진입 순간만 차단
        var fromSet = query.getApplicableRegions(BukkitAdapter.adapt(from));
        boolean fromInProtected = fromSet.getRegions().stream()
                .anyMatch(r -> isProtectedRegionName(r.getId()));

        return !fromInProtected;
    }

    private void handleBlock(Player player, Location from, Location to, org.bukkit.event.Cancellable event) {
        if (!plugin.getConfig().getBoolean("combat-zone.enabled", true)) return;
        if (!plugin.getLifeStealManager().isCombatTagged(player.getUniqueId())) return;
        if (to.getWorld() == null) return;

        if (isEnteringProtectedRegion(from, to)) {
            event.setCancelled(true);
            player.sendMessage("§c전투 중에는 이 구역에 들어갈 수 없습니다.");
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        // 같은 블록 내 시야 회전 등 미세이동은 스킵해서 틱마다 리전조회 하지 않도록
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        handleBlock(event.getPlayer(), event.getFrom(), event.getTo(), event);
    }

    /** 보트·말 등을 타고 들어가면 PlayerMoveEvent 가 안 불림 → 탈것 이동도 검사 */
    @EventHandler
    public void onVehicleMove(org.bukkit.event.vehicle.VehicleMoveEvent event) {
        if (!plugin.getConfig().getBoolean("combat-zone.enabled", true)) return;
        Location from = event.getFrom(), to = event.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) return;
        for (var passenger : event.getVehicle().getPassengers()) {
            if (!(passenger instanceof Player player) || !plugin.getLifeStealManager().isCombatTagged(player.getUniqueId())) continue;
            if (!isEnteringProtectedRegion(from, to)) return;
            event.getVehicle().eject();
            event.getVehicle().setVelocity(new org.bukkit.util.Vector());
            event.getVehicle().teleport(from);
            player.teleport(from.clone().add(0, 1, 0));
            player.sendMessage("§c전투 중에는 이 구역에 들어갈 수 없습니다.");
            return;
        }
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null) return;
        handleBlock(event.getPlayer(), event.getFrom(), event.getTo(), event);
    }
}
