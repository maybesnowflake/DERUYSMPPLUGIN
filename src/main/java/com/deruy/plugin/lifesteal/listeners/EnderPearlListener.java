package com.deruy.plugin.lifesteal.listeners;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * 기본룰: 엔더진주는 "던지는 것"은 허용하되, 그로 인한 "텔레포트"만 막는다.
 * config: lifesteal.restrictions.ender-pearl-tp-blocked (기본 true)
 */
public class EnderPearlListener implements Listener {

    private final DeruyPlugin plugin;

    public EnderPearlListener(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return;

        boolean blocked = plugin.getConfig().getBoolean("lifesteal.restrictions.ender-pearl-tp-blocked", true);
        if (!blocked) return;

        Player player = event.getPlayer();
        event.setCancelled(true);
        player.sendMessage("§c엔더진주로 인한 텔레포트가 금지되어 있습니다. (던지는 것은 가능)");
    }
}
