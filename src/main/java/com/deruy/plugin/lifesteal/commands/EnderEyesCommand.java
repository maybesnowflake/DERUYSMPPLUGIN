package com.deruy.plugin.lifesteal.commands;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * /endereyes on|off - 엔더의 눈 던지기 금지 여부를 서버 재시작 없이 즉시 전환.
 * on = 던지기 차단(금지), off = 던지기 허용
 */
public class EnderEyesCommand implements CommandExecutor, TabCompleter {

    private final DeruyPlugin plugin;

    public EnderEyesCommand(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1 || (!args[0].equalsIgnoreCase("on") && !args[0].equalsIgnoreCase("off"))) {
            sender.sendMessage("§c사용법: /endereyes on|off");
            return true;
        }

        boolean blocked = args[0].equalsIgnoreCase("on");
        plugin.getConfig().set("lifesteal.restrictions.ender-eyes-blocked", blocked);
        plugin.saveConfig();

        Bukkit.broadcastMessage("§6[엔더의 눈] §e던지기가 " + (blocked ? "§c차단" : "§a허용") + "§e되었습니다.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0], List.of("on", "off"), result);
        }
        return result;
    }
}
