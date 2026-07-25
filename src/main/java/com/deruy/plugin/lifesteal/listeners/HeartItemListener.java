package com.deruy.plugin.lifesteal.listeners;

import com.deruy.plugin.DeruyPlugin;
import com.deruy.plugin.lifesteal.RecipeManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * "하트" 아이템(빨간 염료 아이콘, RecipeManager에서 PDC로 식별)을 우클릭하면
 * 아이템 1개를 소비하고 하트를 1개 지급한다.
 */
public class HeartItemListener implements Listener {

    private final DeruyPlugin plugin;

    public HeartItemListener(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (!RecipeManager.isHeartItem(item)) return;

        Player player = event.getPlayer();
        event.setCancelled(true);

        if (!plugin.getLifeStealManager().addHeart(player)) {
            player.sendMessage("§c이미 하트 상한선에 도달해 더 이상 늘릴 수 없습니다.");
            return;
        }

        item.setAmount(item.getAmount() - 1);
        player.sendMessage("§c하트를 1개 획득했습니다!");
    }
}
