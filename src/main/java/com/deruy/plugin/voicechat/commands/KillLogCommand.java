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
 * /killlog on|off|status
 * 투명화 상태인 플레이어가 킬을 냈을 때, 사망 메시지에 뜨는 킬러 이름을
 * 하얀색 노이즈(§k obfuscated) 문자로 표시할지 서버 전체 단위로 켜고 끈다.
 */
public class KillLogCommand implements CommandExecutor, TabCompleter {

    private final DeruyPlugin plugin;

    public KillLogCommand(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        var settings = plugin.getVoiceFeatureSettings();

        if (args.length == 0) {
            sender.sendMessage("§e/killlog on|off|status");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "on" -> {
                settings.setKillLogObfuscationEnabled(true);
                sender.sendMessage("§a투명 상태 킬로그 노이즈 표시를 켰습니다.");
            }
            case "off" -> {
                settings.setKillLogObfuscationEnabled(false);
                sender.sendMessage("§c투명 상태 킬로그 노이즈 표시를 껐습니다.");
            }
            case "status" -> {
                String state = settings.isKillLogObfuscationEnabled() ? "§aON" : "§cOFF";
                sender.sendMessage("§e킬로그 노이즈 표시 상태: " + state);
            }
            default -> sender.sendMessage("§e/killlog on|off|status");
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
