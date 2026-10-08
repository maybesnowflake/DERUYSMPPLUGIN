package com.deruy.plugin.role;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

/**
 * 역할(LuckPerms 그룹)별 하트 상한선 / PVP 허용여부를 관리한다.
 *
 * config 예시:
 * roles:
 *   FWN:
 *     max-hearts: 13
 *     pvp-allowed: true
 *   LV:
 *     max-hearts: 20
 *     pvp-allowed: false
 *
 * /pvp 커맨드로 특정 역할(또는 ALL)의 PVP 상태를 시간제한으로 임시 override 가능.
 * override가 없으면 config의 pvp-allowed 값이 그대로 적용됨.
 */
public class RoleManager {

    private final DeruyPlugin plugin;
    private final Map<String, Boolean> pvpOverride = new HashMap<>();
    private final Map<String, Long> pvpOverrideExpiry = new HashMap<>();

    public RoleManager(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    /** 플레이어가 해당하는 역할들 중 config에 등록된 첫 번째 역할 이름을 반환. 없으면 null. */
    public String resolveConfiguredRole(Player player) {
        ConfigurationSection rolesSection = plugin.getConfig().getConfigurationSection("roles");
        if (rolesSection == null) return null;

        for (String role : rolesSection.getKeys(false)) {
            if (plugin.hasRole(player, role)) {
                return role;
            }
        }
        return null;
    }

    /** 역할별 max-hearts가 설정돼 있으면 그 값(하트 개수), 없으면 -1 (전역 기본값 사용해야 함을 의미) */
    public int getMaxHeartsOverride(Player player) {
        String role = resolveConfiguredRole(player);
        if (role == null) return -1;
        return plugin.getConfig().getInt("roles." + role + ".max-hearts", -1);
    }

    // ---------------- PVP 허용여부 ----------------

    public boolean isPvpAllowed(Player player) {
        Boolean allOverride = getActiveOverride("ALL");
        if (allOverride != null) return allOverride;

        String role = resolveConfiguredRole(player);
        if (role != null) {
            Boolean roleOverride = getActiveOverride(role.toUpperCase());
            if (roleOverride != null) return roleOverride;
            return plugin.getConfig().getBoolean("roles." + role + ".pvp-allowed", true);
        }
        return true;
    }

    private Boolean getActiveOverride(String key) {
        Long expiry = pvpOverrideExpiry.get(key);
        if (expiry == null) return null;
        if (System.currentTimeMillis() > expiry) {
            pvpOverride.remove(key);
            pvpOverrideExpiry.remove(key);
            return null;
        }
        return pvpOverride.get(key);
    }

    /**
     * 특정 역할(또는 "ALL")의 PVP 상태를 durationMillis 동안 임시로 override.
     */
    public void setTemporaryPvpState(String roleOrAll, boolean allowed, long durationMillis) {
        String key = roleOrAll.toUpperCase();
        pvpOverride.put(key, allowed);
        pvpOverrideExpiry.put(key, System.currentTimeMillis() + durationMillis);
    }

    /**
     * "1h", "30m", "45s", "2d" 같은 문자열을 밀리초로 변환. 형식이 잘못되면 -1 반환.
     */
    public static long parseDuration(String input) {
        if (input == null || input.length() < 2) return -1;
        try {
            char unit = input.charAt(input.length() - 1);
            long value = Long.parseLong(input.substring(0, input.length() - 1));
            return switch (unit) {
                case 's' -> value * 1000L;
                case 'm' -> value * 60L * 1000L;
                case 'h' -> value * 60L * 60L * 1000L;
                case 'd' -> value * 24L * 60L * 60L * 1000L;
                default -> -1L;
            };
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
