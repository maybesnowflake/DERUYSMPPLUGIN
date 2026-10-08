package com.deruy.plugin.lifesteal.listeners;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * pending #4: 기존에는 트라이던트(리피터틴) 사용 자체를 전면 금지했으나,
 * 이제는 "컴벳태그 상태일 때만" 금지한다.
 *
 * 주의: PlayerRiptideEvent는 Cancellable이 아니라서 그 시점엔 이미 못 막는다.
 * 실제로 막아야 하는 건 리피터틴이 발동되기 직전의 우클릭(PlayerInteractEvent)이다.
 */
public class TridentListener implements Listener {

    private final DeruyPlugin plugin;

    public TridentListener(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (!plugin.getLifeStealManager().isSystemEnabled()) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.TRIDENT) return;
        if (item.getEnchantmentLevel(Enchantment.RIPTIDE) <= 0) return;

        boolean restrictOnlyInCombat = plugin.getConfig()
                .getBoolean("lifesteal.trident.restrict-only-in-combat", true);
        if (!restrictOnlyInCombat) return;

        Player player = event.getPlayer();
        if (!player.isInWaterOrRain()) return; // 리피터틴은 물/비 상태에서만 발동되므로 그 외엔 막을 필요 없음

        if (plugin.getLifeStealManager().isCombatTagged(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage("§c전투 중에는 트라이던트 순간이동을 사용할 수 없습니다.");
        }
    }
}
