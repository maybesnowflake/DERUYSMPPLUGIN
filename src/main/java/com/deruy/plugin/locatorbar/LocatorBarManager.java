package com.deruy.plugin.locatorbar;

import com.deruy.plugin.DeruyPlugin;
import com.deruy.plugin.events.GameEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * 바닐라 로케이터바는 투명/몹헤드/조각된호박 상태에서 숨겨지는 것이 정식 사양이라
 * 그 조건 자체를 지울 수 없다(NMS 내부 로직). 그래서 바닐라 로케이터바는 끄고
 * (/gamerule locatorBar false), 이 매니저가 액션바로 항상-표시되는 대체 로케이터바를 제공한다.
 *
 * 각 플레이어에게 "가장 가까운 다른 플레이어"의 방향(8방위 화살표)과 거리를 표시한다.
 */
public class LocatorBarManager implements GameEvent {

    private final DeruyPlugin plugin;
    private boolean running = false;
    private BukkitTask task;

    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    public LocatorBarManager(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "locatorbar";
    }

    @Override
    public void start() {
        if (running) return;
        running = true;
        plugin.getConfig().set("locatorbar.enabled", true);
        plugin.saveConfig();

        long interval = plugin.getConfig().getLong("locatorbar.update-interval-ticks", 20);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, interval);
        Bukkit.broadcastMessage("§b§l로케이터바 §e가 활성화되었습니다. (은신/투명 무시하고 항상 표시)");
    }

    @Override
    public void stop() {
        if (!running) return;
        running = false;
        plugin.getConfig().set("locatorbar.enabled", false);
        plugin.saveConfig();
        if (task != null) {
            task.cancel();
            task = null;
        }
        Bukkit.broadcastMessage("§b§l로케이터바 §e가 비활성화되었습니다.");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void tick() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Player nearest = findNearestOtherPlayer(viewer);
            if (nearest == null) {
                continue;
            }
            String bar = buildActionBarText(viewer, nearest);
            viewer.sendActionBar(net.kyori.adventure.text.Component.text(bar));
        }
    }

    private Player findNearestOtherPlayer(Player viewer) {
        Player nearest = null;
        double bestDistSq = Double.MAX_VALUE;
        for (Player other : viewer.getWorld().getPlayers()) {
            if (other.equals(viewer)) continue;
            double distSq = other.getLocation().distanceSquared(viewer.getLocation());
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                nearest = other;
            }
        }
        return nearest;
    }

    private String buildActionBarText(Player viewer, Player target) {
        double distance = viewer.getLocation().distance(target.getLocation());
        String arrow = computeArrow(viewer.getLocation(), target.getLocation(), viewer.getLocation().getYaw());
        return "§b" + arrow + " §f" + target.getName() + " §7(" + Math.round(distance) + "m)";
    }

    /**
     * viewer 기준 target으로의 상대방위를 8방위 화살표로 변환.
     */
    private String computeArrow(Location viewerLoc, Location targetLoc, float viewerYaw) {
        double dx = targetLoc.getX() - viewerLoc.getX();
        double dz = targetLoc.getZ() - viewerLoc.getZ();

        // 마인크래프트 좌표계에서 목표 방위각(북=0, 시계방향)
        double bearingToTarget = Math.toDegrees(Math.atan2(-dx, dz));
        double relative = bearingToTarget - viewerYaw;

        relative = ((relative % 360) + 360) % 360; // 0~360 정규화

        int index = (int) Math.round(relative / 45.0) % 8;
        return ARROWS[index];
    }
}
