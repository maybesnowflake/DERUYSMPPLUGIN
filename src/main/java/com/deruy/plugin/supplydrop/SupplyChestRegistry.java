package com.deruy.plugin.supplydrop;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * 일반/슈퍼 서플라이드랍이 공유하는 상자 레지스트리이자, 서버 전체 보상굴림(rollRewards)의 중심 클래스.
 * KOTH/빙고/바운티/서플라이드랍/상품권 등 reward-items를 쓰는 모든 곳이 이 rollRewards를 공유한다.
 *
 * reward-items 항목 형식 (각 항목은 아래 넷 중 하나, 맨 끝에 ":개수"를 추가로 붙일 수 있음):
 *   "MATERIAL[:확률][:개수]"          - 바닐라 아이템 (예: "EXPERIENCE_BOTTLE:0.1:5" = 10%확률로 5개)
 *   "HEART[:확률][:개수]"             - 하트 아이템
 *   "RECIPE:레시피이름[:확률][:개수]"   - lifesteal.recipes에 등록된 커스텀 아이템
 *                                     (item-limits 걸린 레시피면 상한 체크 + 개체추적 등록까지 매 개당 처리)
 *   "VOUCHER:티어[:확률][:개수]"       - 상품권 아이템
 *
 * 확률/개수를 생략하면 각각 100%/1개로 취급된다.
 */
public class SupplyChestRegistry {

    private final DeruyPlugin plugin;
    private final Map<Location, List<ItemStack>> chests = new HashMap<>();

    public SupplyChestRegistry(DeruyPlugin plugin) {
        this.plugin = plugin;
        // 서버 재시작 전에 저장해둔 상자들을 복원 (안 그러면 재시작 후 빈 상자가 돼버림)
        chests.putAll(plugin.getDataStore().loadAllSupplyChests());
        if (!chests.isEmpty()) {
            plugin.getLogger().info("서플라이드랍 상자 " + chests.size() + "개를 data.yml에서 불러왔습니다.");
        }
    }

    public List<ItemStack> rollRewards(List<String> rewardEntries, Logger logger) {
        List<ItemStack> result = new ArrayList<>();

        for (String entry : rewardEntries) {
            String[] parts = entry.split(":");
            if (parts.length == 0) continue;

            String keyword = parts[0].trim().toUpperCase();

            if (keyword.equals("HEART")) {
                // HEART[:확률][:개수]
                double probability = parts.length >= 2 ? parseProbability(parts[1], entry, logger) : 1.0;
                int amount = parts.length >= 3 ? parseAmount(parts[2], entry, logger) : 1;
                if (roll(probability)) {
                    for (int i = 0; i < amount; i++) {
                        result.add(plugin.getRecipeManager().createHeartItem());
                    }
                }
                continue;
            }

            if (keyword.equals("RECIPE")) {
                // RECIPE:레시피이름[:확률][:개수]
                if (parts.length < 2) {
                    logger.warning("잘못된 RECIPE 보상 형식: " + entry + " (예: RECIPE:custom_netherite_sword:0.1:2)");
                    continue;
                }
                String recipeId = parts[1].trim();
                double probability = parts.length >= 3 ? parseProbability(parts[2], entry, logger) : 1.0;
                int amount = parts.length >= 4 ? parseAmount(parts[3], entry, logger) : 1;
                if (roll(probability)) {
                    for (int i = 0; i < amount; i++) {
                        addRecipeReward(result, recipeId, logger);
                    }
                }
                continue;
            }

            if (keyword.equals("VOUCHER")) {
                // VOUCHER:티어[:확률][:개수]
                if (parts.length < 2) {
                    logger.warning("잘못된 VOUCHER 보상 형식: " + entry + " (예: VOUCHER:1:0.3:2)");
                    continue;
                }
                int tier;
                try {
                    tier = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException e) {
                    logger.warning("잘못된 VOUCHER 티어: " + entry);
                    continue;
                }
                double probability = parts.length >= 3 ? parseProbability(parts[2], entry, logger) : 1.0;
                int amount = parts.length >= 4 ? parseAmount(parts[3], entry, logger) : 1;
                if (roll(probability)) {
                    for (int i = 0; i < amount; i++) {
                        result.add(plugin.getVoucherManager().createVoucher(tier));
                    }
                }
                continue;
            }

            // 기본: 바닐라 MATERIAL[:확률][:개수] 형식
            String materialName = parts[0].trim();
            double probability = parts.length >= 2 ? parseProbability(parts[1], entry, logger) : 1.0;
            int amount = parts.length >= 3 ? parseAmount(parts[2], entry, logger) : 1;

            try {
                Material material = Material.valueOf(materialName.toUpperCase());
                if (roll(probability)) {
                    ItemStack item = new ItemStack(material, amount);
                    result.add(item);
                }
            } catch (IllegalArgumentException e) {
                logger.warning("존재하지 않는 아이템: " + materialName);
            }
        }

        return result;
    }

    /** RECIPE 보상 처리 - item-limits 걸린 레시피면 상한 체크 + 개체추적 등록까지 같이 함 */
    private void addRecipeReward(List<ItemStack> result, String recipeId, Logger logger) {
        ItemStack template = plugin.getRecipeManager().createItemForRecipe(recipeId);
        if (template == null) {
            logger.warning("존재하지 않는 레시피: " + recipeId);
            return;
        }

        if (plugin.getItemLimitManager().isLimited(recipeId)) {
            if (!plugin.getItemLimitManager().canCraftMore(recipeId)) {
                logger.info("레시피 '" + recipeId + "' 보상이 서버 전체 상한 도달로 지급되지 않았습니다.");
                return;
            }
            result.add(plugin.getItemLimitManager().tagAndRegisterInstance(recipeId, template));
        } else {
            result.add(template);
        }
    }

    private double parseProbability(String raw, String entry, Logger logger) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            logger.warning("잘못된 확률 형식: " + entry);
            return 1.0;
        }
    }

    private int parseAmount(String raw, String entry, Logger logger) {
        try {
            return Math.max(1, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            logger.warning("잘못된 개수 형식: " + entry);
            return 1;
        }
    }

    private boolean roll(double probability) {
        return ThreadLocalRandom.current().nextDouble() < probability;
    }

    // ---------------- 서플라이드랍 상자 위치 추적 ----------------

    public void register(Location location, List<ItemStack> items) {
        Location blockLoc = location.getBlock().getLocation();
        chests.put(blockLoc, items);
        plugin.getDataStore().saveSupplyChest(blockLoc, items);
    }

    public boolean isTracked(Location location) {
        return chests.containsKey(location.getBlock().getLocation());
    }

    public List<ItemStack> consume(Location location) {
        Location blockLoc = location.getBlock().getLocation();
        List<ItemStack> items = chests.remove(blockLoc);
        plugin.getDataStore().removeSupplyChest(blockLoc);
        return items;
    }
}
