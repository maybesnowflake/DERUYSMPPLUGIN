package com.deruy.plugin.lifesteal.listeners;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * pending #5: 겉날개 / 엔더상자를 "컴벳태그 중일 때만" 제한.
 *
 * 겉날개는 장착 자체는 항상 허용하고, 대신 "글라이딩이 시작되는 순간"
 * (EntityToggleGlideEvent, isGliding()==true)을 컴벳중이면 취소한다.
 * 이렇게 하면 미리 껴놓고 전투 들어간 경우까지 전부 커버된다.
 */
public class RestrictionListener implements Listener {

    private final DeruyPlugin plugin;

    public RestrictionListener(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean isCombatTagged(Player player) {
        return plugin.getLifeStealManager().isCombatTagged(player.getUniqueId());
    }

    @EventHandler
    public void onEnderChestUse(PlayerInteractEvent event) {
        if (!plugin.getLifeStealManager().isSystemEnabled()) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null) return;
        if (event.getClickedBlock().getType() != Material.ENDER_CHEST) return;

        boolean ruleActive = plugin.getConfig()
                .getBoolean("lifesteal.restrictions.ender-chest-blocked", true);
        if (!ruleActive) return;

        Player player = event.getPlayer();
        if (isCombatTagged(player)) {
            event.setCancelled(true);
            player.sendMessage(plugin.getMessage("ender-chest-blocked", "&c전투 중에는 엔더상자를 사용할 수 없습니다."));
        }
    }

    // ---------------- 겉날개: 착용은 항상 허용, 글라이딩 시작만 차단 ----------------

    @EventHandler
    public void onToggleGlide(EntityToggleGlideEvent event) {
        if (!plugin.getLifeStealManager().isSystemEnabled()) return;
        if (!event.isGliding()) return; // 글라이딩 "시작"만 막음 (멈추는 건 그대로 허용)
        if (!(event.getEntity() instanceof Player player)) return;

        boolean ruleActive = plugin.getConfig()
                .getBoolean("lifesteal.restrictions.elytra-blocked", true);
        if (!ruleActive) return;

        if (isCombatTagged(player)) {
            event.setCancelled(true);
            player.sendMessage(plugin.getMessage("elytra-blocked", "&c전투 중에는 겉날개로 활공할 수 없습니다."));
        }
    }
}
