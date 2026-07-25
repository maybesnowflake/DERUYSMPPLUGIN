package com.deruy.plugin.dragon;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * - 엔더드레곤 체력을 config로 변경 가능하게 (dragon.health)
 * - 드래곤알의 순간이동 능력 제거 + 지정된 특정 위치에 고정 스폰 (dragon.egg.fixed-location)
 * - 드래곤알 획득 방식을 "컴벳태그가 아닐 때 우클릭 후 N초 유지"로 변경 (dragon.egg.acquire-hold-seconds)
 */
public class DragonListener implements Listener {

    private final DeruyPlugin plugin;

    // 우클릭 채널링 진행중인 플레이어 -> 진행 tick
    private final Map<UUID, BukkitTask> channeling = new HashMap<>();

    public DragonListener(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------- 체력 변경 ----------------

    @EventHandler
    public void onDragonSpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)) return;

        double health = plugin.getConfig().getDouble("dragon.health", 200.0);
        var attr = dragon.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) {
            attr.setBaseValue(health);
            dragon.setHealth(health);
        }
    }

    // ---------------- 드래곤알 고정위치 스폰 (텔레포트 능력 제거) ----------------

    @EventHandler
    public void onDragonDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon)) return;
        if (!plugin.getConfig().getBoolean("dragon.egg.relocate-on-death", true)) return;

        // 바닐라가 알을 생성할 시간을 준 뒤(지연) 찾아서 이동시킨다.
        Bukkit.getScheduler().runTaskLater(plugin, () -> relocateDragonEgg(event.getEntity().getWorld()), 40L);
    }

    private void relocateDragonEgg(org.bukkit.World world) {
        // End 메인 섬 출구 포탈 주변(대략 0,0 근방)에서 드래곤알을 찾아 제거 후 지정 위치에 재생성
        int searchRadius = 16;
        Block found = null;
        for (int x = -searchRadius; x <= searchRadius && found == null; x++) {
            for (int z = -searchRadius; z <= searchRadius && found == null; z++) {
                for (int y = 60; y <= 90; y++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (b.getType() == Material.DRAGON_EGG) {
                        found = b;
                        break;
                    }
                }
            }
        }

        if (found != null) {
            found.setType(Material.AIR);
        }

        double x = plugin.getConfig().getDouble("dragon.egg.fixed-x", 0);
        double y = plugin.getConfig().getDouble("dragon.egg.fixed-y", 65);
        double z = plugin.getConfig().getDouble("dragon.egg.fixed-z", 0);

        world.getBlockAt((int) x, (int) y, (int) z).setType(Material.DRAGON_EGG);
        Bukkit.broadcastMessage("§5§l[드래곤알] §e지정된 위치에 드래곤알이 생성되었습니다.");
    }

    // ---------------- 텔레포트 무력화 (진짜 원인이 되는 이벤트) ----------------

    /**
     * 드래곤알의 순간이동은 실제로는 BlockFromToEvent로 처리된다
     * ("물/용암 흐름"과 "드래곤알 텔레포트"에만 쓰이는 전용 이벤트).
     * 이걸 취소하면 좌클릭이든 우클릭이든 어떤 방식으로 트리거되든 100% 막힌다.
     */
    @EventHandler
    public void onDragonEggMove(org.bukkit.event.block.BlockFromToEvent event) {
        if (event.getBlock().getType() != Material.DRAGON_EGG) return;
        event.setCancelled(true);
    }

    // ---------------- 획득 방식 변경 (우클릭 채널링) ----------------

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return;
        if (event.getClickedBlock().getType() != Material.DRAGON_EGG) return;

        // 좌클릭(공격 시작)은 어차피 위 BlockFromToEvent에서 막히지만, 안내 메시지를 위해 여기서도 취소
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
            return;
        }

        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        // 바닐라 텔레포트 동작 자체를 취소 (항상)
        event.setCancelled(true);

        Player player = event.getPlayer();

        if (plugin.getLifeStealManager().isCombatTagged(player.getUniqueId())) {
            player.sendMessage("§c전투 중에는 드래곤알을 획득할 수 없습니다.");
            return;
        }

        if (channeling.containsKey(player.getUniqueId())) {
            return; // 이미 채널링 중
        }

        Block eggBlock = event.getClickedBlock();
        Location eggLoc = eggBlock.getLocation();
        int requiredSeconds = plugin.getConfig().getInt("dragon.egg.acquire-hold-seconds", 30);

        player.sendMessage("§5드래곤알을 획득하는 중입니다... " + requiredSeconds + "초간 가만히 있으세요.");

        final int[] elapsed = {0};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()
                    || player.getLocation().distance(eggLoc) > 4
                    || eggBlock.getType() != Material.DRAGON_EGG
                    || plugin.getLifeStealManager().isCombatTagged(player.getUniqueId())) {
                cancelChanneling(player, "§c드래곤알 획득이 중단되었습니다.");
                return;
            }

            elapsed[0]++;
            if (elapsed[0] >= requiredSeconds) {
                eggBlock.setType(Material.AIR);
                player.getInventory().addItem(new ItemStack(Material.DRAGON_EGG));
                player.sendMessage("§5§l드래곤알을 획득했습니다!");
                cancelChanneling(player, null);
            }
        }, 20L, 20L);

        channeling.put(player.getUniqueId(), task);
    }

    @EventHandler
    public void onBreak(org.bukkit.event.block.BlockBreakEvent event) {
        if (event.getBlock().getType() != Material.DRAGON_EGG) return;
        // 파괴를 통한 우회 획득도 막고, 오직 우클릭 채널링 방식으로만 획득 가능하게 함
        event.setCancelled(true);
    }

    private void cancelChanneling(Player player, String message) {
        BukkitTask task = channeling.remove(player.getUniqueId());
        if (task != null) task.cancel();
        if (message != null) player.sendMessage(message);
    }
}
