package com.deruy.plugin.lifesteal;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * LifeSteal 시스템의 중심 매니저.
 * - 하트(최대 체력) 증감 (역할별 max-hearts override 반영)
 * - 0하트 밴 처리
 * - 컴벳태그 상태 관리 (다른 매니저/리스너에서 공통으로 참조)
 * - 시스템 전체 on/off 토글
 *
 * min/max 하트값은 필드로 캐싱하지 않고 매번 config에서 직접 읽는다.
 * (그래야 /deruyreload 만으로 재시작 없이 즉시 반영됨)
 */
public class LifeStealManager {

    private static final double HEART_VALUE = 2.0; // 1하트 = 2 체력포인트

    private final DeruyPlugin plugin;

    private final Map<UUID, Long> combatTagged = new HashMap<>();

    private boolean systemEnabled;

    public LifeStealManager(DeruyPlugin plugin) {
        this.plugin = plugin;
        this.systemEnabled = plugin.getConfig().getBoolean("lifesteal.enabled", true);
    }

    // ---------------- 시스템 on/off ----------------

    public boolean isSystemEnabled() {
        return systemEnabled;
    }

    public void setSystemEnabled(boolean enabled) {
        this.systemEnabled = enabled;
        plugin.getConfig().set("lifesteal.enabled", enabled);
        plugin.saveConfig();
    }

    // ---------------- 하트 시스템 ----------------

    /** @return 실제로 하트가 추가됐으면 true, 이미 상한선(max-hearts)에 도달해서 추가 못했으면 false */
    public boolean addHeart(Player player) {
        return addHeart(player, 1, "HEART_GAIN");
    }

    public boolean addHeart(Player player, int amount) { return addHeart(player, amount, "HEART_GAIN"); }
    public boolean addHeart(Player player, int amount, String reason) {
        if (amount <= 0) return false;
        var attr = player.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) return false;

        double maxHealth = getMaxHealth(player); // 역할별 override 반영
        if (attr.getBaseValue() >= maxHealth) {
            return false; // 이미 상한선 도달, 추가 불가
        }

