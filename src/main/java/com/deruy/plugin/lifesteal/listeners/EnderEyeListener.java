package com.deruy.plugin.lifesteal.listeners;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * /endereyes on|off 로 서버 재시작 없이 즉시 켜고 끌 수 있는 엔더의 눈 던지기 차단.
 */
public class EnderEyeListener implements Listener {

    private final DeruyPlugin plugin;

    public EnderEyeListener(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.ENDER_EYE) return;

        boolean blocked = plugin.getConfig().getBoolean("lifesteal.restrictions.ender-eyes-blocked", false);
        if (!blocked) return;

        Player player = event.getPlayer();
        event.setCancelled(true);
        player.sendMessage(plugin.getMessage("ender-eyes-blocked", "&c엔더의 눈 사용이 금지되어 있습니다."));
    }
}
