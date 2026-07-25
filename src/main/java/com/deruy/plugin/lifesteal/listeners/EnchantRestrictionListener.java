package com.deruy.plugin.lifesteal.listeners;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * pending #10: 검류 아이템에는 화염부여(Fire Aspect) 인챈트가 절대 붙지 않도록 차단.
 * - 마법부여 테이블 제안 단계에서 제거
 * - 실제 부여 단계에서 제거
 * - 모루에서 마법책으로 합치는 것도 차단
 */
public class EnchantRestrictionListener implements Listener {

    private final DeruyPlugin plugin;

    public EnchantRestrictionListener(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean isSword(ItemStack item) {
        return item != null && item.getType().name().endsWith("_SWORD");
    }

    @EventHandler
    public void onPrepareEnchant(PrepareItemEnchantEvent event) {
        if (!plugin.getLifeStealManager().isSystemEnabled()) return;
        if (!isSword(event.getItem())) return;

        for (org.bukkit.enchantments.EnchantmentOffer offer : event.getOffers()) {
            if (offer != null && offer.getEnchantment().equals(Enchantment.FIRE_ASPECT)) {
                // 오퍼 배열 요소 자체는 null로 바꿀 수 없어 레벨을 0으로 무력화
                offer.setEnchantmentLevel(0);
            }
        }
    }

    @EventHandler
    public void onEnchant(EnchantItemEvent event) {
        if (!plugin.getLifeStealManager().isSystemEnabled()) return;
        if (!isSword(event.getItem())) return;
        if (event.getEnchantsToAdd().containsKey(Enchantment.FIRE_ASPECT)) {
            event.getEnchantsToAdd().remove(Enchantment.FIRE_ASPECT);
        }
    }

    @EventHandler
    public void onAnvilPrepare(PrepareAnvilEvent event) {
        if (!plugin.getLifeStealManager().isSystemEnabled()) return;

        AnvilInventory inv = event.getInventory();
        ItemStack base = inv.getItem(0);
        ItemStack addition = inv.getItem(1);
        ItemStack result = event.getResult();

        if (!isSword(base) || result == null) return;

        ItemMeta resultMeta = result.getItemMeta();
        if (resultMeta == null) return;

        if (resultMeta.hasEnchant(Enchantment.FIRE_ASPECT)) {
            resultMeta.removeEnchant(Enchantment.FIRE_ASPECT);
            result.setItemMeta(resultMeta);
            event.setResult(result);
        }
    }
}
