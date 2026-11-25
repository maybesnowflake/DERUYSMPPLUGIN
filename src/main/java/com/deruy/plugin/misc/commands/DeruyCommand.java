package com.deruy.plugin.misc.commands;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.*;

import java.time.LocalDate;
import java.util.*;

/** /deruy status — 모든 시스템 상태 한눈에 / /deruy timeline [날짜] — 영상용 기록 파일 */
public final class DeruyCommand implements TabExecutor {
    private final DeruyPlugin plugin;
    public DeruyCommand(DeruyPlugin plugin) { this.plugin = plugin; }

    private static String on(boolean b) { return b ? "§a켜짐" : "§7꺼짐"; }

    @Override public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("deruy.admin")) { s.sendMessage("§c권한이 없습니다."); return true; }
        String sub = a.length == 0 ? "status" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(s).forEach(s::sendMessage);
            case "timeline" -> timeline(s, a);
            case "replay" -> replay(s, a);
            default -> s.sendMessage("§e/" + label + " status | timeline [YYYY-MM-DD] | replay [status|list|save <플레이어>|verify <파일>]");
        }
        return true;
    }

    List<String> status(CommandSender s) {
        List<String> out = new ArrayList<>();
        out.add("§6§l── DERUY 상태 ──");
        out.add("§e라이프스틸 " + on(plugin.getLifeStealManager().isSystemEnabled()));
        for (var k : List.of(plugin.getKothManager(), plugin.getSuperKothManager())) {
            String zones = k.isRunning() ? " §f(" + String.join(", ", k.getActiveZoneIds()) + ")" : "";
            out.add("§e" + k.getName() + " " + on(k.isEnabled()) + " §7/ 진행 " + on(k.isRunning()) + zones);
        }
        var b = plugin.getBountyManager();
        var target = b.getGlobalTarget() == null ? null : Bukkit.getOfflinePlayer(b.getGlobalTarget()).getName();
        out.add("§e바운티 " + on(b.isRunning()) + " §7/ 페어 " + b.pairCount() + "쌍"
                + (target != null ? " §7/ 전체 타겟 §f" + target : "")
                + (b.getDesignatedPursuer() != null ? " §7/ 지정 §f" + b.getDesignatedPursuer() + " → " + b.getDesignatedTarget() : ""));
        out.add("§e서플라이드랍 " + on(plugin.getSupplyDropManager().isRunning()) + " §7/ 떨어질 상자 " + plugin.getSupplyDropManager().getPendingDropCount()
                + "  §e슈퍼 " + on(plugin.getSuperSupplyDropManager().isRunning()) + " §7/ " + plugin.getSuperSupplyDropManager().getPendingDropCount());
        var r = plugin.getRandomEventManager();
        out.add("§e랜덤 이벤트 예약 " + on(r.isSchedulerEnabled()) + " §7/ " + (r.isDrawing() ? "§6추첨 중" : r.getActive() == null ? "§7진행 없음" : "§c" + r.getActive()));
        var t = plugin.getTransportManager();
        out.add("§e하트 운송전 §f" + (t == null ? "§c설정 오류" : t.status()));
        out.add("§e질문타임 " + on(plugin.getQuestionTimeManager().isEnabled()) + (plugin.getQuestionTimeManager().isQuestionActive() ? " §6(문제 진행 중)" : ""));
        out.add("§e빙고 " + on(plugin.getBingoManager().isRunning()) + "  §e로케이터바 " + on(plugin.getLocatorBarManager().isRunning()));
        out.add("§e타임라인 기록 " + on(plugin.getConfig().getBoolean("timeline.enabled", true)));
        var rs = plugin.getReplayService();
        out.add("§e서버 녹화 " + on(rs != null) + (rs == null ? "" : " §7/ 버퍼 " + rs.memoryBytes() / 1024 / 1024 + "MB"));
        return out;
    }

    private void timeline(CommandSender s, String[] a) {
        var tl = plugin.getTimeline();
        if (tl == null) { s.sendMessage("§c타임라인이 준비되지 않았습니다."); return; }
        LocalDate day;
        try { day = a.length >= 2 ? LocalDate.parse(a[1]) : LocalDate.now(); }
        catch (Exception e) { s.sendMessage("§c날짜 형식: YYYY-MM-DD"); return; }
        tl.flush();
        long n = tl.count(day);
        s.sendMessage("§6[타임라인] §f" + day + " §7기록 §f" + Math.max(0, n) + "건");
        s.sendMessage("§7파일: §f" + plugin.getDataFolder().toPath().relativize(tl.fileFor(day).toAbsolutePath()).toString().replace('\\', '/')
                + " §7(plugins/" + plugin.getName() + "/ 안)");
    }

    private void replay(CommandSender s, String[] a) {
        var rs = plugin.getReplayService();
        if (rs == null) { s.sendMessage("§c서버 녹화가 꺼져 있습니다. §7(config.yml replay.enabled)"); return; }
        String sub = a.length >= 2 ? a[1].toLowerCase(Locale.ROOT) : "status";
        switch (sub) {
            case "status" -> rs.status().forEach(s::sendMessage);
            case "list" -> {
                var files = rs.recent(10);
                s.sendMessage("§d[서버 녹화] §f최근 " + files.size() + "개");
                files.forEach(f -> s.sendMessage("§7- §f" + f));
            }
            case "save" -> {
                var p = a.length >= 3 ? Bukkit.getPlayerExact(a[2]) : s instanceof org.bukkit.entity.Player self ? self : null;
                if (p == null) { s.sendMessage("§c플레이어를 찾을 수 없습니다."); return; }
                s.sendMessage(rs.saveNow(p, "수동") ? "§a" + p.getName() + " 최근 장면을 저장합니다. 잠시 뒤 완료 메시지가 옵니다." : "§c그 플레이어는 녹화 중이 아닙니다.");
            }
            case "verify" -> {
                if (a.length < 3) { s.sendMessage("§e/deruy replay verify <파일> §7(list 에 나온 경로)"); return; }
                String file = a[2];
                s.sendMessage("§7검사 중…");
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    List<String> msg = new ArrayList<>();
                    try {
                        var r = rs.verify(file);
                        msg.add((r.ok() ? "§a정상" : "§c문제 " + r.problems().size() + "건") + " §7— 파일 " + r.files() + "개, " + r.totalTicks() / 20 + "초, 패킷 "
                                + r.gamePackets() + "개, 청크 " + r.cachedChunks() + "개");
                        r.problems().stream().limit(5).forEach(p -> msg.add("§c- " + p));
                    } catch (Exception e) { msg.add("§c검사 실패: " + e.getMessage()); }
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        msg.forEach(s::sendMessage);
                        if (!(s instanceof org.bukkit.entity.Player)) msg.forEach(m -> plugin.getLogger().info("[녹화 검사] " + file + " " + m.replaceAll("§.", "")));
                    });
                });
            }
            default -> s.sendMessage("§e/deruy replay status | list | save [플레이어] | verify <파일>");
        }
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (!s.hasPermission("deruy.admin")) return List.of();
        if (a.length == 2 && a[0].equalsIgnoreCase("replay"))
            return List.of("status", "list", "save", "verify").stream().filter(x -> x.startsWith(a[1].toLowerCase(Locale.ROOT))).toList();
        if (a.length == 3 && a[0].equalsIgnoreCase("replay") && a[1].equalsIgnoreCase("save"))
            return Bukkit.getOnlinePlayers().stream().map(org.bukkit.entity.Player::getName).toList();
        if (a.length == 3 && a[0].equalsIgnoreCase("replay") && a[1].equalsIgnoreCase("verify") && plugin.getReplayService() != null)
            return plugin.getReplayService().recent(20);
        if (a.length == 1) return List.of("status", "timeline", "replay").stream().filter(x -> x.startsWith(a[0].toLowerCase(Locale.ROOT))).toList();
        if (a.length == 2 && a[0].equalsIgnoreCase("timeline")) return List.of(LocalDate.now().toString());
        return List.of();
    }
}
