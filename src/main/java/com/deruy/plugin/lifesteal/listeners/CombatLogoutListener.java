package com.deruy.plugin.lifesteal.listeners;
import com.deruy.plugin.DeruyPlugin;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class CombatLogoutListener implements Listener {
    private final DeruyPlugin plugin;
    public CombatLogoutListener(DeruyPlugin plugin) { this.plugin = plugin; }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        var p = event.getPlayer(); var manager = plugin.getLifeStealManager();
        try {
            if (plugin.getServer().isStopping() || !manager.isSystemEnabled() || p.isDead()
                    || !manager.isCombatTagged(p.getUniqueId())) return;
            String mode = plugin.getConfig().getString("combat-tag.logout-penalty", "CHAT");
            if (mode.equalsIgnoreCase("CHAT")) {
                Bukkit.broadcastMessage(plugin.getMessage("combat-logout", "&c[전투] {player}님이 전투 중 로그아웃했습니다.").replace("{player}", p.getName()));
            } else if (mode.equalsIgnoreCase("KILL")) {
                manager.beginLogoutDeath(p.getUniqueId());
                try { p.setHealth(0); } finally { manager.endLogoutDeath(p.getUniqueId()); }
            }
        } finally { manager.clearCombatTag(p.getUniqueId()); }
    }
}
