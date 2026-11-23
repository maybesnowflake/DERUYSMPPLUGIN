package com.deruy.plugin.improve;

import com.deruy.plugin.lifesteal.listeners.VillagerTradeListener;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VillagerTradeTest {
    @BeforeEach void setup() { MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void refreshResetsUsesAndDemandButKeepsMaxUses() {
        var r = new MerchantRecipe(new ItemStack(Material.EMERALD), 12, 12, true, 1, 0.05f, 7, 0);
        r.addIngredient(new ItemStack(Material.PAPER, 24));
        assertEquals(12, r.getUses());
        VillagerTradeListener.refresh(List.of(r));
        assertEquals(0, r.getUses(), "잠긴 거래가 다시 열림");
        assertEquals(0, r.getDemand(), "수요 누적으로 값이 오르지 않음");
        assertEquals(12, r.getMaxUses(), "최대 횟수는 그대로 (끄면 바로 원래대로)");
    }
}