        double before = attr.getBaseValue();
        double newMax = Math.min(before + (HEART_VALUE * amount), maxHealth);
        attr.setBaseValue(newMax);
        plugin.getHeartAuditLog().record(player, before / 2, newMax / 2, reason);
        plugin.playConfiguredSound("heart-change", player.getLocation());
        return true;
    }

    /**
     * /withdraw 전용: 하트를 밴 로직 없이 안전하게 인출한다.
     * 인출 후 min-hearts 밑으로 내려가면 실패(false) 반환하고 아무것도 바뀌지 않는다.
     *
     * @return 실제로 인출됐으면 true
     */
    public boolean withdrawHearts(Player player, int amount) {
        if (amount <= 0) return false;
        var attr = player.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) return false;

        double minHealth = getMinHealth();
        double newMax = attr.getBaseValue() - (HEART_VALUE * amount);

        if (newMax <= minHealth) {
            return false; // 최소 하트 밑으로는 인출 불가 (밴 방지)
        }

        double before = attr.getBaseValue();
        attr.setBaseValue(newMax);
        plugin.getHeartAuditLog().record(player, before / 2, newMax / 2, "WITHDRAW");
        if (player.getHealth() > newMax) {
            player.setHealth(newMax);
        }
        plugin.playConfiguredSound("heart-change", player.getLocation());
        return true;
    }

    /**
     * 하트를 제거한다. 하한선(min-hearts) 도달 시 밴 처리.
     */
    public void removeHeart(Player player) {
        removeHeart(player, 1);
    }

    public void removeHeart(Player player, int amount) { removeHeart(player, amount, "HEART_LOSS"); }
    public void removeHeart(Player player, int amount, String reason) {
        if (amount <= 0) return;
        var attr = player.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) return;

        double minHealth = getMinHealth();
        double newMax = attr.getBaseValue() - (HEART_VALUE * amount);

        if (newMax <= minHealth) {
            plugin.getHeartAuditLog().record(player, attr.getBaseValue()/2, minHealth/2, reason + " BAN");
            attr.setBaseValue(minHealth);
            banZeroHeart(player);
            return;
        }

        double before = attr.getBaseValue();
        attr.setBaseValue(newMax);
        plugin.getHeartAuditLog().record(player, before / 2, newMax / 2, reason);
        if (player.getHealth() > newMax) {
            player.setHealth(newMax);
        }
    }

    /** 전역 기본 max-hearts (역할 override 없을 때 사용) */
    public double getMaxHealth() {
        return plugin.getConfig().getInt("lifesteal.heart-limits.max-hearts", 10) * HEART_VALUE;
    }

    /** 역할별 max-hearts override가 있으면 그걸, 없으면 전역 기본값을 사용 */
    public double getMaxHealth(Player player) {
        int roleOverride = plugin.getRoleManager().getMaxHeartsOverride(player);
        if (roleOverride > 0) return roleOverride * HEART_VALUE;
        return getMaxHealth();
    }

    public double getMinHealth() {
        return plugin.getConfig().getInt("lifesteal.heart-limits.min-hearts", 1) * HEART_VALUE;
    }

    private void banZeroHeart(Player player) {
        Location deathSpot = player.getLocation();
        plugin.playConfiguredSound("on-zero-heart-ban", deathSpot);

        String kickMessage = plugin.getConfig().getString(
                "lifesteal.zero-heart-ban-message",
                "§c하트를 모두 잃어 서버에서 추방되었습니다."
        );


        org.bukkit.BanList<org.bukkit.profile.PlayerProfile> banList =
                Bukkit.getBanList(org.bukkit.BanList.Type.PROFILE);
        plugin.getDataStore().setHeartBan(player.getUniqueId(), true);
        banList.addBan(player.getPlayerProfile(), "최소 하트 도달", (java.time.Instant) null, "LifeSteal");
        Bukkit.getScheduler().runTask(plugin, () -> { if (player.isOnline()) player.kickPlayer(kickMessage); });
    }

    public void reloadSettings() {
        systemEnabled = plugin.getConfig().getBoolean("lifesteal.enabled", true);
        if (!systemEnabled) { combatTagged.clear(); lastAttackers.clear(); }
    }
    public double getHearts(Player p) { return p.getAttribute(Attribute.MAX_HEALTH).getBaseValue() / 2; }
    public boolean setHearts(Player p, double hearts, String reason) {
        if (!Double.isFinite(hearts) || hearts <= getMinHealth()/2 || hearts > getMaxHealth(p)/2) return false;
        var attr = p.getAttribute(Attribute.MAX_HEALTH);
        double before = attr.getBaseValue()/2;
        attr.setBaseValue(hearts*2);
        if (p.getHealth() > hearts*2) p.setHealth(hearts*2);
        plugin.getHeartAuditLog().record(p, before, hearts, reason);
        return true;
    }
    private final Map<UUID, UUID> lastAttackers = new HashMap<>();
    private final java.util.Set<UUID> logoutDeaths = new java.util.HashSet<>();
    public void tagCombat(Player victim, Player attacker, long duration) {
        tagCombat(victim.getUniqueId(), duration); tagCombat(attacker.getUniqueId(), duration);
        lastAttackers.put(victim.getUniqueId(), attacker.getUniqueId());
        lastAttackers.put(attacker.getUniqueId(), victim.getUniqueId());
    }
    public Player getLastAttacker(UUID id) { UUID other = lastAttackers.get(id); return other == null ? null : Bukkit.getPlayer(other); }
    public void beginLogoutDeath(UUID id) { logoutDeaths.add(id); }
    public boolean isLogoutDeath(UUID id) { return logoutDeaths.contains(id); }
    public void endLogoutDeath(UUID id) { logoutDeaths.remove(id); }
    // ---------------- 컴벳태그 ----------------

    public void tagCombat(UUID uuid, long durationMillis) {
        combatTagged.put(uuid, System.currentTimeMillis() + durationMillis);
    }

    public boolean isCombatTagged(UUID uuid) {
        Long expiry = combatTagged.get(uuid);
        if (expiry == null) return false;
        if (System.currentTimeMillis() > expiry) {
            combatTagged.remove(uuid);
            return false;
        }
        return true;
    }

    public void clearCombatTag(UUID uuid) {
        combatTagged.remove(uuid);
        lastAttackers.remove(uuid);
    }

    public long getRemainingCombatMillis(UUID uuid) {
        Long expiry = combatTagged.get(uuid);
        if (expiry == null) return 0L;
        return Math.max(0L, expiry - System.currentTimeMillis());
    }
}
