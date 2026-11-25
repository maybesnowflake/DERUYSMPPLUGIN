package com.deruy.plugin.replay;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * /camera <이벤트> <이름> [반경] — 지금 서 있는 위치·바라보는 방향을 그 이벤트용 고정 카메라로 저장
 * /camera list | remove <이름> | tp <이름> | test <이름> [초]
 * 이벤트: koth, transport, randomevent, supply, dragon, always(항상 녹화)
 */
public final class CameraCommand implements TabExecutor {
    private final DeruyPlugin plugin;
    public CameraCommand(DeruyPlugin plugin) { this.plugin = plugin; }

    @Override public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("deruy.admin")) { s.sendMessage("§c권한이 없습니다."); return true; }
        ReplayService rs = plugin.getReplayService();
        if (rs == null) { s.sendMessage("§c서버 녹화가 꺼져 있습니다. §7(config.yml replay.enabled)"); return true; }
        if (a.length == 0) { help(s, label); return true; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> {
                var defs = rs.cameraDefs();
                s.sendMessage("§d[카메라] §f" + defs.size() + "대");
                for (var d : defs) s.sendMessage("§7- §f" + d.name() + " §7[" + d.event() + "] §f" + d.at().getWorld().getName()
                        + String.format(" %.1f %.1f %.1f", d.at().getX(), d.at().getY(), d.at().getZ()) + " §7반경 " + (int) d.radius());
            }
            case "remove" -> {
                if (a.length < 2) { s.sendMessage("§e/" + label + " remove <이름>"); return true; }
                s.sendMessage(rs.removeCamera(a[1]) ? "§a카메라 " + a[1] + " 삭제" : "§c없는 카메라입니다.");
            }
            case "tp" -> {
                if (!(s instanceof Player p) || a.length < 2) { s.sendMessage("§e/" + label + " tp <이름> §7(게임 안에서)"); return true; }
                var d = rs.cameraDefs().stream().filter(x -> x.name().equals(a[1])).findFirst();
                if (d.isEmpty()) { s.sendMessage("§c없는 카메라입니다."); return true; }
                p.teleport(d.get().at());
                s.sendMessage("§a카메라 " + a[1] + " 자리로 이동 (이 화면이 녹화 첫 화면)");
            }
            case "test" -> {
                if (a.length < 2) { s.sendMessage("§e/" + label + " test <이름> [초]"); return true; }
                int sec = 20;
                try { if (a.length >= 3) sec = Math.max(5, Math.min(600, Integer.parseInt(a[2]))); } catch (NumberFormatException e) { s.sendMessage("§c초는 숫자"); return true; }
                s.sendMessage(rs.testCamera(a[1], sec) ? "§a카메라 " + a[1] + " 시험 녹화 " + sec + "초 시작 → 끝나면 저장 알림" : "§c없는 카메라이거나 시작하지 못했습니다.");
            }
            default -> {
                if (!ReplayService.CAMERA_EVENTS.contains(sub)) { help(s, label); return true; }
                if (!(s instanceof Player p)) { s.sendMessage("§c게임 안에서 원하는 자리·방향을 보고 입력하세요."); return true; }
                if (a.length < 2 || !a[1].matches("[0-9A-Za-z_-]{1,24}")) { s.sendMessage("§c이름은 영문·숫자·_·- 24자 이내"); return true; }
                double radius = 32;
                try { if (a.length >= 3) radius = Math.max(8, Math.min(128, Double.parseDouble(a[2]))); } catch (NumberFormatException e) { s.sendMessage("§c반경은 숫자"); return true; }
                rs.addCamera(a[1], sub, p.getEyeLocation(), radius);
                s.sendMessage("§a카메라 §f" + a[1] + " §a저장: §f" + sub + (sub.equals("always") ? " §7(항상 녹화)" : " §7이벤트 때 자동 녹화") + " §7반경 " + (int) radius
                        + " §7— 지금 보는 방향이 첫 화면. 시험: /" + label + " test " + a[1]);
            }
        }
        return true;
    }

    private static void help(CommandSender s, String label) {
        s.sendMessage("§e/" + label + " <이벤트> <이름> [반경] §7— 지금 자리·방향을 카메라로 (" + String.join(", ", ReplayService.CAMERA_EVENTS) + ")");
        s.sendMessage("§e/" + label + " list | remove <이름> | tp <이름> | test <이름> [초]");
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (!s.hasPermission("deruy.admin")) return List.of();
        ReplayService rs = plugin.getReplayService();
        List<String> names = rs == null ? List.of() : rs.cameraDefs().stream().map(ReplayService.CameraDef::name).toList();
        if (a.length == 1) {
            List<String> opts = new ArrayList<>(List.of("list", "remove", "tp", "test"));
            opts.addAll(ReplayService.CAMERA_EVENTS);
            return opts.stream().filter(x -> x.startsWith(a[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (a.length == 2 && List.of("remove", "tp", "test").contains(a[0].toLowerCase(Locale.ROOT)))
            return names.stream().filter(x -> x.startsWith(a[1])).toList();
        if (a.length == 3 && ReplayService.CAMERA_EVENTS.contains(a[0].toLowerCase(Locale.ROOT))) return List.of("32", "48", "64");
        return List.of();
    }
}
