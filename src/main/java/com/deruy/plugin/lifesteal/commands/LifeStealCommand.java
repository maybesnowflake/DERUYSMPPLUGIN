package com.deruy.plugin.lifesteal.commands;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * /lifesteal toggle           - 시스템 전체 on/off (#12)
 * /lifesteal status           - 현재 상태 확인
 * /lifesteal recipe additem <레시피이름> <슬롯문자> <머티리얼>  - 레시피 재료 커스터마이징 (#8)
 */
public class LifeStealCommand implements CommandExecutor, TabCompleter {

    private final DeruyPlugin plugin;

    public LifeStealCommand(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§e/lifesteal toggle | status | recipe additem <name> <slot> <material>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "toggle" -> {
                boolean newState = !plugin.getLifeStealManager().isSystemEnabled();
                plugin.getLifeStealManager().setSystemEnabled(newState);
                sender.sendMessage("§aLifeSteal 시스템: " + (newState ? "§a활성화" : "§c비활성화"));
                return true;
            }
            case "status" -> {
                boolean enabled = plugin.getLifeStealManager().isSystemEnabled();
                sender.sendMessage("§eLifeSteal 시스템 현재 상태: " + (enabled ? "§a활성화" : "§c비활성화"));

                if (sender instanceof Player p) {
                    String role = plugin.getRoleManager().resolveConfiguredRole(p);
                    double maxHealth = plugin.getLifeStealManager().getMaxHealth(p);
                    int maxHearts = (int) (maxHealth / 2);

                    if (role != null) {
                        sender.sendMessage("§e당신의 역할: §a" + role + " §7(적용중인 최대하트: " + maxHearts + "개)");
                    } else {
                        sender.sendMessage("§e당신의 역할: §c없음 §7(등록된 roles 중 매칭 안 됨 → 전역값 " + maxHearts + "개 적용중)");
                    }
                }
                return true;
            }
            case "recipe" -> {
                if (args.length < 5 || !args[1].equalsIgnoreCase("additem")) {
                    sender.sendMessage("§c사용법: /lifesteal recipe additem <레시피이름> <슬롯문자> <머티리얼>");
                    sender.sendMessage("§7레시피이름: " + String.join(", ", plugin.getRecipeManager().getRegisteredRecipeNames()));
                    return true;
                }
                String recipeName = args[2];
                char slot = args[3].charAt(0);
                Material material;
                try {
                    material = Material.valueOf(args[4].toUpperCase());
                } catch (IllegalArgumentException e) {
                    sender.sendMessage("§c존재하지 않는 아이템: " + args[4]);
                    return true;
                }

                boolean success = plugin.getRecipeManager().addIngredientToRecipe(recipeName, slot, material);
                if (success) {
                    sender.sendMessage("§a레시피 '" + recipeName + "'의 슬롯 '" + slot + "'를 " + material + "(으)로 변경했습니다.");
                } else {
                    sender.sendMessage("§c해당 이름의 셰이프드 레시피를 찾을 수 없습니다: " + recipeName);
                }
                return true;
            }
            case "naturaldeath" -> {
                if (args.length < 2 || (!args[1].equalsIgnoreCase("on") && !args[1].equalsIgnoreCase("off"))) {
                    sender.sendMessage("§c사용법: /lifesteal naturaldeath on|off");
                    return true;
                }
                boolean enabled = args[1].equalsIgnoreCase("on");
                plugin.getConfig().set("lifesteal.natural-death-removes-heart", enabled);
                plugin.saveConfig();
                sender.sendMessage("§a자연사 하트차감: " + (enabled ? "§a활성화" : "§c비활성화"));
                return true;
            }
            case "itemlimit" -> {
                if (args.length < 2) {
                    sender.sendMessage("§c사용법: /lifesteal itemlimit status [레시피이름] | reset <레시피이름>");
                    return true;
                }
                if (args[1].equalsIgnoreCase("status")) {
                    if (args.length >= 3) {
                        String name = args[2];
                        sender.sendMessage("§e" + name + ": §f" + plugin.getItemLimitManager().describeLimit(name));
                    } else {
                        var section = plugin.getConfig().getConfigurationSection("item-limits");
                        if (section == null || section.getKeys(false).isEmpty()) {
                            sender.sendMessage("§7설정된 개수제한이 없습니다.");
                            return true;
                        }
                        sender.sendMessage("§6=== 제작개수 제한 현황 ===");
                        for (String name : section.getKeys(false)) {
                            sender.sendMessage("§e" + name + ": §f" + plugin.getItemLimitManager().describeLimit(name));
                        }
                    }
                    return true;
                }
                if (args[1].equalsIgnoreCase("reset")) {
                    if (args.length < 3) {
                        sender.sendMessage("§c사용법: /lifesteal itemlimit reset <레시피이름>");
                        sender.sendMessage("§7주의: 개체별로 실시간 추적하는 방식이라, reset은 그 레시피의");
                        sender.sendMessage("§7추적 목록을 통째로 비웁니다. (전부 파괴/삭제된 걸 확인했을 때만 사용)");
                        return true;
                    }
                    int before = plugin.getItemLimitManager().getCurrentCount(args[2]);
                    plugin.getItemLimitManager().clearAll(args[2]);
                    sender.sendMessage("§a'" + args[2] + "'의 추적 목록을 비웠습니다. (" + before + "개 → 0개)");
                    return true;
                }
                sender.sendMessage("§c사용법: /lifesteal itemlimit status [레시피이름] | reset <레시피이름>");
                return true;
            }
            default -> {
                sender.sendMessage("§e알 수 없는 하위 명령어입니다.");
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0], List.of("toggle", "status", "recipe", "naturaldeath", "itemlimit"), result);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("itemlimit")) {
            StringUtil.copyPartialMatches(args[1], List.of("status", "reset"), result);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("itemlimit")) {
            var section = plugin.getConfig().getConfigurationSection("item-limits");
            if (section != null) StringUtil.copyPartialMatches(args[2], section.getKeys(false), result);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("naturaldeath")) {
            StringUtil.copyPartialMatches(args[1], List.of("on", "off"), result);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("recipe")) {
            StringUtil.copyPartialMatches(args[1], List.of("additem"), result);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("recipe") && args[1].equalsIgnoreCase("additem")) {
            StringUtil.copyPartialMatches(args[2], plugin.getRecipeManager().getRegisteredRecipeNames(), result);
        } else if (args.length == 4 && args[0].equalsIgnoreCase("recipe")) {
            StringUtil.copyPartialMatches(args[3], List.of("1", "2", "3", "4", "5", "6", "7", "8", "9"), result);
        } else if (args.length == 5 && args[0].equalsIgnoreCase("recipe")) {
            StringUtil.copyPartialMatches(args[4],
                    Arrays.stream(Material.values()).map(Enum::name).toList(), result);
        }
        return result;
    }
}
