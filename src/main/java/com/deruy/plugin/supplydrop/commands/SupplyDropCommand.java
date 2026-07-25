package com.deruy.plugin.supplydrop.commands;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * /supplydrop start|stop|force|setregion 1|2        - 일반 서플라이드랍 관리
 * /supersupplydrop start|stop|force|setregion 1|2   - 슈퍼 서플라이드랍 관리
 *
 * setregion으로 두 코너(현재 서있는 위치, 월드 포함)를 지정하면 그 구역 안에서만
 * 상자가 스폰된다. 지정 안 하면 config의 center-x/z + radius 방식으로 폴백.
 */
public class SupplyDropCommand implements CommandExecutor, TabCompleter {

    private final DeruyPlugin plugin;
    private final boolean superMode;
    private final String kind;

    public SupplyDropCommand(DeruyPlugin plugin, boolean superMode) {
        this.plugin = plugin;
        this.superMode = superMode;
        this.kind = superMode ? "super" : "normal";
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§e사용법: /" + label + " start|stop|force|setregion 1|2");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "start" -> {
                if (superMode) plugin.getSuperSupplyDropManager().start();
                else plugin.getSupplyDropManager().start();
                return true;
            }
            case "stop" -> {
                if (superMode) plugin.getSuperSupplyDropManager().stop();
                else plugin.getSupplyDropManager().stop();
                return true;
            }
            case "force" -> {
                if (superMode) plugin.getSuperSupplyDropManager().triggerDrop();
                else plugin.getSupplyDropManager().triggerDrop();
                sender.sendMessage("§a강제로 드랍을 실행했습니다.");
                return true;
            }
            case "setregion" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("§c이 명령어는 인게임에서만 사용 가능합니다.");
                    return true;
                }
                if (args.length < 2 || (!args[1].equals("1") && !args[1].equals("2"))) {
                    sender.sendMessage("§c사용법: /" + label + " setregion 1|2");
                    return true;
                }
                if (args[1].equals("1")) {
                    plugin.getDataStore().saveSupplyRegion(kind, p.getLocation(), null);
                    sender.sendMessage("§a1번 코너가 저장되었습니다. (월드: " + p.getWorld().getName() + ")");
                } else {
                    plugin.getDataStore().saveSupplyRegion(kind, null, p.getLocation());
                    sender.sendMessage("§a2번 코너가 저장되었습니다. (월드: " + p.getWorld().getName() + ")");
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
            StringUtil.copyPartialMatches(args[0], List.of("start", "stop", "force", "setregion"), result);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("setregion")) {
            StringUtil.copyPartialMatches(args[1], List.of("1", "2"), result);
        }
        return result;
    }
}
