package com.deruy.plugin.lifesteal.listeners;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingRecipe;
import org.bukkit.inventory.Recipe;

/**
 * config의 item-limits에 등록된 레시피(예: totem, mace, custom_netherite_chestplate)가
 * 이미 서버 전체 상한에 도달했으면 조합대에 결과물이 아예 안 뜨게 막고,
 * 실제로 제작에 성공하면 그 개체에 고유 UUID를 태그해서 실시간 추적 목록에 등록한다.
 */
public class ItemLimitListener implements Listener {

    private final DeruyPlugin plugin;

    public ItemLimitListener(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPrepare(PrepareItemCraftEvent event) {
        String recipeId = resolveRecipeId(event.getRecipe());
        if (recipeId == null) return;
        if (!plugin.getItemLimitManager().isLimited(recipeId)) return;

        if (!plugin.getItemLimitManager().canCraftMore(recipeId)) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler
    public void onCraft(CraftItemEvent event) {
        String recipeId = resolveRecipeId(event.getRecipe());
        if (recipeId == null) return;
        if (!plugin.getItemLimitManager().isLimited(recipeId)) return;

        if (!plugin.getItemLimitManager().canCraftMore(recipeId)) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                player.sendMessage(plugin.getMessage("item-limit-reached",
                        "&c이 아이템은 서버 전체 제작 개수 상한에 도달해 더 이상 만들 수 없습니다."));
            }
            return;
        }

        var currentItem = event.getCurrentItem();
        if (currentItem == null) return;

        var tagged = plugin.getItemLimitManager().tagAndRegisterInstance(recipeId, currentItem);
        event.getInventory().setResult(tagged);
    }

    private String resolveRecipeId(Recipe recipe) {
        if (!(recipe instanceof CraftingRecipe craftingRecipe)) return null;
        NamespacedKey key = craftingRecipe.getKey();
        if (!key.getNamespace().equalsIgnoreCase(plugin.getName().toLowerCase())) return null;
        return key.getKey();
    }
}
