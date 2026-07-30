package com.deruy.plugin.lifesteal.listeners;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 하트 시스템 핵심: 킬 시 +1하트, 사망 시 -1하트.
 * PVP 데미지 발생 시 컴벳태그 부여 (다른 리스너들이 공통으로 참조).
 *
 * config의 lifesteal.double-loss-pairs 에 등록된 (victim-role, killer-role) 조합이면
 * 해당 multiplier만큼 하트 손실이 배가된다. (예: LE가 FN에게 죽으면 2배)
 *
 * 사망시 config의 sounds.on-death 설정에 따라 사운드를 재생하고,
 * death.drop-scatter-radius 설정에 따라 드롭 아이템을 흩뿌린다.
 */
public class DeathListener implements Listener {

    private final DeruyPlugin plugin;

    public DeathListener(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!plugin.getLifeStealManager().isSystemEnabled()) return;

        if (event.getEntity() instanceof Player victim && event.getDamager() instanceof Player attacker) {
            long durationMillis = plugin.getConfig().getLong("combat-tag.duration-seconds", 15) * 1000L;
            plugin.getLifeStealManager().tagCombat(victim.getUniqueId(), durationMillis);
            plugin.getLifeStealManager().tagCombat(attacker.getUniqueId(), durationMillis);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (!plugin.getLifeStealManager().isSystemEnabled()) return;

        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        plugin.playConfiguredSound("on-death", victim.getLocation());
        scatterDrops(event);

        boolean naturalDeathRemovesHeart = plugin.getConfig()
                .getBoolean("lifesteal.natural-death-removes-heart", false);

        boolean heartWasRemoved = killer != null || naturalDeathRemovesHeart;
        if (heartWasRemoved) {
            int amount = killer != null ? resolveLossAmount(victim, killer) : 1;
            plugin.getLifeStealManager().removeHeart(victim, amount);
        }

        if (killer != null) {
            plugin.getBountyManager().onKill(killer, victim);

            if (!plugin.getLifeStealManager().addHeart(killer)) {
                dropHeartItem(victim);
            }
        } else if (heartWasRemoved) {
            dropHeartItem(victim);
        }
    }

    /**
     * config의 lifesteal.double-loss-pairs 에서 (victim의 역할, killer의 역할) 조합과
     * 일치하는 항목을 찾아 그 배수를 적용한 하트 손실 개수를 반환. 없으면 기본 1.
     *
     * victim-role/killer-role은 "L" 처럼 하나만 적어도 되고, "F,N,M" 처럼 쉼표로
     * 여러 역할을 나열하면 그 중 하나라도 해당되면 매칭되는 OR 조건으로 취급한다.
     */
    private int resolveLossAmount(Player victim, Player killer) {
        List<?> rawList = plugin.getConfig().getList("lifesteal.double-loss-pairs");
        if (rawList == null || rawList.isEmpty()) return 1;

        String victimRole = plugin.getRoleManager().resolveConfiguredRole(victim);
        String killerRole = plugin.getRoleManager().resolveConfiguredRole(killer);
        if (victimRole == null || killerRole == null) return 1;

        for (Object raw : rawList) {
            if (!(raw instanceof java.util.Map<?, ?> rawMap)) continue;

            Object vRoleRaw = rawMap.get("victim-role");
            Object kRoleRaw = rawMap.get("killer-role");
            if (vRoleRaw == null || kRoleRaw == null) continue;

            if (matchesAnyRole(victimRole, String.valueOf(vRoleRaw))
                    && matchesAnyRole(killerRole, String.valueOf(kRoleRaw))) {
                Object multiplierRaw = rawMap.get("multiplier");
                double multiplier = multiplierRaw instanceof Number n ? n.doubleValue() : 2.0;
                return Math.max(1, (int) Math.round(multiplier));
            }
        }

        return 1;
    }

    /** roleListRaw를 쉼표로 나눠서, actualRole이 그 중 하나와 (공백 무시하고) 일치하는지 확인 */
    private boolean matchesAnyRole(String actualRole, String roleListRaw) {
        for (String candidate : roleListRaw.split(",")) {
            if (actualRole.equalsIgnoreCase(candidate.trim())) {
                return true;
            }
        }
        return false;
    }

    private void dropHeartItem(Player victim) {
        victim.getWorld().dropItemNaturally(victim.getLocation(), plugin.getRecipeManager().createHeartItem());
    }

    /**
     * config의 death.drop-scatter-radius 설정에 따라 사망 드롭 아이템을
     * 죽은 위치 기준 랜덤한 반경 안에 흩뿌린다. 0(또는 미설정)이면 원래대로 한 곳에 모아 떨어짐.
     */
    private void scatterDrops(PlayerDeathEvent event) {
        double radius = plugin.getConfig().getDouble("death.drop-scatter-radius", 0);
        if (radius <= 0) return;

        List<ItemStack> drops = new ArrayList<>(event.getDrops());
        if (drops.isEmpty()) return;

        event.getDrops().clear();

        Location center = event.getEntity().getLocation();
        var world = center.getWorld();
        var random = ThreadLocalRandom.current();

        for (ItemStack item : drops) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = random.nextDouble() * radius;
            double x = center.getX() + Math.cos(angle) * dist;
            double z = center.getZ() + Math.sin(angle) * dist;
            Location dropLoc = new Location(world, x, center.getY(), z);
            world.dropItemNaturally(dropLoc, item);
        }
    }
}
