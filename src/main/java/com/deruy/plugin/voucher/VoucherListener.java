package com.deruy.plugin.voucher;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public class VoucherListener implements Listener {

    private final DeruyPlugin plugin;

    public VoucherListener(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        int tier = plugin.getVoucherManager().getTier(item);
        if (tier == -1) return;

        event.setCancelled(true);

        Player player = event.getPlayer();
        item.setAmount(item.getAmount() - 1);

        List<String> rewardEntries = plugin.getConfig().getStringList("voucher.tier" + tier + ".reward-items");
        var items = plugin.getSupplyChestRegistry().rollRewards(rewardEntries, plugin.getLogger());

        if (items.isEmpty()) {
            player.sendMessage(plugin.getMessage("voucher-empty", "&7상품권을 개봉했지만 아무것도 나오지 않았습니다."));
            return;
        }

        for (ItemStack reward : items) {
            var leftover = player.getInventory().addItem(reward);
            leftover.values().forEach(remain -> player.getWorld().dropItemNaturally(player.getLocation(), remain));
        }

        String msg = plugin.getMessage("voucher-opened", "&e상품권(티어 {tier})을 개봉해서 보상을 획득했습니다!")
                .replace("{tier}", String.valueOf(tier));
        player.sendMessage(msg);
    }
}
