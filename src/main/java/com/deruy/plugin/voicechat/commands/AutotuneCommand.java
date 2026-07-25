package com.deruy.plugin.voicechat.commands;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * /autotune on|off|status
 * 투명화 유저 전원에게 적용되는 오토튠(피치 다운시프트) 이펙트를
 * 서버 전체 단위로 켜고 끈다. 인게임에서 즉시 반영됨.
 */
public class AutotuneCommand implements CommandExecutor, TabCompleter {

    private final DeruyPlugin plugin;

    public AutotuneCommand(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        var settings = plugin.getVoiceFeatureSettings();

        if (args.length == 0) {
            sender.sendMessage("§e/autotune on|off|status");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "on" -> {
                settings.setAutotuneEnabled(true);
                sender.sendMessage("§a투명화 오토튠 이펙트를 켰습니다. 모든 투명화 유저에게 즉시 적용됩니다.");
            }
            case "off" -> {
                settings.setAutotuneEnabled(false);
                sender.sendMessage("§c투명화 오토튠 이펙트를 껐습니다.");
            }
            case "status" -> {
                String state = settings.isAutotuneEnabled() ? "§aON" : "§cOFF";
                sender.sendMessage("§e오토튠 이펙트 상태: " + state);
            }
            default -> sender.sendMessage("§e/autotune on|off|status");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0], List.of("on", "off", "status"), result);
        }
        return result;
    }
}
