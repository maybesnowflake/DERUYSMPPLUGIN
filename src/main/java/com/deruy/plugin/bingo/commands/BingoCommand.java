package com.deruy.plugin.bingo.commands;

import com.deruy.plugin.DeruyPlugin;
import com.deruy.plugin.bingo.BingoCell;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * /bingo team <플레이어> <팀이름>  - 팀 배정
 * /bingo start | stop             - 이벤트 시작/종료
 * /bingo board | boardlist        - 보드 확인
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
                showBoard(sender);
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
     * 채팅으로 현재 빙고판을 출력한다. 발신자가 팀에 속해있으면 완료된 칸을 §a[V]로 표시.
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

        sender.sendMessage("§d§l=== 빙고판 " + (team != null ? "(팀: " + team + ")" : "") + " ===");
        for (int r = 0; r < size; r++) {
            StringBuilder line = new StringBuilder();
            for (int c = 0; c < size; c++) {
                int idx = r * size + c;
                BingoCell cell = board.get(idx);
                boolean done = progress.contains(idx);
                String mark = done ? "§a[V]" : "§7[ ]";
                line.append(mark).append(" ");
            }
            sender.sendMessage(line.toString());
        }
        sender.sendMessage("§7각 칸의 실제 내용은 /bingo boardlist 로 확인하세요.");
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
