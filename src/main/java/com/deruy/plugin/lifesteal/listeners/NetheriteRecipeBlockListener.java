package com.deruy.plugin.lifesteal.listeners;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.inventory.ItemStack;

/**
 * pending #13: 기존 netherite.enabled 설정과는 별개의 독립 기능.
 * "네더라이트 장비 제작"(스미싱 테이블 업그레이드)만 막고,
 * 이미 갖고 있는 네더라이트 장비는 그대로 사용 가능하게 유지한다.
 *
 * config: lifesteal.recipe-block.netherite-upgrade-smithing
 */
public class NetheriteRecipeBlockListener implements Listener {

    private final DeruyPlugin plugin;

    public NetheriteRecipeBlockListener(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        if (!plugin.getLifeStealManager().isSystemEnabled()) return;

        boolean blocked = plugin.getConfig()
                .getBoolean("lifesteal.recipe-block.netherite-upgrade-smithing", false);
        if (!blocked) return;

        ItemStack result = event.getResult();
        if (result == null) return;

        if (result.getType().name().startsWith("NETHERITE_")) {
            event.setResult(null);
            for (HumanEntity viewer : event.getViewers()) {
                viewer.sendMessage("§c현재 네더라이트 장비 제작이 비활성화되어 있습니다. (기존 장비는 사용 가능)");
            }
        }
    }
}
