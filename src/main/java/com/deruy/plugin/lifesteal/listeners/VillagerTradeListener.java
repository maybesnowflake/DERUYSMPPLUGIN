package com.deruy.plugin.lifesteal.listeners;

import com.deruy.plugin.DeruyPlugin;
import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;

import java.util.List;

/**
 * pending #9: 주민 거래 무제한 (lifesteal.unlimited-villager-trades).
 * 최대 횟수를 늘리지 않고, 거래창을 열 때·거래할 때마다 사용 횟수와 수요를 0으로 되돌린다.
 * → 잠기지 않고 값도 오르지 않음. 설정을 끄면 바로 원래대로(주민 데이터를 바꿔 두지 않음).
 * 데이터팩 때 겪은 "No key buy" 도배·수요 누적·재입고 소리 문제는 재입고를 쓰지 않아 생기지 않음.
 */
public final class VillagerTradeListener implements Listener {
    private final DeruyPlugin plugin;
    public VillagerTradeListener(DeruyPlugin plugin) { this.plugin = plugin; }

    private boolean on() { return plugin.getConfig().getBoolean("lifesteal.unlimited-villager-trades", false); }

    /** 사용 횟수·수요·특가 누적을 초기화 (같은 거래 목록 객체를 그대로 수정) */
    public static void refresh(List<MerchantRecipe> recipes) {
        for (MerchantRecipe r : recipes) {
            r.setUses(0);
            r.setDemand(0);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!on() || !(e.getInventory() instanceof MerchantInventory inv)) return;
        if (!(inv.getMerchant() instanceof AbstractVillager villager)) return;
        List<MerchantRecipe> recipes = new java.util.ArrayList<>(villager.getRecipes());
        refresh(recipes);
        villager.setRecipes(recipes);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrade(PlayerTradeEvent e) {
        if (!on()) return;
        AbstractVillager villager = e.getVillager();
        // 거래가 반영된 다음 틱에 되돌림 (같은 창에서 계속 거래 가능)
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!villager.isValid()) return;
            List<MerchantRecipe> recipes = new java.util.ArrayList<>(villager.getRecipes());
            refresh(recipes);
            villager.setRecipes(recipes);
        });
    }
}
