package com.deruy.plugin.lifesteal;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LifeSteal 커스텀 조합법 전담 관리자.
 * 하트/토템/메이스/황금사과 레시피 전부 config.yml (lifesteal.recipes.*) 에서 읽어와 등록한다.
 * 서버 재시작만으로 조합법 모양/재료를 자유롭게 바꿀 수 있다.
 */
public class RecipeManager {

    /** "하트" 아이템(빨간 염료 아이콘)을 식별하기 위한 PDC 키 */
    public static final NamespacedKey HEART_ITEM_KEY = new NamespacedKey("deruy", "heart_item");
    /** 모든 커스텀 레시피 결과물에 공통으로 붙는 "이 아이템이 어떤 레시피로 만들어졌는지" 태그 */
    public static final NamespacedKey RECIPE_ID_KEY = new NamespacedKey("deruy", "recipe_id");

    private final DeruyPlugin plugin;

    public RecipeManager(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    public void registerAll() {
        Bukkit.removeRecipe(NamespacedKey.minecraft("mace"));
        Bukkit.removeRecipe(NamespacedKey.minecraft("golden_apple"));

        ConfigurationSection recipesSection = plugin.getConfig().getConfigurationSection("lifesteal.recipes");
        if (recipesSection == null) {
            plugin.getLogger().warning("lifesteal.recipes 설정이 없어 커스텀 레시피를 등록하지 않습니다.");
            return;
        }

        for (String recipeName : recipesSection.getKeys(false)) {
            ConfigurationSection section = recipesSection.getConfigurationSection(recipeName);
            if (section == null) continue;
            registerFromConfig(recipeName, section);
        }
    }

    private void registerFromConfig(String recipeName, ConfigurationSection section) {
        String resultMaterialName = section.getString("result-material");
        if (resultMaterialName == null) {
            plugin.getLogger().warning("레시피 '" + recipeName + "'에 result-material이 없어 건너뜁니다.");
            return;
        }

        Material resultMaterial;
        try {
            resultMaterial = Material.valueOf(resultMaterialName.toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("레시피 '" + recipeName + "'의 result-material이 잘못됨: " + resultMaterialName);
            return;
        }

        List<String> shapeList = section.getStringList("shape");
        if (shapeList.size() != 3) {
            plugin.getLogger().warning("레시피 '" + recipeName + "'의 shape은 반드시 3줄이어야 합니다.");
            return;
        }

        ConfigurationSection ingredientsSection = section.getConfigurationSection("ingredients");
        if (ingredientsSection == null) {
            plugin.getLogger().warning("레시피 '" + recipeName + "'에 ingredients가 없습니다.");
            return;
        }

        ItemStack result = buildResultItem(recipeName, resultMaterial, section);

        NamespacedKey key = new NamespacedKey(plugin, recipeName);
        ShapedRecipe recipe = new ShapedRecipe(key, result);
        recipe.shape(shapeList.get(0), shapeList.get(1), shapeList.get(2));

        for (String symbol : ingredientsSection.getKeys(false)) {
            String materialName = ingredientsSection.getString(symbol);
            if (materialName == null) continue;
            try {
                Material ingredientMaterial = Material.valueOf(materialName.toUpperCase());
                recipe.setIngredient(symbol.charAt(0), ingredientMaterial);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("레시피 '" + recipeName + "'의 재료가 잘못됨: " + materialName);
            }
        }

        Bukkit.addRecipe(recipe);
    }

    /**
     * "heart" 레시피는 결과물이 그냥 아이템이 아니라, 우클릭시 소비되어 하트를 지급하는
     * 커스텀 아이템(빨간 염료 아이콘 + PDC 태그)이어야 하므로 별도 처리한다.
     */
    private ItemStack buildResultItem(String recipeName, Material resultMaterial, ConfigurationSection section) {
        ItemStack item;
        if (recipeName.equalsIgnoreCase("heart")) {
            String displayName = section.getString("result-name", "&c하트");
            item = createHeartItem(resultMaterial, displayName);
        } else {
            item = new ItemStack(resultMaterial);
            String displayName = section.getString("result-name");
            if (displayName != null) {
                ItemMeta meta = item.getItemMeta();
                meta.setDisplayName(org.bukkit.ChatColor.translateAlternateColorCodes('&', displayName));
                item.setItemMeta(meta);
            }
        }

        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(RECIPE_ID_KEY, PersistentDataType.STRING, recipeName);
        item.setItemMeta(meta);
        return item;
    }

    /** 이 아이템이 어떤 레시피로 만들어졌는지 (없으면 null) */
    public static String getRecipeId(ItemStack item) {
        if (item == null || item.getItemMeta() == null) return null;
        return item.getItemMeta().getPersistentDataContainer().get(RECIPE_ID_KEY, PersistentDataType.STRING);
    }

    /**
     * lifesteal.recipes.<recipeId> 설정을 기반으로 그 레시피의 결과물을 직접 생성한다
     * (조합대 없이 즉시 지급할 때 사용, 예: 보상시스템). 등록 안 된 레시피면 null.
     */
    public ItemStack createItemForRecipe(String recipeId) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("lifesteal.recipes." + recipeId);
        if (section == null) return null;

        String materialName = section.getString("result-material");
        if (materialName == null) return null;

        Material material;
        try {
            material = Material.valueOf(materialName.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }

        return buildResultItem(recipeId, material, section);
    }

    /** 하트 아이템을 생성한다 (레시피 결과물, 자연사/오버캡 드롭 양쪽에서 재사용). */
    public ItemStack createHeartItem() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("lifesteal.recipes.heart");
        String materialName = section != null ? section.getString("result-material", "RED_DYE") : "RED_DYE";
        String displayName = section != null ? section.getString("result-name", "&c하트") : "&c하트";

        Material material;
        try {
            material = Material.valueOf(materialName.toUpperCase());
        } catch (IllegalArgumentException e) {
            material = Material.RED_DYE;
        }
        return createHeartItem(material, displayName);
    }

    private ItemStack createHeartItem(Material material, String displayName) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(org.bukkit.ChatColor.translateAlternateColorCodes('&', displayName));
        meta.setLore(List.of("§7우클릭하여 하트 1개를 획득합니다."));
        meta.getPersistentDataContainer().set(HEART_ITEM_KEY, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public static boolean isHeartItem(ItemStack item) {
        if (item == null || item.getItemMeta() == null) return false;
        return item.getItemMeta().getPersistentDataContainer().has(HEART_ITEM_KEY, PersistentDataType.BYTE);
    }

    // ---------------- 인게임 커맨드로 레시피 재료 변경 (런타임 임시 변경, 영구 반영은 config.yml 직접수정) ----------------

    public boolean addIngredientToRecipe(String recipeName, char slot, Material material) {
        NamespacedKey key = new NamespacedKey(plugin, recipeName);
        var existing = Bukkit.getRecipe(key);
        if (!(existing instanceof ShapedRecipe shaped)) {
            return false;
        }

        Bukkit.removeRecipe(key);
        ShapedRecipe updated = new ShapedRecipe(key, shaped.getResult());
        updated.shape(shaped.getShape());

        Map<Character, ItemStack> ingredients = shaped.getIngredientMap();
        for (Map.Entry<Character, ItemStack> entry : ingredients.entrySet()) {
            ItemStack ingredient = entry.getValue();
            if (ingredient != null) {
                updated.setIngredient(entry.getKey(), ingredient.getType());
            }
        }
        updated.setIngredient(slot, material);
        Bukkit.addRecipe(updated);
        return true;
    }

    public Set<String> getRegisteredRecipeNames() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("lifesteal.recipes");
        return section != null ? section.getKeys(false) : Set.of();
    }
}
