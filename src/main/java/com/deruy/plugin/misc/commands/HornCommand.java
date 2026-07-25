package com.deruy.plugin.misc.commands;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.Material;
import org.bukkit.MusicInstrument;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MusicInstrumentMeta;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * /horn <1~8> - 번호별로 서로 다른 소리(악기)가 나는 염소뿔을 지급한다 (개수가 아니라 종류 선택).
 * config의 horn.allowed-roles에 등록된 역할만 사용 가능.
 * 지급되는 염소뿔에는 항상 소실의 저주(VANISHING_CURSE)가 인첸트되어 있다.
 */
public class HornCommand implements CommandExecutor, TabCompleter {

 private static final MusicInstrument[] INSTRUMENTS = {
            MusicInstrument.PONDER_GOAT_HORN,
            MusicInstrument.SING_GOAT_HORN,
            MusicInstrument.SEEK_GOAT_HORN,
            MusicInstrument.FEEL_GOAT_HORN,
            MusicInstrument.ADMIRE_GOAT_HORN,
            MusicInstrument.CALL_GOAT_HORN,
            MusicInstrument.YEARN_GOAT_HORN,
            MusicInstrument.DREAM_GOAT_HORN
    };

    private final DeruyPlugin plugin;

    public HornCommand(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c이 명령어는 인게임에서만 사용 가능합니다.");
            return true;
        }

        if (!hasAllowedRole(player)) {
            sender.sendMessage(plugin.getMessage("horn-no-permission", "&c이 명령어를 사용할 권한이 없습니다."));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage("§c사용법: /horn <1~8> (번호마다 다른 소리)");
            return true;
        }

        int number;
        try {
            number = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§c숫자를 입력해주세요. (1~8)");
            return true;
        }

        if (number < 1 || number > 8) {
            sender.sendMessage("§c번호는 1~8 사이여야 합니다.");
            return true;
        }

        ItemStack horn = createEnchantedHorn(INSTRUMENTS[number - 1]);
        var leftover = player.getInventory().addItem(horn);
        leftover.values().forEach(remain -> player.getWorld().dropItemNaturally(player.getLocation(), remain));

        player.sendMessage("§a" + number + "번 소리의 염소뿔을 받았습니다. (소실의 저주 인첸트됨)");
        return true;
    }

    private ItemStack createEnchantedHorn(MusicInstrument instrument) {
        ItemStack horn = new ItemStack(Material.GOAT_HORN);
        MusicInstrumentMeta meta = (MusicInstrumentMeta) horn.getItemMeta();
        meta.setInstrument(instrument);
        meta.addEnchant(Enchantment.VANISHING_CURSE, 1, true);
        horn.setItemMeta(meta);
        return horn;
    }

    private boolean hasAllowedRole(Player player) {
        List<String> allowedRoles = plugin.getConfig().getStringList("horn.allowed-roles");
        if (allowedRoles.isEmpty()) return true;

        for (String role : allowedRoles) {
            if (plugin.hasRole(player, role)) return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0], List.of("1", "2", "3", "4", "5", "6", "7", "8"), result);
        }
        return result;
    }
}
