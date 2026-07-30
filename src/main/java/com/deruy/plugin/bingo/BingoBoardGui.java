package com.deruy.plugin.bingo;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Set;

/**
 * /bingo board 전용 GUI. 텍스트 [ ]/[V] 대신 실제 아이템 아이콘을 격자로 보여준다.
 * - 완료된 칸: 아이템에 인챈트 광택(글로우) 효과 + 초록색 "완료" 표시
 * - 미완료 칸: 그냥 아이템 아이콘 + 빨간색 "미완료" 표시
 * - 빈 칸(보드 크기가 9보다 작을 때 남는 칸)은 회색 유리판으로 채움
 */
public class BingoBoardGui {

    /** 이 인벤토리가 빙고판 GUI라는 걸 식별하기 위한 홀더 (클릭 취소용) */
    public static class Holder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null; // Bukkit이 실제로 쓰지 않는 값, 식별용도로만 사용
        }
    }

    public static void open(Player player, DeruyPlugin plugin) {
        List<BingoCell> board = plugin.getBingoManager().getBoard();
        if (board.isEmpty()) {
            player.sendMessage("§c진행중인 빙고가 없습니다.");
            return;
        }

        int size = plugin.getBingoManager().getSize();
        String team = plugin.getBingoManager().getTeam(player);
        Set<Integer> progress = team != null ? plugin.getBingoManager().getProgress(team) : Set.of();

        int invSize = size * 9;
        String title = "§d빙고판" + (team != null ? " §7(팀: " + team + ")" : "");
        Inventory inv = org.bukkit.Bukkit.createInventory(new Holder(), invSize, title);

        ItemStack filler = createFiller();
        for (int i = 0; i < invSize; i++) {
            inv.setItem(i, filler);
        }

        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                int cellIndex = r * size + c;
                int slot = r * 9 + c;
                BingoCell cell = board.get(cellIndex);
                boolean done = progress.contains(cellIndex);
                inv.setItem(slot, buildCellIcon(cell, done));
            }
        }

        player.openInventory(inv);
    }

    private static ItemStack buildCellIcon(BingoCell cell, boolean done) {
        Material material = resolveCellIcon(cell);
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        String typeLabel = cell.getType() == BingoCell.Type.BIOME ? "§7[바이옴] " : "§7[아이템] ";
        meta.setDisplayName((done ? "§a" : "§e") + typeLabel + cell.getValue());
        meta.setLore(List.of(done ? "§a§l✔ 완료" : "§c§l✘ 미완료"));

        if (done) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }

        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack createFiller() {
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        meta.setDisplayName(" ");
        filler.setItemMeta(meta);
        return filler;
    }

    private static Material resolveCellIcon(BingoCell cell) {
        if (cell.getType() == BingoCell.Type.ITEM) {
            try {
                return Material.valueOf(cell.getValue());
            } catch (IllegalArgumentException e) {
                return Material.BARRIER;
            }
        }
        return resolveBiomeIcon(cell.getValue());
    }

    private static Material resolveBiomeIcon(String biomeName) {
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

        return Material.GRASS_BLOCK;
    }
}
