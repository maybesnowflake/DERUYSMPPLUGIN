package com.deruy.plugin.supplydrop;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * 서플라이드랍 상자를 클릭하면 GUI를 여는 대신 즉시 내용물을 지급하고 블록을 제거한다.
 */
public class SupplyChestListener implements Listener {

    private final DeruyPlugin plugin;

    public SupplyChestListener(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null) return;
        if (event.getClickedBlock().getType() != Material.CHEST) return;

        var loc = event.getClickedBlock().getLocation();
        SupplyChestRegistry registry = plugin.getSupplyChestRegistry();
        if (!registry.isTracked(loc)) return;

        event.setCancelled(true);

        List<ItemStack> items = registry.consume(loc);
        Player player = event.getPlayer();

        event.getClickedBlock().setType(Material.AIR);

        if (items != null) {
            for (ItemStack item : items) {
                var leftover = player.getInventory().addItem(item);
                leftover.values().forEach(remain -> player.getWorld().dropItemNaturally(player.getLocation(), remain));
            }
            player.sendMessage("§6[보급] §e보급품을 획득했습니다!");
        }
    }
}
