package com.deruy.plugin.roleeffect;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * config에 등록된 "시간 + 역할 + 이펙트" 스케줄을 주기적으로 체크해서,
 * 해당 시각이 되면 그 역할(LuckPerms 그룹)을 가진 온라인 플레이어 전원에게
 * 지정된 포션이펙트를 자동으로 부여한다.
 *
 * config 예시:
 * role-effects:
 *   schedule:
 *     - time: "09:00"
 *       role: "gwn"
 *       effect: "RESISTANCE"
 *       amplifier: 0
 *       duration-seconds: 86400
 *     - time: "09:00"
 *       role: "gwn"
 *       effect: "LUCK"
 *       amplifier: 0
 *       duration-seconds: 86400
 */
public class RoleEffectScheduler {

    private final DeruyPlugin plugin;
    private BukkitTask task;

    private final Set<String> triggeredToday = new HashSet<>();
    private int lastResetDay = -1;
    /** 직전 확인 시각. 서버가 렉으로 1분 넘게 멈춰도 그 사이 지난 시각을 놓치지 않게 한다. */
    private LocalTime lastChecked;

    public RoleEffectScheduler(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::check, 20L, 20L * 20); // 20초마다 체크
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** 현재 서버 실제(현실) 시각 문자열. /deruytime 커맨드 등에서 사용. */
    public static String currentRealTime() {
        return java.time.LocalDateTime.now().toString();
    }

    private void check() {
        int dayValue = java.time.LocalDate.now().getDayOfYear();
        if (dayValue != lastResetDay) {
            triggeredToday.clear();
            if (lastResetDay != -1) lastChecked = LocalTime.MIDNIGHT; // 날이 바뀌면 00:00부터 다시 본다
            lastResetDay = dayValue;
        }
        LocalTime now = LocalTime.now();
        LocalTime previous = lastChecked;
        lastChecked = now;

        List<?> rawList = plugin.getConfig().getList("role-effects.schedule");
        if (rawList == null) return;

        for (int i = 0; i < rawList.size(); i++) {
            Object raw = rawList.get(i);
            if (!(raw instanceof Map)) continue;
            // 리로드로 순서가 바뀌어도 같은 항목을 두 번 주지 않도록 내용으로 구분한다.
            String key = raw.toString();
            if (triggeredToday.contains(key)) continue;

            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) raw;

            String timeStr = String.valueOf(entry.get("time"));
            String role = String.valueOf(entry.get("role"));
            String effectName = String.valueOf(entry.get("effect"));
            int amplifier = entry.get("amplifier") instanceof Number n ? n.intValue() : 0;
            int durationSeconds = entry.get("duration-seconds") instanceof Number n ? n.intValue() : 60;

            try {
                LocalTime target = LocalTime.parse(timeStr);
                if (due(target, previous, now)) {
                    applyEffect(role, effectName, amplifier, durationSeconds);
                    triggeredToday.add(key);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("role-effects.schedule 항목 형식이 잘못되었습니다: " + entry);
            }
        }
    }

    /**
     * 처음 확인할 때(서버 시작 직후)는 그 분이 맞을 때만, 이후에는 직전 확인과 지금 사이에
     * 목표 시각이 지났으면 실행한다. 재시작으로 이미 지난 시각을 한꺼번에 몰아 주지는 않는다.
     */
    static boolean due(LocalTime target, LocalTime previous, LocalTime now) {
        LocalTime minute = target.withSecond(0).withNano(0);
        if (previous == null || previous.isAfter(now))
            return now.getHour() == minute.getHour() && now.getMinute() == minute.getMinute();
        return minute.isAfter(previous) && !minute.isAfter(now)
                || now.getHour() == minute.getHour() && now.getMinute() == minute.getMinute();
    }

    private void applyEffect(String role, String effectName, int amplifier, int durationSeconds) {
        PotionEffectType type = PotionEffectType.getByName(effectName.toUpperCase());
        if (type == null) {
            plugin.getLogger().warning("존재하지 않는 포션이펙트: " + effectName);
            return;
        }

        int applied = 0;
        for (Player player : com.deruy.plugin.misc.Online.players()) {
            if (!plugin.hasRole(player, role)) continue;
            player.addPotionEffect(new PotionEffect(type, durationSeconds * 20, amplifier));
            player.sendMessage("§d[역할이펙트] §e" + effectName + " 효과를 받았습니다.");
            applied++;
        }

        if (applied > 0) {
            plugin.getLogger().info("역할 '" + role + "'에게 " + effectName + " 이펙트 부여 (" + applied + "명)");
        }
    }
}
