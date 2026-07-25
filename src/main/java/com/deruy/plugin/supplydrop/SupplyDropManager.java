package com.deruy.plugin.supplydrop;

import com.deruy.plugin.DeruyPlugin;
import com.deruy.plugin.events.GameEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.time.LocalTime;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 일반 서플라이드랍: 지정 시간대(윈도우) 안에서 완전 랜덤한 시각에 자동 실행되며,
 * 트리거되면 한번에 상자 하나가 아니라 config에 설정된 시간(기본 15분) 동안
 * config에 설정된 개수(기본 20~30개)의 상자가 순차적으로 흩뿌려진다.
 *
 * config: supplydrop.normal.*
 *   window-start-hour / window-end-hour : 하루 중 랜덤 발동 시간대
 *   duration-minutes                    : 상자가 다 떨어지는데 걸리는 시간
 *   count-min / count-max                : 이번 드랍에서 몇 개가 떨어질지 (랜덤범위)
 */
public class SupplyDropManager implements GameEvent {

    private final DeruyPlugin plugin;
    private boolean running = false;
    private BukkitTask checkTask;

    private LocalTime todayTarget;
    private int lastTriggeredDay = -1;

    public SupplyDropManager(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "supplydrop";
    }

    @Override
    public void start() {
        if (running) return;
        running = true;
        rollTodayTarget();
        checkTask = Bukkit.getScheduler().runTaskTimer(plugin, this::check, 0L, 20L * 30);
        Bukkit.broadcastMessage("§6§l서플라이드랍 §e시스템이 활성화되었습니다. 언제 떨어질지는 아무도 모릅니다.");
    }

    @Override
    public void stop() {
        if (!running) return;
        running = false;
        if (checkTask != null) {
            checkTask.cancel();
            checkTask = null;
        }
        todayTarget = null;
        Bukkit.broadcastMessage("§6§l서플라이드랍 §e시스템이 비활성화되었습니다.");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void rollTodayTarget() {
        int startHour = plugin.getConfig().getInt("supplydrop.normal.window-start-hour", 15);
        int endHour = plugin.getConfig().getInt("supplydrop.normal.window-end-hour", 24);

        Random random = ThreadLocalRandom.current();
        int hour = startHour + random.nextInt(Math.max(1, endHour - startHour));
        int minute = random.nextInt(60);
        if (hour >= 24) hour = 23;

        this.todayTarget = LocalTime.of(hour, minute);
        // 절대 로그로 남기지 않음 (관리자도 모르게)
    }

    private void check() {
        java.time.LocalDate today = java.time.LocalDate.now();
        int dayValue = today.getDayOfYear();

        if (dayValue != lastTriggeredDay && todayTarget != null) {
            LocalTime now = LocalTime.now();
            if (!now.isBefore(todayTarget)) {
                triggerDrop();
                lastTriggeredDay = dayValue;
                rollTodayTarget();
            }
        }
    }

    /**
     * 서플라이드랍을 즉시 시작한다. 상자 하나가 아니라, config에 설정된 시간동안
     * config에 설정된 개수만큼 순차적으로(랜덤 간격) 흩뿌려진다.
     */
    public void triggerDrop() {
        int countMin = plugin.getConfig().getInt("supplydrop.normal.count-min", 20);
        int countMax = plugin.getConfig().getInt("supplydrop.normal.count-max", 30);
        int durationMinutes = plugin.getConfig().getInt("supplydrop.normal.duration-minutes", 15);

        int count = countMin + ThreadLocalRandom.current().nextInt(Math.max(1, countMax - countMin + 1));
        long durationTicks = durationMinutes * 60L * 20L;

        Bukkit.broadcastMessage("§6§l[서플라이드랍] §e보급 상자들이 앞으로 " + durationMinutes + "분간 흩뿌려집니다! (총 " + count + "개)");

        for (int i = 0; i < count; i++) {
            long delay = ThreadLocalRandom.current().nextLong(0, Math.max(1, durationTicks));
            Bukkit.getScheduler().runTaskLater(plugin, this::spawnSingleChest, delay);
        }
    }

    private void spawnSingleChest() {
        Location[] region = plugin.getDataStore().loadSupplyRegion("normal");
        Location loc;

        if (region != null) {
            loc = randomLocationInRegion(region[0], region[1]);
        } else {
            World world = Bukkit.getWorlds().get(0);
            double centerX = plugin.getConfig().getDouble("supplydrop.normal.center-x", 0);
            double centerZ = plugin.getConfig().getDouble("supplydrop.normal.center-z", 0);
            int radius = plugin.getConfig().getInt("supplydrop.normal.radius", 200);

            Random random = ThreadLocalRandom.current();
            double x = centerX + (random.nextDouble() * 2 - 1) * radius;
            double z = centerZ + (random.nextDouble() * 2 - 1) * radius;
            int y = world.getHighestBlockYAt((int) x, (int) z) + 1;
            loc = new Location(world, x, y, z);
        }

        Block block = loc.getBlock();
        block.setType(Material.CHEST);

        List<String> rewardEntries = plugin.getConfig().getStringList("supplydrop.normal.reward-items");
        List<ItemStack> rolled = plugin.getSupplyChestRegistry().rollRewards(rewardEntries, plugin.getLogger());
        plugin.getSupplyChestRegistry().register(block.getLocation(), rolled);
    }

    /** 구역(두 코너) 안에서 랜덤 좌표를 고르고, 그 x,z의 지형 최고높이+1을 y로 사용 */
    static Location randomLocationInRegion(Location corner1, Location corner2) {
        World world = corner1.getWorld();
        double minX = Math.min(corner1.getX(), corner2.getX());
        double maxX = Math.max(corner1.getX(), corner2.getX());
        double minZ = Math.min(corner1.getZ(), corner2.getZ());
        double maxZ = Math.max(corner1.getZ(), corner2.getZ());

        Random random = ThreadLocalRandom.current();
        double x = minX + random.nextDouble() * (maxX - minX);
        double z = minZ + random.nextDouble() * (maxZ - minZ);
        int y = world.getHighestBlockYAt((int) x, (int) z) + 1;

        return new Location(world, x, y, z);
    }
}
