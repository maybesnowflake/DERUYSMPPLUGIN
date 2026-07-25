package com.deruy.plugin.role.commands;

import com.deruy.plugin.DeruyPlugin;
import com.deruy.plugin.role.RoleManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * /pvp <역할 [역할 역할...]|ALL> on|off [시간]
 * 예: /pvp L V on 1h        -> L, V 역할 PVP를 1시간 동안 켬
 *     /pvp ALL off 30m      -> 전체 PVP를 30분 동안 끔
 *     /pvp FWN on           -> 시간 생략시 영구 적용(다음 명시적 변경 전까지)
 */
public class PvpCommand implements CommandExecutor, TabCompleter {

    private final DeruyPlugin plugin;

    public PvpCommand(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c사용법: /pvp <역할 [역할...]|ALL> on|off [시간(예: 1h, 30m)]");
            return true;
        }

        String[] roles;
        String state;
        String durationStr = null;

        if (args[args.length - 1].equalsIgnoreCase("on") || args[args.length - 1].equalsIgnoreCase("off")) {
            state = args[args.length - 1];
            roles = java.util.Arrays.copyOfRange(args, 0, args.length - 1);
        } else {
            state = args[args.length - 2];
            durationStr = args[args.length - 1];
            roles = java.util.Arrays.copyOfRange(args, 0, args.length - 2);
        }

        if (!state.equalsIgnoreCase("on") && !state.equalsIgnoreCase("off")) {
            sender.sendMessage("§c사용법: /pvp <역할 [역할...]|ALL> on|off [시간(예: 1h, 30m)]");
            return true;
        }

        if (roles.length == 0) {
            sender.sendMessage("§c역할을 최소 1개 이상 입력해주세요. (또는 ALL)");
            return true;
        }

        boolean allowed = state.equalsIgnoreCase("on");
        long durationMillis = 365L * 24 * 60 * 60 * 1000; // 시간 생략시 사실상 영구(1년)

        if (durationStr != null) {
            long parsed = RoleManager.parseDuration(durationStr);
            if (parsed <= 0) {
                sender.sendMessage("§c시간 형식이 잘못됐습니다. 예: 1h, 30m, 45s, 2d");
                return true;
            }
            durationMillis = parsed;
        }

        RoleManager roleManager = plugin.getRoleManager();
        for (String role : roles) {
            roleManager.setTemporaryPvpState(role, allowed, durationMillis);
        }

        String roleList = String.join(", ", roles);
        String durationText = durationStr != null ? durationStr : "무기한";
        Bukkit.broadcastMessage("§6[PVP] §e" + roleList + " 역할 PVP가 " + (allowed ? "§a활성화" : "§c비활성화")
                + "§e되었습니다. (" + durationText + ")");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        var rolesSection = plugin.getConfig().getConfigurationSection("roles");
        List<String> roleNames = new ArrayList<>();
        roleNames.add("ALL");
        if (rolesSection != null) roleNames.addAll(rolesSection.getKeys(false));

        if (args.length >= 1) {
            String last = args[args.length - 1];
            if (last.equalsIgnoreCase("on") || last.equalsIgnoreCase("off")) {
                StringUtil.copyPartialMatches("", List.of("1h", "30m", "10m", "1d"), result);
            } else {
                StringUtil.copyPartialMatches(last, roleNames, result);
                StringUtil.copyPartialMatches(last, List.of("on", "off"), result);
            }
        }
        return result;
    }
}
