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
 * /autotune on|off|status|pitch <반음>
 * 투명화 유저 전원에게 적용되는 오토튠(피치 다운시프트) 이펙트를
 * 서버 전체 단위로 켜고 끈다. 인게임에서 즉시 반영됨.
 * pitch <반음>으로 몇 반음 낮출지도 재시작 없이 바로 조절 가능 (음수 = 다운피치, 기본 -3).
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
            sender.sendMessage("§e/autotune on|off|status|pitch <반음>");
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
                sender.sendMessage("§e오토튠 이펙트 상태: " + state + " §7(현재 " + settings.getPitchSemitones() + "반음)");
            }
            case "pitch" -> {
                if (args.length < 2) {
                    sender.sendMessage("§c사용법: /autotune pitch <반음> §7(예: -3, 음수일수록 더 낮아짐)");
                    sender.sendMessage("§7현재값: " + settings.getPitchSemitones() + "반음");
                    return true;
                }
                try {
                    double semitones = Double.parseDouble(args[1]);
                    settings.setPitchSemitones(semitones);
                    sender.sendMessage("§a오토튠 피치를 " + semitones + "반음으로 설정했습니다. (다음 발화부터 즉시 적용)");
                } catch (IllegalArgumentException e) {
                    sender.sendMessage("§c-12~12 사이 숫자를 입력해주세요. (예: -3, -6, -1.5)");
                }
            }
            default -> sender.sendMessage("§e/autotune on|off|status|pitch <반음>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0], List.of("on", "off", "status", "pitch"), result);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("pitch")) {
            StringUtil.copyPartialMatches(args[1], List.of("-1", "-2", "-3", "-4", "-6"), result);
        }
        return result;
    }
}
