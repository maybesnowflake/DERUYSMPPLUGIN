package com.deruy.plugin.lifesteal.commands;

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
 * /withdraw <개수> - 자신의 하트를 개수만큼 소모하고, 그만큼 하트 아이템(빨간염료)을 인벤토리로 받는다.
 * 최소 하트(min-hearts) 밑으로는 인출할 수 없다 (밴 방지).
 */
public class WithdrawCommand implements CommandExecutor, TabCompleter {

    private final DeruyPlugin plugin;

    public WithdrawCommand(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c이 명령어는 인게임에서만 사용 가능합니다.");
            return true;
        }

        if (!plugin.getLifeStealManager().isSystemEnabled()) {
            sender.sendMessage("§cLifeSteal 시스템이 비활성화되어 있습니다.");
            return true;
        }

        int amount = 1;
        if (args.length >= 1) {
            try {
                amount = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§c숫자를 입력해주세요.");
                return true;
            }
        }

        if (amount < 1) {
            sender.sendMessage("§c1개 이상 인출해야 합니다.");
            return true;
        }

        boolean success = plugin.getLifeStealManager().withdrawHearts(player, amount);
        if (!success) {
            int minHearts = (int) (plugin.getLifeStealManager().getMinHealth() / 2);
            player.sendMessage(plugin.getMessage("withdraw-too-low",
                            "&c하트가 부족합니다. 최소 {min}개의 하트는 유지해야 합니다.")
                    .replace("{min}", String.valueOf(minHearts)));
            return true;
        }

        for (int i = 0; i < amount; i++) {
            var leftover = player.getInventory().addItem(plugin.getRecipeManager().createHeartItem());
            leftover.values().forEach(remain -> player.getWorld().dropItemNaturally(player.getLocation(), remain));
        }

        player.sendMessage(plugin.getMessage("withdraw-success", "&a하트 {amount}개를 인출했습니다.")
                .replace("{amount}", String.valueOf(amount)));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0], List.of("1", "2", "3", "5"), result);
        }
        return result;
    }
}
