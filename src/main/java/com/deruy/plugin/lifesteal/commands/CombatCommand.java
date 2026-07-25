package com.deruy.plugin.lifesteal.commands;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * /combat <player> <초> - 지정 플레이어에게 수동으로 컴벳태그 부여 (테스트/관리자용)
 */
public class CombatCommand implements CommandExecutor, TabCompleter {

    private final DeruyPlugin plugin;

    public CombatCommand(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c사용법: /combat <player> <초>");
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage("§c플레이어를 찾을 수 없습니다: " + args[0]);
            return true;
        }

        int seconds;
        try {
            seconds = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§c초는 숫자로 입력해주세요.");
            return true;
        }

        plugin.getLifeStealManager().tagCombat(target.getUniqueId(), seconds * 1000L);
        sender.sendMessage("§a" + target.getName() + "님에게 " + seconds + "초간 컴벳태그를 부여했습니다.");
        target.sendMessage("§c컴벳태그가 부여되었습니다. (" + seconds + "초)");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0],
                    Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()), result);
        } else if (args.length == 2) {
            StringUtil.copyPartialMatches(args[1], List.of("10", "15", "30", "60"), result);
        }
        return result;
    }
}
