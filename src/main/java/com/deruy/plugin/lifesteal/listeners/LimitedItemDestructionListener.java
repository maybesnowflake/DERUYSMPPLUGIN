package com.deruy.plugin.lifesteal.listeners;

import com.deruy.plugin.DeruyPlugin;
import com.deruy.plugin.lifesteal.RecipeManager;
import org.bukkit.Bukkit;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;

/**
 * 개수제한 걸린 아이템(item-limits에 등록된 레시피로 만든 것)이 실제로 파괴되면
 * ItemLimitManager의 추적 목록에서 제거해서 "지금 진짜로 몇 개 존재하는지"를 정확히 유지한다.
 *
 * 감지하는 파괴 경로:
 *  - 바닥에 떨어진 상태에서 용암/불/폭발/선인장 등으로 파괴됨 (EntityDamageEvent 이후 엔티티 소멸 확인)
 *  - 보이드 추락·사망 제거 (EntityRemoveEvent OUT_OF_WORLD/DEATH)
 *  - 셜커 상자·꾸러미째 보이드로 떨어지면 안에 든 제한 아이템도 함께 제거
 *    (불·용암·폭발로 부서지면 내용물은 밖으로 쏟아져 살아남으므로 제거하지 않음)
 *  - 내구도가 다 닳아서 부서짐 (PlayerItemBreakEvent)
 *  - 자연 디스폰(5분 방치)은 아예 막아서 추적을 놓치지 않게 함 (ItemDespawnEvent 취소)
 */
public class LimitedItemDestructionListener implements Listener {

    private final DeruyPlugin plugin;

    public LimitedItemDestructionListener(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    /** 추적 대상 아이템은 자연 디스폰(5분 방치 후 사라짐)되지 않게 막는다. */
    @EventHandler
    public void onDespawn(ItemDespawnEvent event) {
        if (!containsLimited(event.getEntity().getItemStack())) return;
        event.setCancelled(true); // 사라지지 않게 막음 (추적 정확도 유지)
    }

    /**
     * 화상/용암/폭발/선인장 등으로 바닥의 아이템 엔티티가 파괴되는 경우.
     * 셜커·꾸러미는 이렇게 부서지면 내용물을 밖으로 쏟으므로 안의 것은 건드리지 않는다
     * (쏟아진 아이템은 그 엔티티가 실제로 파괴될 때 따로 잡힌다).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Item itemEntity)) return;
        ItemStack stack = itemEntity.getItemStack();
        String recipeId = RecipeManager.getRecipeId(stack);
        if (recipeId == null || !plugin.getItemLimitManager().isLimited(recipeId)) return;
        // Only lethal damage is relevant; pickups/unloads are not destruction.
        if (event.getFinalDamage() < itemEntity.getHealth()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (itemEntity.isDead()) plugin.getItemLimitManager().markDestroyed(recipeId, stack);
        });
    }

    /**
     * 보이드로 떨어진 아이템은 피해 이벤트 없이 바로 사라지고, 셜커·꾸러미도 내용물을 쏟지 않고
     * 통째로 없어진다. 그래서 보이드일 때만 안에 든 것까지 파괴 처리한다.
     * DEATH(불·용암·폭발 등)는 아이템 자신만 — 컨테이너 내용물은 밖으로 떨어져 살아남는다.
     * 제거는 UUID 기준이라 위 경로와 겹쳐도 한 번만 빠진다.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemRemoved(EntityRemoveEvent event) {
        if (!(event.getEntity() instanceof Item itemEntity)) return;
        ItemStack stack = itemEntity.getItemStack();
        if (event.getCause() == EntityRemoveEvent.Cause.OUT_OF_WORLD) {
            forEachLimited(stack, 0, (id, item) -> plugin.getItemLimitManager().markDestroyed(id, item));
        } else if (event.getCause() == EntityRemoveEvent.Cause.DEATH) {
            String id = RecipeManager.getRecipeId(stack);
            if (id != null && plugin.getItemLimitManager().isLimited(id)) plugin.getItemLimitManager().markDestroyed(id, stack);
        }
    }

    boolean containsLimited(ItemStack stack) {
        boolean[] found = {false};
        forEachLimited(stack, 0, (id, item) -> found[0] = true);
        return found[0];
    }

    private void forEachLimited(ItemStack stack, int depth, java.util.function.BiConsumer<String, ItemStack> action) {
        if (stack == null || stack.getType().isAir() || depth > 3) return;
        String recipeId = RecipeManager.getRecipeId(stack);
        if (recipeId != null && plugin.getItemLimitManager().isLimited(recipeId)) action.accept(recipeId, stack);
        if (!stack.hasItemMeta()) return;
        var meta = stack.getItemMeta();
        if (meta instanceof BlockStateMeta bsm && bsm.hasBlockState() && bsm.getBlockState() instanceof ShulkerBox box) {
            for (ItemStack inner : box.getInventory().getContents()) forEachLimited(inner, depth + 1, action);
        }
        if (meta instanceof BundleMeta bundle) {
            for (ItemStack inner : bundle.getItems()) forEachLimited(inner, depth + 1, action);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTotem(org.bukkit.event.entity.EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof org.bukkit.entity.Player player) || event.getHand() == null) return;
        ItemStack item = player.getInventory().getItem(event.getHand()).clone();
        String id = RecipeManager.getRecipeId(item);
        if (id != null && plugin.getItemLimitManager().isLimited(id)) {
            Bukkit.getScheduler().runTask(plugin, () -> plugin.getItemLimitManager().markDestroyed(id, item));
        }
    }

    /** 도구/무기/방어구가 내구도 소진으로 부서지는 경우 */
    @EventHandler
    public void onItemBreak(PlayerItemBreakEvent event) {
        ItemStack broken = event.getBrokenItem();
        String recipeId = RecipeManager.getRecipeId(broken);
        if (recipeId == null) return;
        if (!plugin.getItemLimitManager().isLimited(recipeId)) return;

        plugin.getItemLimitManager().markDestroyed(recipeId, broken);
    }
}
