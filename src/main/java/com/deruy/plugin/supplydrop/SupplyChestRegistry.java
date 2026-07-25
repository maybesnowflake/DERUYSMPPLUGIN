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
 * reward-items 항목 형식 (각 항목은 아래 넷 중 하나):
 *   "MATERIAL"              - 바닐라 아이템, 100% 확률
 *   "MATERIAL:확률"          - 바닐라 아이템 (예: "DIAMOND:0.1" = 10%)
 *   "HEART" / "HEART:확률"   - 하트 아이템 (RecipeManager.createHeartItem())
 *   "RECIPE:레시피이름[:확률]" - lifesteal.recipes에 등록된 커스텀 아이템 그대로 지급
 *                              (item-limits에 걸린 레시피면 상한 체크 + 개체추적 등록까지 같이 처리)
 *   "VOUCHER:티어[:확률]"     - 상품권 아이템 (티어 1 또는 2)
 */
public class SupplyChestRegistry {

    private final DeruyPlugin plugin;
    private final Map<Location, List<ItemStack>> chests = new HashMap<>();

    public SupplyChestRegistry(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    public List<ItemStack> rollRewards(List<String> rewardEntries, Logger logger) {
        List<ItemStack> result = new ArrayList<>();

        for (String entry : rewardEntries) {
            String[] parts = entry.split(":");
            if (parts.length == 0) continue;

            String keyword = parts[0].trim().toUpperCase();

            if (keyword.equals("HEART")) {
                double probability = parts.length >= 2 ? parseProbability(parts[1], entry, logger) : 1.0;
                if (roll(probability)) {
                    result.add(plugin.getRecipeManager().createHeartItem());
                }
                continue;
            }

            if (keyword.equals("RECIPE")) {
                if (parts.length < 2) {
                    logger.warning("잘못된 RECIPE 보상 형식: " + entry + " (예: RECIPE:custom_netherite_sword:0.1)");
                    continue;
                }
                String recipeId = parts[1].trim();
                double probability = parts.length >= 3 ? parseProbability(parts[2], entry, logger) : 1.0;
                if (roll(probability)) {
                    addRecipeReward(result, recipeId, logger);
                }
                continue;
            }

            if (keyword.equals("VOUCHER")) {
                if (parts.length < 2) {
                    logger.warning("잘못된 VOUCHER 보상 형식: " + entry + " (예: VOUCHER:1:0.3)");
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
                if (roll(probability)) {
                    result.add(plugin.getVoucherManager().createVoucher(tier));
                }
                continue;
            }

            // 기본: 바닐라 Material:확률 형식
            String materialName = parts[0].trim();
            double probability = parts.length >= 2 ? parseProbability(parts[1], entry, logger) : 1.0;

            try {
                Material material = Material.valueOf(materialName.toUpperCase());
                if (roll(probability)) {
                    result.add(new ItemStack(material));
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

    private boolean roll(double probability) {
        return ThreadLocalRandom.current().nextDouble() < probability;
    }

    // ---------------- 서플라이드랍 상자 위치 추적 ----------------

    public void register(Location location, List<ItemStack> items) {
        chests.put(location.getBlock().getLocation(), items);
    }

    public boolean isTracked(Location location) {
        return chests.containsKey(location.getBlock().getLocation());
    }

    public List<ItemStack> consume(Location location) {
        return chests.remove(location.getBlock().getLocation());
    }
}
