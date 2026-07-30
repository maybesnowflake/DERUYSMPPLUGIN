package com.deruy.plugin.lifesteal.listeners;

import com.deruy.plugin.DeruyPlugin;
import com.deruy.plugin.lifesteal.RecipeManager;
import org.bukkit.entity.Cat;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Wolf;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 하트 아이템은 아이콘이 빨간 염료(RED_DYE)라서, 그대로 두면 진짜 염료처럼
 * 양털/가죽방어구/불꽃로켓/콘크리트가루 염색, 베틀 배너무늬, 양/늑대/고양이 염색 등에
 * 그대로 쓰일 수 있다. 전부 차단한다.
 */
public class HeartDyeRestrictionListener implements Listener {

    private final DeruyPlugin plugin;

    public HeartDyeRestrictionListener(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    /** 조합대에서 하트가 재료로 들어간 모든 레시피(양털/가죽방어구/불꽃로켓/콘크리트가루 등) 차단 */
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        for (ItemStack ingredient : event.getInventory().getMatrix()) {
            if (RecipeManager.isHeartItem(ingredient)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    /** 베틀(배너 무늬 적용)에 하트를 염료로 넣는 것 차단 */
    @EventHandler
    public void onLoomClick(InventoryClickEvent event) {
        if (event.getInventory().getType() != InventoryType.LOOM) return;

        boolean cursorIsHeart = RecipeManager.isHeartItem(event.getCursor());
        boolean currentIsHeart = RecipeManager.isHeartItem(event.getCurrentItem());
        if (!cursorIsHeart && !currentIsHeart) return;

        event.setCancelled(true);
        if (event.getWhoClicked() instanceof Player player) {
            player.sendMessage(plugin.getMessage("heart-not-a-dye", "&c하트는 염료로 사용할 수 없습니다."));
        }
    }

    /** 양/늑대/고양이를 하트로 직접 염색하려는 것 차단 */
    @EventHandler
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Sheep)
                && !(event.getRightClicked() instanceof Wolf)
                && !(event.getRightClicked() instanceof Cat)) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!RecipeManager.isHeartItem(item)) {
            item = player.getInventory().getItemInOffHand();
            if (!RecipeManager.isHeartItem(item)) return;
        }

        event.setCancelled(true);
        player.sendMessage(plugin.getMessage("heart-not-a-dye", "&c하트는 염료로 사용할 수 없습니다."));
    }
}
