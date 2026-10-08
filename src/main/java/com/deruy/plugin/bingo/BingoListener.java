package com.deruy.plugin.bingo;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;

public class BingoListener implements Listener {

    private final DeruyPlugin plugin;

    public BingoListener(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getBingoManager().isRunning()) return;
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        plugin.getBingoManager().checkBiome(event.getPlayer(), event.getTo().getBlock().getBiome());
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (!plugin.getBingoManager().isRunning()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        plugin.getBingoManager().checkItem(player, event.getItem().getItemStack().getType());
    }
}
