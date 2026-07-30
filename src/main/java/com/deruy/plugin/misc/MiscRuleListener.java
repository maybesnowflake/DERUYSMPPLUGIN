package com.deruy.plugin.misc;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 기본룰 잡다한 항목들:
 * - 토템작동: EntityResurrectEvent 자체를 막지 않는 것이 기본이지만, config로 강제 on/off 가능하게.
 * - 사망시 포션이팩트 유지: 사망 시점의 이펙트를 저장해뒀다가 리스폰 시 재적용.
 * - 화살이 몸에 박히지 않음: 화살이 플레이어에게 명중하면 즉시 제거.
 *
 * config:
 *   totem.enabled
 *   death.keep-potion-effects
 *   arrow.no-stick
 */
public class MiscRuleListener implements Listener {

    private final DeruyPlugin plugin;
    private final Map<UUID, List<PotionEffect>> storedEffects = new HashMap<>();

    public MiscRuleListener(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------- 토템작동 + 이펙트 유지 ----------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onResurrect(EntityResurrectEvent event) {
        boolean totemEnabled = plugin.getConfig().getBoolean("totem.enabled", true);
        if (!totemEnabled) {
            event.setCancelled(true);
            return;
        }

        if (!(event.getEntity() instanceof Player player)) return;
        if (!plugin.getConfig().getBoolean("totem.keep-effects-on-use", true)) return;
        if (event.isCancelled()) return;

        // 불사의 토템 발동 직전 상태의 이펙트를 저장해뒀다가, 토템이 이펙트를 정리한 뒤 무조건 되돌려준다.
        List<PotionEffect> beforeEffects = new ArrayList<>(player.getActivePotionEffects());
        if (beforeEffects.isEmpty()) return;

        // 토템 로직은 이 이벤트가 끝난 "직후" 같은 틱에 처리되므로, 다음 틱에 강제로 재적용한다.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (PotionEffect effect : beforeEffects) {
                player.addPotionEffect(effect, true); // force=true: 기존 값 덮어쓰고 무조건 재적용
            }
        });
    }

    // ---------------- 사망시 포션이팩트 유지 ----------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        if (!plugin.getConfig().getBoolean("death.keep-potion-effects", true)) return;

        Player player = event.getEntity();
        storedEffects.put(player.getUniqueId(), new ArrayList<>(player.getActivePotionEffects()));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onRespawn(PlayerRespawnEvent event) {
        if (!plugin.getConfig().getBoolean("death.keep-potion-effects", true)) return;

        Player player = event.getPlayer();
        List<PotionEffect> effects = storedEffects.remove(player.getUniqueId());
        if (effects == null || effects.isEmpty()) return;

        // 리스폰 직후 서버가 이펙트를 한번 더 정리하는 경우가 있어 1틱 지연 후 재적용
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (PotionEffect effect : effects) {
                player.addPotionEffect(effect);
            }
        });
    }

    // ---------------- 화살이 몸에 박히지 않음 ----------------

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!plugin.getConfig().getBoolean("arrow.no-stick", true)) return;
        if (!(event.getEntity() instanceof Arrow arrow)) return;
        if (!(event.getHitEntity() instanceof Player)) return;

        plugin.getServer().getScheduler().runTask(plugin, arrow::remove);
    }
}
