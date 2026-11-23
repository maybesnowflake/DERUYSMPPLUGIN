package com.deruy.plugin.timeline;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/** 킬·사망 기록 (라이프스틸 켜짐 여부와 상관없이) */
public final class TimelineListener implements Listener {
    private final DeruyPlugin plugin;
    public TimelineListener(DeruyPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer != null && !killer.equals(victim)) {
            String weapon = killer.getInventory().getItemInMainHand().getType().name();
            Timeline.log(plugin, "KILL", killer.getName() + " → " + victim.getName(), weapon, victim.getLocation());
        } else {
            var cause = victim.getLastDamageCause();
            Timeline.log(plugin, "DEATH", victim.getName(), cause == null ? "" : cause.getCause().name(), victim.getLocation());
        }
    }
}
