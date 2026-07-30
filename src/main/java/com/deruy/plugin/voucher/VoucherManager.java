package com.deruy.plugin.voucher;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * 종이로 된 "상품권" 아이템. 티어 1(초록)/티어 2(보라) 두 종류가 있고,
 * 우클릭시 config에 설정된 reward-items를 굴려서 그 자리에서 보상을 지급한다.
 */
public class VoucherManager {

    public static final NamespacedKey TIER_KEY = new NamespacedKey("deruy", "voucher_tier");

    private final DeruyPlugin plugin;

    public VoucherManager(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    public ItemStack createVoucher(int tier) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();

        ChatColor color = tier == 2 ? ChatColor.DARK_PURPLE : ChatColor.GREEN;
        String defaultName = tier == 2 ? "&5&l상품권 [티어 2]" : "&a&l상품권 [티어 1]";
        String displayName = plugin.getConfig().getString("voucher.tier" + tier + ".name", defaultName);

        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', displayName));
        meta.setLore(List.of(
                color + "우클릭하여 개봉하세요.",
                "§7무작위 보상을 획득합니다."
        ));
        meta.getPersistentDataContainer().set(TIER_KEY, PersistentDataType.INTEGER, tier);
        item.setItemMeta(meta);
        return item;
    }

    /** 이 아이템이 상품권이면 티어(1 또는 2)를, 아니면 -1을 반환 */
    public int getTier(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER || item.getItemMeta() == null) return -1;
        Integer tier = item.getItemMeta().getPersistentDataContainer().get(TIER_KEY, PersistentDataType.INTEGER);
        return tier != null ? tier : -1;
    }
}
