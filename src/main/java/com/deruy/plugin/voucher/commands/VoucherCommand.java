package com.deruy.plugin.voucher.commands;

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

/**
 * /voucher give <player> <1|2> [개수]
 */
public class VoucherCommand implements CommandExecutor, TabCompleter {

    private final DeruyPlugin plugin;

    public VoucherCommand(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 3 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage("§c사용법: /voucher give <player> <1|2> [개수]");
            return true;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage("§c플레이어를 찾을 수 없습니다: " + args[1]);
            return true;
        }

        int tier;
        try {
            tier = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§c티어는 1 또는 2여야 합니다.");
            return true;
        }
        if (tier != 1 && tier != 2) {
            sender.sendMessage("§c티어는 1 또는 2여야 합니다.");
            return true;
        }

        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Math.max(1, Integer.parseInt(args[3]));
            } catch (NumberFormatException ignored) {
            }
        }

        for (int i = 0; i < amount; i++) {
            var leftover = target.getInventory().addItem(plugin.getVoucherManager().createVoucher(tier));
            leftover.values().forEach(remain -> target.getWorld().dropItemNaturally(target.getLocation(), remain));
        }

        sender.sendMessage("§a" + target.getName() + "님에게 티어 " + tier + " 상품권 " + amount + "개를 지급했습니다.");
        target.sendMessage("§e티어 " + tier + " 상품권 " + amount + "개를 받았습니다!");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0], List.of("give"), result);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            StringUtil.copyPartialMatches(args[1],
                    Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), result);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            StringUtil.copyPartialMatches(args[2], List.of("1", "2"), result);
        } else if (args.length == 4 && args[0].equalsIgnoreCase("give")) {
            StringUtil.copyPartialMatches(args[3], List.of("1", "5", "10"), result);
        }
        return result;
    }
}
