package com.deruy.plugin.events.commands;

import com.deruy.plugin.DeruyPlugin;
import com.deruy.plugin.events.koth.KothManager;
import com.deruy.plugin.events.koth.KothZone;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * /koth on|off                    - 기능 자체 활성/비활성 (마스터 스위치)
 * /koth setregion <id> 1|2        - id로 구역 지정 (여러 id로 원하는 개수만큼: 1,2,3...7 등)
 * /koth removezone <id>           - 특정 구역 삭제
 * /koth zones                     - 설정된 구역 목록 확인 + 테두리를 빨간 유리로 잠깐 시각화
 * /koth start                     - 라운드 시작 (등록된 모든 구역이 동시에 활성화됨)
 * /koth stop                      - 라운드 강제 종료
 * /koth status                    - 현재 상태 확인
 * /koth time <초>                  - 점령 필요 시간 설정 (모든 구역 공통)
 *
 * 특정 KothManager 인스턴스를 주입받아 동작하므로, /koth와 /superkoth 둘 다
 * 이 클래스를 그대로 재사용해서 완전히 독립적으로 관리할 수 있다.
 */
public class KothCommand implements CommandExecutor, TabCompleter {

    private final DeruyPlugin plugin;
    private final KothManager koth;

    public KothCommand(DeruyPlugin plugin, KothManager koth) {
        this.plugin = plugin;
        this.koth = koth;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmdName = "/" + koth.getNamespace();

        if (args.length == 0) {
            sender.sendMessage("§e" + cmdName + " on|off|start [id...]|stop [id...]|status|zones|setregion <id> 1|2|removezone <id>|time <초>");
            sender.sendMessage("§7예: " + cmdName + " start 1        → 1번 구역만 시작");
            sender.sendMessage("§7예: " + cmdName + " start 2 3      → 2,3번 구역만 시작");
            sender.sendMessage("§7예: " + cmdName + " start          → 등록된 전체 구역 시작");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "on" -> {
                koth.setEnabled(true);
                sender.sendMessage("§a" + koth.getLabel() + " 기능이 활성화되었습니다.");
                return true;
            }
            case "off" -> {
                koth.setEnabled(false);
                sender.sendMessage("§c" + koth.getLabel() + " 기능이 비활성화되었습니다. (진행 중이던 라운드도 종료됨)");
                return true;
            }
            case "status" -> {
                sender.sendMessage("§6=== " + koth.getLabel() + " 상태 ===");
                sender.sendMessage("§e기능 활성화: " + (koth.isEnabled() ? "§aON" : "§cOFF"));
                sender.sendMessage("§e라운드 진행중: " + (koth.isRunning() ? "§a예" : "§7아니오"));
                if (koth.isRunning()) {
                    sender.sendMessage("§e활성 구역: " + String.join(", ", koth.getActiveZoneIds()));
                }
                sender.sendMessage("§e점령 필요시간: " + koth.getRequiredSeconds() + "초");
                sender.sendMessage("§e설정된 구역 수: " + koth.getZones().size());
                return true;
            }
            case "zones" -> {
                if (koth.getZones().isEmpty()) {
                    sender.sendMessage("§7설정된 구역이 없습니다.");
                    return true;
                }
                sender.sendMessage("§6=== " + koth.getLabel() + " 구역 목록 ===");
                koth.getZones().forEach((id, zone) -> {
                    String state = !zone.isComplete() ? "§c미완성" : (koth.getActiveZoneIds().contains(id) ? "§a활성중" : "§7대기중");
                    sender.sendMessage("§e- " + id + ": " + state);
                });

                if (sender instanceof Player p) {
                    showZonesVisual(p, koth);
                }
                return true;
            }
            case "setregion" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("§c이 명령어는 인게임에서만 사용 가능합니다.");
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage("§c사용법: " + cmdName + " setregion <id> 1|2");
                    return true;
                }
                String id = args[1];
                KothZone zone = koth.getOrCreateZone(id);

                if (args[2].equals("1")) {
                    zone.setCorner1(p.getLocation());
                    koth.saveZoneCorner(id, p.getLocation(), null);
                    sender.sendMessage("§a구역 '" + id + "' 1번 코너가 저장되었습니다.");
                } else if (args[2].equals("2")) {
                    zone.setCorner2(p.getLocation());
                    koth.saveZoneCorner(id, null, p.getLocation());
                    sender.sendMessage(zone.isComplete()
                            ? "§a구역 '" + id + "' 설정이 완료되었습니다."
                            : "§e구역 '" + id + "' 2번 코너가 저장되었습니다. (1번 코너를 먼저 지정하세요)");
                } else {
                    sender.sendMessage("§c마지막 인자는 1 또는 2여야 합니다.");
                }
                return true;
            }
            case "removezone" -> {
                if (args.length < 2) {
                    sender.sendMessage("§c사용법: " + cmdName + " removezone <id>");
                    return true;
                }
                boolean removed = koth.removeZone(args[1]);
                sender.sendMessage(removed
                        ? "§a구역 '" + args[1] + "'을(를) 삭제했습니다."
                        : "§c해당 id의 구역이 없습니다.");
                return true;
            }
            case "time" -> {
                if (args.length < 2) {
                    sender.sendMessage("§c사용법: " + cmdName + " time <초>");
                    return true;
                }
                try {
                    int seconds = Integer.parseInt(args[1]);
                    koth.setRequiredSeconds(seconds);
                    sender.sendMessage("§a점령 필요 시간이 " + seconds + "초로 설정되었습니다. (모든 구역 공통 적용)");
                } catch (NumberFormatException e) {
                    sender.sendMessage("§c숫자를 입력해주세요.");
                }
                return true;
            }
            case "start" -> {
                if (args.length >= 2) {
                    String[] ids = Arrays.copyOfRange(args, 1, args.length);
                    koth.start(ids);
                } else {
                    koth.start();
                }
                return true;
            }
            case "stop" -> {
                if (args.length >= 2) {
                    String[] ids = Arrays.copyOfRange(args, 1, args.length);
                    koth.stop(ids);
                } else {
                    koth.stop();
                }
                return true;
            }
            default -> {
                sender.sendMessage("§c알 수 없는 하위 명령어입니다.");
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();

        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0],
                    List.of("on", "off", "start", "stop", "status", "zones", "setregion", "removezone", "time"), result);
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("setregion") || args[0].equalsIgnoreCase("removezone"))) {
            StringUtil.copyPartialMatches(args[1], koth.getZones().keySet(), result);
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("start") || args[0].equalsIgnoreCase("stop"))) {
            StringUtil.copyPartialMatches(args[1], koth.getZones().keySet(), result);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("setregion")) {
            StringUtil.copyPartialMatches(args[2], List.of("1", "2"), result);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("time")) {
            StringUtil.copyPartialMatches(args[1], List.of("30", "60", "90", "120"), result);
        }
        return result;
    }

    // ---------------- 구역 시각화 ----------------

    private void showZonesVisual(Player player, KothManager koth) {
        Map<Location, BlockData> originals = new HashMap<>();

        for (KothZone zone : koth.getZones().values()) {
            if (!zone.isComplete()) continue;
            if (!zone.getCorner1().getWorld().equals(player.getWorld())) continue;

            for (Location loc : edgeBlocksOf(zone.getCorner1(), zone.getCorner2())) {
                var block = loc.getBlock();
                if (block.getType().isSolid()) continue;

                originals.put(loc, block.getBlockData());
                player.sendBlockChange(loc, Material.RED_STAINED_GLASS.createBlockData());
            }
        }

        if (originals.isEmpty()) return;

        int durationSeconds = plugin.getConfig().getInt(koth.getNamespace() + ".zones-visualize-duration-seconds", 15);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (var entry : originals.entrySet()) {
                player.sendBlockChange(entry.getKey(), entry.getValue());
            }
        }, durationSeconds * 20L);
    }

    private List<Location> edgeBlocksOf(Location corner1, Location corner2) {
        List<Location> result = new ArrayList<>();
        World world = corner1.getWorld();

        int minX = Math.min(corner1.getBlockX(), corner2.getBlockX());
        int maxX = Math.max(corner1.getBlockX(), corner2.getBlockX());
        int minY = Math.min(corner1.getBlockY(), corner2.getBlockY());
        int maxY = Math.max(corner1.getBlockY(), corner2.getBlockY());
        int minZ = Math.min(corner1.getBlockZ(), corner2.getBlockZ());
        int maxZ = Math.max(corner1.getBlockZ(), corner2.getBlockZ());

        for (int x = minX; x <= maxX; x++) {
            result.add(new Location(world, x, minY, minZ));
            result.add(new Location(world, x, minY, maxZ));
            result.add(new Location(world, x, maxY, minZ));
            result.add(new Location(world, x, maxY, maxZ));
        }
        for (int y = minY; y <= maxY; y++) {
            result.add(new Location(world, minX, y, minZ));
            result.add(new Location(world, minX, y, maxZ));
            result.add(new Location(world, maxX, y, minZ));
            result.add(new Location(world, maxX, y, maxZ));
        }
        for (int z = minZ; z <= maxZ; z++) {
            result.add(new Location(world, minX, minY, z));
            result.add(new Location(world, minX, maxY, z));
            result.add(new Location(world, maxX, minY, z));
            result.add(new Location(world, maxX, maxY, z));
        }

        return result;
    }
}
