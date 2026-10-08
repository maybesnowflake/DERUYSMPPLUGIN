package com.deruy.plugin.lifesteal.commands;
import com.deruy.plugin.DeruyPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.*;

/** All mutations require deruy.heart.admin. Offline revival is applied at next login. */
public final class HeartCommand implements CommandExecutor, TabCompleter {
    private final DeruyPlugin plugin;
    public HeartCommand(DeruyPlugin plugin) { this.plugin = plugin; }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("deruy.heart.admin")) { sender.sendMessage("§c권한이 없습니다."); return true; }
        if (args.length >= 1 && args[0].equalsIgnoreCase("giveitem")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("§c플레이어만 사용할 수 있습니다."); return true; }
            p.getInventory().addItem(plugin.getReviveItemManager().create()); sender.sendMessage("§a부활 아이템을 지급했습니다."); return true;
        }
        if (args.length < 2) { help(sender); return true; }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (!List.of("info", "set", "add", "remove", "revive").contains(action)) { help(sender); return true; }
        if (action.equals("revive")) return revive(sender, args);
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) { sender.sendMessage("§c온라인 플레이어의 정확한 이름을 입력하세요. 오프라인 부활은 /heart revive 를 사용하세요."); return true; }
        var manager = plugin.getLifeStealManager();
        if (action.equals("info")) {
            sender.sendMessage("§e" + target.getName() + " §f하트: " + manager.getHearts(target)
                + " / " + manager.getMaxHealth(target)/2 + " §7(밴 기준: " + manager.getMinHealth()/2 + " 이하)"); return true;
        }
        if (args.length != 3) { help(sender); return true; }
        int amount;
        try { amount = Integer.parseInt(args[2]); if (amount <= 0 || amount > 1024) throw new NumberFormatException(); }
        catch (NumberFormatException e) { sender.sendMessage("§c하트는 1~1024 사이 정수로 입력하세요."); return true; }
        double before = manager.getHearts(target);
        String reason = "ADMIN_" + action.toUpperCase(Locale.ROOT) + ":" + sender.getName();
        boolean changed = true;
        switch (action) {
            case "set" -> changed = manager.setHearts(target, amount, reason);
            case "add" -> changed = manager.addHeart(target, amount, reason);
            case "remove" -> manager.removeHeart(target, amount, reason);
        }
        if (!changed) sender.sendMessage("§c설정 가능한 하트 범위: " + (manager.getMinHealth()/2) + " 초과 ~ " + manager.getMaxHealth(target)/2 + " 이하");
        else sender.sendMessage("§a" + target.getName() + " 하트: " + before + " → " + manager.getHearts(target));
        return true;
    }
    private boolean revive(CommandSender sender, String[] args) {
        OfflinePlayer target;
        try { target = Bukkit.getOfflinePlayer(UUID.fromString(args[1])); }
        catch (IllegalArgumentException e) {
            target = Bukkit.getOfflinePlayerIfCached(args[1]);
            if (target == null) {
                target = Arrays.stream(Bukkit.getOfflinePlayers()).filter(p -> args[1].equalsIgnoreCase(p.getName())).findFirst().orElse(null);
            }
        }
        if (target == null || (!target.hasPlayedBefore() && !target.isOnline()
                && !plugin.getDataStore().isHeartBan(target.getUniqueId()))) {
            sender.sendMessage("§c접속 기록이 있는 이름 또는 UUID를 입력하세요."); return true;
        }
        int hearts = plugin.getConfig().getInt("heart.revive-hearts", 10);
        if (args.length >= 3) {
            try { hearts = Integer.parseInt(args[2]); }
            catch (NumberFormatException e) { sender.sendMessage("§c하트 수는 정수로 입력하세요."); return true; }
        }
        var manager = plugin.getLifeStealManager();
        double cap = target.isOnline() ? manager.getMaxHealth(target.getPlayer())/2 : manager.getMaxHealth()/2;
        if (hearts <= manager.getMinHealth()/2 || hearts > cap) {
            sender.sendMessage("§c부활 하트는 " + manager.getMinHealth()/2 + " 초과 ~ " + cap + " 이하로 입력하세요."); return true;
        }
        org.bukkit.BanList<org.bukkit.profile.PlayerProfile> bans = Bukkit.getBanList(org.bukkit.BanList.Type.PROFILE);
        var entry = bans.getBanEntry(target.getPlayerProfile());
        if (entry != null && !"LifeSteal".equals(entry.getSource())) {
            sender.sendMessage("§c다른 사유로 밴된 플레이어입니다. 해당 밴을 먼저 확인하세요."); return true;
        }
        // Persist first, so a restart before their login cannot lose the restoration.
        plugin.getDataStore().queueHeartRestore(target.getUniqueId(), hearts);
        if (entry != null) bans.pardon(target.getPlayerProfile());
        plugin.getDataStore().setHeartBan(target.getUniqueId(), false);
        plugin.getHeartAuditLog().record(target.getUniqueId(), Objects.toString(target.getName(), args[1]),
                -1, hearts, "REVIVE_QUEUED:" + sender.getName());
        if (target.isOnline()) com.deruy.plugin.lifesteal.listeners.HeartRestoreListener.apply(plugin, target.getPlayer());
        sender.sendMessage("§a부활 처리 완료: " + Objects.toString(target.getName(), args[1]) + " (" + hearts + "하트, 오프라인이면 다음 접속에 적용·역할 상한 반영)");
        return true;
    }
    private void help(CommandSender s) {
        s.sendMessage("§e/heart info <플레이어>");
        s.sendMessage("§e/heart set|add|remove <플레이어> <하트>");
        s.sendMessage("§e/heart revive <플레이어|UUID> [하트]");
        s.sendMessage("§e/heart giveitem");
        s.sendMessage("§7remove로 최소 하트 이하에 도달하면 LifeSteal 밴이 적용됩니다.");
    }
    @Override public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        if (!s.hasPermission("deruy.heart.admin")) return List.of();
        List<String> choices = args.length == 1 ? List.of("info", "set", "add", "remove", "revive", "giveitem")
            : args.length == 2 ? Bukkit.getOnlinePlayers().stream().map(Player::getName).toList()
            : args.length == 3 ? List.of("1", "5", "10") : List.of();
        String prefix = args[args.length-1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
