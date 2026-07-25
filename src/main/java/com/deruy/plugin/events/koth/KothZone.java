package com.deruy.plugin.events.koth;

import org.bukkit.Location;

import java.util.UUID;

/**
 * KOTH의 개별 점령 구역. 여러 개(3개, 5개, 7개 등) 동시에 운영 가능.
 */
public class KothZone {

    private final String id;
    private Location corner1;
    private Location corner2;

    private UUID currentHolder = null;
    private int heldTicks = 0;

    public KothZone(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public void setCorner1(Location corner1) {
        this.corner1 = corner1;
    }

    public void setCorner2(Location corner2) {
        this.corner2 = corner2;
    }

    public boolean isComplete() {
        return corner1 != null && corner2 != null;
    }

    public Location getCorner1() {
        return corner1;
    }

    public Location getCorner2() {
        return corner2;
    }

    public UUID getCurrentHolder() {
        return currentHolder;
    }

    public void setCurrentHolder(UUID currentHolder) {
        this.currentHolder = currentHolder;
    }

    public int getHeldTicks() {
        return heldTicks;
    }

    public void setHeldTicks(int heldTicks) {
        this.heldTicks = heldTicks;
    }

    public void resetProgress() {
        this.currentHolder = null;
        this.heldTicks = 0;
    }

    public boolean contains(Location loc) {
        if (!isComplete() || !loc.getWorld().equals(corner1.getWorld())) return false;

        double minX = Math.min(corner1.getX(), corner2.getX());
        double maxX = Math.max(corner1.getX(), corner2.getX());
        double minY = Math.min(corner1.getY(), corner2.getY());
        double maxY = Math.max(corner1.getY(), corner2.getY());
        double minZ = Math.min(corner1.getZ(), corner2.getZ());
        double maxZ = Math.max(corner1.getZ(), corner2.getZ());

        return loc.getX() >= minX && loc.getX() <= maxX
                && loc.getY() >= minY && loc.getY() <= maxY
                && loc.getZ() >= minZ && loc.getZ() <= maxZ;
    }
}
