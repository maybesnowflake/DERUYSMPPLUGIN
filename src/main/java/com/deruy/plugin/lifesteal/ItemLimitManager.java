package com.deruy.plugin.lifesteal;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/**
 * 특정 레시피의 "서버 전체 동시 존재 개수"를 제한한다.
 *
 * config.yml의 item-limits.<레시피이름> 값은 반드시 문자열이어야 한다:
 *   "0"  -> 제작 자체가 불가능 (완전 차단)
 *   "00" -> 무제한 (개수 제한 없음)
 *   그 외 숫자문자열(예: "3") -> 그 개수까지만 제작 가능
 *
 * 단순 카운터가 아니라 아이템 개체마다 고유 UUID를 부여해서 실시간으로 추적한다.
 * 파괴(용암/불/폭발/보이드/내구도소진)가 감지되면 LimitedItemDestructionListener가
 * 그 UUID를 목록에서 제거해서 개수를 정확히 유지한다.
 */
public class ItemLimitManager {

    public static final NamespacedKey INSTANCE_ID_KEY = new NamespacedKey("deruy", "limited_instance_id");

    private final DeruyPlugin plugin;

    public ItemLimitManager(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * @return -1 이면 무제한, 0이면 완전 차단, 1 이상이면 그 개수가 상한
     */
    private int resolveLimit(String recipeId) {
        String raw = plugin.getConfig().getString("item-limits." + recipeId, null);
        if (raw == null) return -1;

        String trimmed = raw.trim();
        if (trimmed.equals("0")) return 0;
        if (trimmed.equals("00")) return -1;

        try {
            return Math.max(0, Integer.parseInt(trimmed));
        } catch (NumberFormatException e) {
            plugin.getLogger().warning("item-limits." + recipeId + " 값이 잘못됨: " + raw + " (숫자, \"0\", \"00\" 중 하나여야 함)");
            return -1;
        }
    }

    public boolean isLimited(String recipeId) {
        return plugin.getConfig().isSet("item-limits." + recipeId);
    }

    public boolean canCraftMore(String recipeId) {
        int limit = resolveLimit(recipeId);
        if (limit < 0) return true;
        if (limit == 0) return false;
        return plugin.getDataStore().getLimitedInstanceCount(recipeId) < limit;
    }

    /**
     * 제작 성공시 호출. 전달된 아이템에 고유 개체 UUID를 태그해서 반환하고,
     * 그 UUID를 "살아있는 개체" 목록에 등록한다. (호출 전에 반드시 canCraftMore로 확인할 것)
     */
    public ItemStack tagAndRegisterInstance(String recipeId, ItemStack item) {
        UUID instanceId = UUID.randomUUID();
        ItemStack tagged = item.clone();
        ItemMeta meta = tagged.getItemMeta();
        meta.getPersistentDataContainer().set(INSTANCE_ID_KEY, PersistentDataType.STRING, instanceId.toString());
        tagged.setItemMeta(meta);

        plugin.getDataStore().addLimitedInstance(recipeId, instanceId);
        return tagged;
    }

    /** 이 아이템 개체가 파괴됐을 때 호출 - 추적 목록에서 제거 */
    public void markDestroyed(String recipeId, ItemStack item) {
        UUID instanceId = extractInstanceId(item);
        if (instanceId == null) return;
        plugin.getDataStore().removeLimitedInstance(recipeId, instanceId);
    }

    public static UUID extractInstanceId(ItemStack item) {
        if (item == null || item.getItemMeta() == null) return null;
        String raw = item.getItemMeta().getPersistentDataContainer().get(INSTANCE_ID_KEY, PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public int getCurrentCount(String recipeId) {
        return plugin.getDataStore().getLimitedInstanceCount(recipeId);
    }

    /** 관리자 수동 보정 - 이 레시피의 추적 목록을 통째로 비운다 (전부 파괴된 걸 확인했을 때만 사용) */
    public void clearAll(String recipeId) {
        plugin.getDataStore().clearLimitedInstances(recipeId);
    }

    public String describeLimit(String recipeId) {
        int limit = resolveLimit(recipeId);
        int current = getCurrentCount(recipeId);
        if (limit < 0) return "무제한 (현재 " + current + "개 존재)";
        if (limit == 0) return "제작불가 (0개로 설정됨)";
        return current + " / " + limit + " (실시간 추적 중)";
    }
}
