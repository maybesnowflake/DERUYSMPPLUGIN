package com.deruy.plugin.role;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * 역할별 PVP 허용여부를 실제로 강제하는 리스너.
 * 공격자 또는 피해자 중 한쪽이라도 PVP 불가 상태면 데미지를 취소한다.
 */
public class RolePvpListener implements Listener {

    private final DeruyPlugin plugin;
    private final java.util.Map<java.util.UUID, Long> lastNotice = new java.util.HashMap<>();

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) { lastNotice.remove(event.getPlayer().getUniqueId()); }

    public RolePvpListener(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;

        Player attacker = resolveAttacker(event);
        if (attacker == null || attacker.equals(victim)) return;

        RoleManager roleManager = plugin.getRoleManager();
        if (!roleManager.isPvpAllowed(attacker) || !roleManager.isPvpAllowed(victim)) {
            event.setCancelled(true);
            // 휩쓸기·연사로 메시지가 도배되지 않게 2초에 한 번만
            long now = System.currentTimeMillis();
            Long last = lastNotice.get(attacker.getUniqueId());
            if (last == null || now - last > 2000) {
                lastNotice.put(attacker.getUniqueId(), now);
                attacker.sendMessage(plugin.getMessage("pvp-blocked", "&c지금은 PVP가 금지되어 있습니다."));
            }
        }
    }

    private Player resolveAttacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p) return p;
        if (event.getDamager() instanceof org.bukkit.entity.Projectile proj
                && proj.getShooter() instanceof Player p) return p;
        return null;
    }
}
