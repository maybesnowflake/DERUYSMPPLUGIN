package com.deruy.plugin.lifesteal.listeners;

import com.deruy.plugin.DeruyPlugin;
import com.deruy.plugin.lifesteal.RecipeManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 개수제한 걸린 아이템(item-limits에 등록된 레시피로 만든 것)이 실제로 파괴되면
 * ItemLimitManager의 추적 목록에서 제거해서 "지금 진짜로 몇 개 존재하는지"를 정확히 유지한다.
 *
 * 감지하는 파괴 경로:
 *  - 바닥에 떨어진 상태에서 용암/불/보이드/폭발/선인장 등으로 파괴됨 (EntityDamageEvent 이후 엔티티 소멸 확인)
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
        String recipeId = RecipeManager.getRecipeId(event.getEntity().getItemStack());
        if (recipeId == null) return;
        if (!plugin.getItemLimitManager().isLimited(recipeId)) return;

        event.setCancelled(true); // 사라지지 않게 막음 (추적 정확도 유지)
    }

    /** 화상/용암/폭발/보이드/선인장 등으로 바닥의 아이템 엔티티가 파괴되는 경우 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Item itemEntity)) return;

        ItemStack stack = itemEntity.getItemStack();
        String recipeId = RecipeManager.getRecipeId(stack);
        if (recipeId == null) return;
        if (!plugin.getItemLimitManager().isLimited(recipeId)) return;

        // 데미지 처리가 끝난 다음 틱에 엔티티가 실제로 사라졌는지(파괴됐는지) 확인
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!itemEntity.isValid()) {
                plugin.getItemLimitManager().markDestroyed(recipeId, stack);
            }
        });
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
