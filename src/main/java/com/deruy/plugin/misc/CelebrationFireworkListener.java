package com.deruy.plugin.misc;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.entity.Firework;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.persistence.PersistentDataType;

/** 바운티·빙고·KOTH 축하 폭죽(spawnRainbowFirework)이 주변 플레이어를 다치게 하지 않도록 한다. */
public final class CelebrationFireworkListener implements Listener {
    public static boolean isCelebration(Object damager) {
        return damager instanceof Firework f
                && f.getPersistentDataContainer().has(DeruyPlugin.CELEBRATION_KEY, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (isCelebration(event.getDamager())) event.setCancelled(true);
    }
}
