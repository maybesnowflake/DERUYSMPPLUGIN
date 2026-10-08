package com.deruy.plugin.lifesteal.listeners;
import com.deruy.plugin.DeruyPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;

/** Limited crafts are committed one at a time, each with its own UUID. */
public final class ItemLimitListener implements Listener {
    private final DeruyPlugin plugin;
    public ItemLimitListener(DeruyPlugin plugin) { this.plugin = plugin; }
    @EventHandler
    public void onPrepare(PrepareItemCraftEvent event) {
        String id = resolveRecipeId(event.getRecipe());
        if (id != null && plugin.getItemLimitManager().isLimited(id)
                && !plugin.getItemLimitManager().canCraftMore(id)) event.getInventory().setResult(null);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        String id = resolveRecipeId(event.getRecipe());
        if (id == null || !plugin.getItemLimitManager().isLimited(id)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT
                && event.getClick() != ClickType.SHIFT_LEFT && event.getClick() != ClickType.SHIFT_RIGHT) return;
        if (event.getCurrentItem() == null || event.getCurrentItem().getType().isAir()) return;
        boolean bulk = event.isShiftClick();
        if (!bulk && event.getCursor() != null && !event.getCursor().getType().isAir()) return;
        ItemStack[] matrix = event.getInventory().getMatrix();
        int available = java.util.Arrays.stream(matrix).filter(i -> i != null && !i.getType().isAir())
                .mapToInt(ItemStack::getAmount).min().orElse(0);
        int crafted = 0;
        // Unique UUID items cannot stack; do not consume materials when storage is full.
        for (int n = 0; n < (bulk ? available : Math.min(available,1)); n++) {
            if (!plugin.getItemLimitManager().canCraftMore(id)) break;
            if (bulk && java.util.Arrays.stream(player.getInventory().getStorageContents())
                    .noneMatch(i -> i == null || i.getType().isAir())) break;
            ItemStack template = event.getRecipe().getResult().clone();
            template.setAmount(1);
            ItemStack result = plugin.getItemLimitManager().tagAndRegisterInstance(id, template);
            for (int slot = 0; slot < matrix.length; slot++) {
                ItemStack ingredient = matrix[slot];
                if (ingredient == null || ingredient.getType().isAir()) continue;
                org.bukkit.Material remainder = ingredient.getType().getCraftingRemainingItem();
                if (ingredient.getAmount() == 1) matrix[slot] = remainder == null ? null : new ItemStack(remainder);
                else {
                    ingredient.setAmount(ingredient.getAmount()-1);
                    if (remainder != null) give(player, new ItemStack(remainder));
                }
            }
            if (bulk) give(player, result); else player.setItemOnCursor(result);
            crafted++;
            // Bucket/remainder recipes must be matched anew after this craft.
            if (java.util.Arrays.stream(event.getInventory().getMatrix())
                    .filter(i -> i != null && !i.getType().isAir())
                    .anyMatch(i -> i.getType().getCraftingRemainingItem() != null)) break;
        }
        if (crafted > 0) {
            event.getInventory().setMatrix(matrix);
            if (!plugin.getItemLimitManager().canCraftMore(id)) event.getInventory().setResult(null);
            plugin.getServer().getScheduler().runTask(plugin, player::updateInventory);
        } else if (!plugin.getItemLimitManager().canCraftMore(id)) {
            player.sendMessage(plugin.getMessage("item-limit-reached", "&c서버 전체 아이템 개수 상한에 도달했습니다."));
        }
    }
    private void give(Player player, ItemStack item) {
        player.getInventory().addItem(item).values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(),i));
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAutoCraft(org.bukkit.event.block.CrafterCraftEvent event) {
        String id = resolveRecipeId(event.getRecipe());
        if (id != null && plugin.getItemLimitManager().isLimited(id)) event.setCancelled(true);
    }
    private String resolveRecipeId(Recipe recipe) {
        if (!(recipe instanceof CraftingRecipe crafting)) return null;
        NamespacedKey key = crafting.getKey();
        return key.getNamespace().equalsIgnoreCase(plugin.getName()) ? key.getKey() : null;
    }
}
