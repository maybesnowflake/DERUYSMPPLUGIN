package com.deruy.plugin.bounty;

import com.deruy.plugin.DeruyPlugin;
import com.deruy.plugin.events.GameEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 바운티 이벤트. 두 가지 방식이 공존한다:
 *  - 1:1 페어(pair): 두 플레이어가 서로를 쫓음
 *  - 서버 전체 단일 타겟(random/setGlobalTarget): 온라인 전원이 한 명을 쫓고, 누구든 처치하면 보상
 *
 * config: bounty.reward-items
 */
public class BountyManager implements GameEvent {

    private final DeruyPlugin plugin;
    private boolean running = false;

    private final Map<UUID, UUID> bounties = new HashMap<>();
    private UUID globalTarget;

    public BountyManager(DeruyPlugin plugin) {
        this.plugin = plugin;
        bounties.putAll(plugin.getDataStore().loadBountyPairs());
        if (!bounties.isEmpty()) {
            plugin.getLogger().info("바운티 페어 " + (bounties.size() / 2) + "쌍을 data.yml에서 불러왔습니다.");
        }
        this.globalTarget = plugin.getDataStore().loadGlobalBountyTarget();
        if (globalTarget != null) {
            plugin.getLogger().info("서버전체 바운티 타겟을 data.yml에서 불러왔습니다: " + globalTarget);
        }
    }

    @Override
    public String getName() {
        return "bounty";
    }

    @Override
    public void start() {
        if (running) return;
        running = true;
        Bukkit.broadcastMessage("§4§l바운티 §e이벤트가 시작되었습니다. 지정된 타겟을 처치하면 추가 보상이 지급됩니다.");
    }

    @Override
    public void stop() {
        if (!running) return;
        running = false;
        bounties.clear();
        Bukkit.broadcastMessage("§4§l바운티 §e이벤트가 종료되었습니다.");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // ---------------- 1:1 페어 ----------------

    public void pair(Player a, Player b) {
        bounties.put(a.getUniqueId(), b.getUniqueId());
        bounties.put(b.getUniqueId(), a.getUniqueId());
        plugin.getDataStore().saveBountyPair(a.getUniqueId(), b.getUniqueId());
        a.sendMessage("§4[바운티] §e당신의 타겟: " + b.getName());
        b.sendMessage("§4[바운티] §e당신의 타겟: " + a.getName());
    }

    public void unpair(Player a) {
        UUID targetId = bounties.remove(a.getUniqueId());
        if (targetId != null) {
            bounties.remove(targetId);
        }
        plugin.getDataStore().removeBountyPair(a.getUniqueId());
    }

    public UUID getTarget(Player player) {
        return bounties.get(player.getUniqueId());
    }

    // ---------------- 서버 전체 단일 타겟 ----------------

    /** 랜덤 온라인 플레이어를 서버 전체가 쫓는 단일 타겟으로 지정 */
    public boolean setRandomGlobalTarget() {
        var online = new java.util.ArrayList<>(Bukkit.getOnlinePlayers());
        if (online.isEmpty()) return false;

        java.util.Collections.shuffle(online);
        setGlobalTarget(online.get(0));
        return true;
    }

    public void setGlobalTarget(Player target) {
        this.globalTarget = target.getUniqueId();
        plugin.getDataStore().saveGlobalBountyTarget(globalTarget);

        String msg = plugin.getMessage("bounty-global-target-set",
                        "&4&l[바운티] &e서버 전체가 &c{target}&e님을 쫓습니다! 처치하면 보상을 받습니다.")
                .replace("{target}", target.getName());
        Bukkit.broadcastMessage(msg);
    }

    public UUID getGlobalTarget() {
        return globalTarget;
    }

    public void clearGlobalTarget() {
        this.globalTarget = null;
        plugin.getDataStore().clearGlobalBountyTarget();
    }

    // ---------------- 킬 처리 (페어/전체타겟 공통) ----------------

    public void onKill(Player killer, Player victim) {
        if (!running) return;

        boolean rewarded = false;

        // 서버 전체 타겟을 죽인 경우
        if (globalTarget != null && globalTarget.equals(victim.getUniqueId())) {
            String deathMessage = plugin.getMessage("bounty-global-target-killed",
                            "&4&l[바운티] &a{killer}님이 서버 전체 타겟 {victim}님을 처치했습니다!")
                    .replace("{killer}", killer.getName())
                    .replace("{victim}", victim.getName());
            Bukkit.broadcastMessage(deathMessage);

            giveReward(killer);
            clearGlobalTarget();
            rewarded = true;
        }

        // 1:1 페어 타겟을 죽인 경우
        UUID pairTarget = bounties.get(killer.getUniqueId());
        if (pairTarget != null && pairTarget.equals(victim.getUniqueId())) {
            String deathMessage = plugin.getMessage("bounty-target-killed",
                            "&4&l[바운티] &a{killer}님이 타겟 {victim}님을 처치했습니다!")
                    .replace("{killer}", killer.getName())
                    .replace("{victim}", victim.getName());
            Bukkit.broadcastMessage(deathMessage);

            giveReward(killer);
            bounties.remove(killer.getUniqueId());
            bounties.remove(victim.getUniqueId());
            plugin.getDataStore().removeBountyPair(killer.getUniqueId());
            rewarded = true;
        }

        if (rewarded && plugin.getConfig().getBoolean("bounty.end-on-death", false)) {
            Bukkit.broadcastMessage("§4§l[바운티] §e바운티 대상이 사망하여 이벤트가 종료됩니다.");
            stop();
        }
    }

    private void giveReward(Player killer) {
        var items = plugin.getSupplyChestRegistry().rollRewards(
                plugin.getConfig().getStringList("bounty.reward-items"), plugin.getLogger());
        for (var item : items) {
            var leftover = killer.getInventory().addItem(item);
            leftover.values().forEach(remain -> killer.getWorld().dropItemNaturally(killer.getLocation(), remain));
        }
    }
}
