package com.deruy.plugin.bingo.commands;

import com.deruy.plugin.DeruyPlugin;
import com.deruy.plugin.bingo.BingoCell;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * /bingo team <플레이어> <팀이름>  - 팀 배정
 * /bingo start | stop             - 이벤트 시작/종료
 * /bingo board | boardlist        - 보드 확인 (board는 [ ]/[V]에 마우스 올리면 실제 아이템 표시)
 * /bingo excludeitem <머티리얼>     - 제외 아이템 추가 (config에도 반영)
 * /bingo excludebiome <바이옴>      - 제외 바이옴 추가 (config에도 반영)
 */
public class BingoCommand implements CommandExecutor, TabCompleter {

    private final DeruyPlugin plugin;

    public BingoCommand(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§e/bingo team <player> <team> | start | stop | board | boardlist | excludeitem <material> | excludebiome <biome>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "team" -> {
                if (args.length < 3) {
                    sender.sendMessage("§c사용법: /bingo team <player> <team>");
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage("§c플레이어를 찾을 수 없습니다: " + args[1]);
                    return true;
                }
                plugin.getBingoManager().setTeam(target, args[2]);
                sender.sendMessage("§a" + target.getName() + "님을 팀 '" + args[2] + "'에 배정했습니다.");
                return true;
            }
            case "start" -> {
                plugin.getBingoManager().start();
                return true;
            }
            case "stop" -> {
                plugin.getBingoManager().stop();
                return true;
            }
            case "board" -> {
                if (sender instanceof Player p) {
                    com.deruy.plugin.bingo.BingoBoardGui.open(p, plugin);
                } else {
                    showBoard(sender);
                }
                return true;
            }
            case "boardlist" -> {
                showBoardList(sender);
                return true;
            }
            case "excludeitem" -> {
                if (args.length < 2) {
                    sender.sendMessage("§c사용법: /bingo excludeitem <머티리얼이름>");
                    return true;
                }
                var list = plugin.getConfig().getStringList("bingo.excluded-items");
                list.add(args[1].toUpperCase());
                plugin.getConfig().set("bingo.excluded-items", list);
                plugin.saveConfig();
                sender.sendMessage("§a제외 아이템에 " + args[1].toUpperCase() + " 추가됨. (다음 /bingo start 부터 반영)");
                return true;
            }
            case "excludebiome" -> {
                if (args.length < 2) {
                    sender.sendMessage("§c사용법: /bingo excludebiome <바이옴이름>");
                    return true;
                }
                var list = plugin.getConfig().getStringList("bingo.excluded-biomes");
                list.add(args[1].toUpperCase());
                plugin.getConfig().set("bingo.excluded-biomes", list);
                plugin.saveConfig();
                sender.sendMessage("§a제외 바이옴에 " + args[1].toUpperCase() + " 추가됨. (다음 /bingo start 부터 반영)");
                return true;
            }
            default -> {
                sender.sendMessage("§c알 수 없는 하위 명령어입니다.");
                return true;
            }
        }
    }

    /**
     * 채팅으로 현재 빙고판을 출력한다. [ ]/[V] 칸에 마우스를 올리면 실제 아이템(바이옴은 대표아이템)이 표시된다.
     * 미완료=빨간색, 완료=초록색.
     */
    private void showBoard(CommandSender sender) {
        List<BingoCell> board = plugin.getBingoManager().getBoard();
        if (board.isEmpty()) {
            sender.sendMessage("§c진행중인 빙고가 없습니다.");
            return;
        }

        int size = plugin.getBingoManager().getSize();
        String team = (sender instanceof Player p) ? plugin.getBingoManager().getTeam(p) : null;
        Set<Integer> progress = team != null ? plugin.getBingoManager().getProgress(team) : Set.of();

        sender.sendMessage("§d§l=== 빙고판 " + (team != null ? "(팀: " + team + ")" : "") + " §7(칸에 마우스를 올리면 아이템 표시) ===");

        for (int r = 0; r < size; r++) {
            Component row = Component.empty();
            for (int c = 0; c < size; c++) {
                int idx = r * size + c;
                BingoCell cell = board.get(idx);
                boolean done = progress.contains(idx);

                ItemStack icon = resolveCellIcon(cell);
                NamedTextColor color = done ? NamedTextColor.GREEN : NamedTextColor.RED;
                String bracketText = done ? "[V]" : "[ ]";

                Component bracket = Component.text(bracketText, color).hoverEvent(icon.asHoverEvent());
                row = row.append(bracket).append(Component.text(" "));
            }
            sender.sendMessage(row);
        }
    }

    private void showBoardList(CommandSender sender) {
        List<BingoCell> board = plugin.getBingoManager().getBoard();
        if (board.isEmpty()) {
            sender.sendMessage("§c진행중인 빙고가 없습니다.");
            return;
        }

        int size = plugin.getBingoManager().getSize();
        String team = (sender instanceof Player p) ? plugin.getBingoManager().getTeam(p) : null;
        Set<Integer> progress = team != null ? plugin.getBingoManager().getProgress(team) : Set.of();

        sender.sendMessage("§d§l=== 빙고판 목록 ===");
        for (int i = 0; i < board.size(); i++) {
            int row = i / size + 1;
            int col = i % size + 1;
            boolean done = progress.contains(i);
            String prefix = done ? "§a[완료] " : "§7[ - ] ";
            sender.sendMessage(prefix + "(" + row + "," + col + ") " + board.get(i).display());
        }
    }

    /** 셀에 대응하는 아이콘 아이템을 만든다. ITEM 타입은 그 아이템 그대로, BIOME 타입은 대표 아이템으로. */
    private ItemStack resolveCellIcon(BingoCell cell) {
        if (cell.getType() == BingoCell.Type.ITEM) {
            try {
                return new ItemStack(Material.valueOf(cell.getValue()));
            } catch (IllegalArgumentException e) {
                return new ItemStack(Material.BARRIER);
            }
        }
        return new ItemStack(resolveBiomeIcon(cell.getValue()));
    }

    /** 바이옴 이름의 키워드를 보고 그 바이옴을 대표하는 아이템을 골라준다 (완전 매칭표는 아니고 패턴기반 근사치). */
    private Material resolveBiomeIcon(String biomeName) {
        String n = biomeName.toUpperCase();

        if (n.contains("MUSHROOM")) return Material.RED_MUSHROOM;
        if (n.contains("BADLANDS")) return Material.RED_SAND;
        if (n.contains("DESERT")) return Material.SAND;
        if (n.contains("BEACH") || n.contains("STONY_SHORE")) return Material.SAND;
        if (n.contains("OCEAN") || n.contains("RIVER")) return Material.PRISMARINE_SHARD;
        if (n.contains("MANGROVE")) return Material.MANGROVE_PROPAGULE;
        if (n.contains("SWAMP")) return Material.LILY_PAD;
        if (n.contains("BAMBOO")) return Material.BAMBOO;
        if (n.contains("JUNGLE")) return Material.JUNGLE_SAPLING;
        if (n.contains("SAVANNA")) return Material.ACACIA_SAPLING;
        if (n.contains("CHERRY")) return Material.CHERRY_SAPLING;
        if (n.contains("BIRCH")) return Material.BIRCH_SAPLING;
        if (n.contains("DARK_FOREST")) return Material.DARK_OAK_SAPLING;
        if (n.contains("TAIGA")) return Material.SPRUCE_SAPLING;
        if (n.contains("SUNFLOWER")) return Material.SUNFLOWER;
        if (n.contains("FOREST")) return Material.OAK_SAPLING;
        if (n.contains("MEADOW")) return Material.PINK_TULIP;
        if (n.contains("GROVE")) return Material.POWDER_SNOW_BUCKET;
        if (n.contains("FROZEN") || n.contains("SNOWY") || n.contains("ICE_SPIKES")) return Material.SNOWBALL;
        if (n.contains("DEEP_DARK")) return Material.SCULK;
        if (n.contains("LUSH_CAVES")) return Material.MOSS_BLOCK;
        if (n.contains("DRIPSTONE")) return Material.POINTED_DRIPSTONE;
        if (n.contains("CRIMSON")) return Material.CRIMSON_FUNGUS;
        if (n.contains("WARPED")) return Material.WARPED_FUNGUS;
        if (n.contains("SOUL_SAND")) return Material.SOUL_SAND;
        if (n.contains("BASALT")) return Material.BASALT;
        if (n.contains("NETHER")) return Material.NETHERRACK;
        if (n.contains("CHORUS") || n.contains("END")) return Material.CHORUS_FRUIT;
        if (n.contains("WINDSWEPT") || n.contains("MOUNTAIN") || n.contains("PEAK") || n.contains("HILLS") || n.contains("SLOPES")) return Material.STONE;
        if (n.contains("PLAINS")) return Material.GRASS_BLOCK;

        return Material.GRASS_BLOCK; // 매칭 안 되는 경우 기본값
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();

        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0],
                    List.of("team", "start", "stop", "board", "boardlist", "excludeitem", "excludebiome"), result);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("team")) {
            StringUtil.copyPartialMatches(args[1],
                    Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()), result);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("excludeitem")) {
            StringUtil.copyPartialMatches(args[1],
                    java.util.Arrays.stream(org.bukkit.Material.values()).map(Enum::name).toList(), result);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("excludebiome")) {
            StringUtil.copyPartialMatches(args[1],
                    java.util.Arrays.stream(org.bukkit.block.Biome.values()).map(b -> b.getKey().getKey()).toList(), result);
        }
        return result;
    }
}
