package com.deruy.plugin;

import com.deruy.plugin.data.DataStore;
import com.deruy.plugin.lifesteal.ItemLimitManager;
import com.deruy.plugin.lifesteal.RecipeManager;
import com.deruy.plugin.lifesteal.listeners.LimitedItemDestructionListener;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.InputStreamReader;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LimitedItemDestructionTest {
    @TempDir Path temp;
    ServerMock server;
    DeruyPlugin plugin;
    ItemLimitManager limits;

    @BeforeEach void setup() {
        server = MockBukkit.mock();
        plugin = mock(DeruyPlugin.class);
        var config = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/config.yml")));
        when(plugin.getConfig()).thenReturn(config); when(plugin.getDataFolder()).thenReturn(temp.toFile());
        when(plugin.getName()).thenReturn("DeruyPlugin"); when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        var data = new DataStore(plugin); when(plugin.getDataStore()).thenReturn(data);
        limits = new ItemLimitManager(plugin); when(plugin.getItemLimitManager()).thenReturn(limits);
        when(plugin.getRecipeManager()).thenReturn(new RecipeManager(plugin));
    }
    @AfterEach void cleanup() { MockBukkit.unmock(); }

    private ItemStack limitedSword() {
        return limits.tagAndRegisterInstance("custom_netherite_sword",
                plugin.getRecipeManager().createItemForRecipe("custom_netherite_sword"));
    }

    @Test void voidRemovalFreesTheSlot() {
        var sword = limitedSword();
        assertEquals(1, limits.getCurrentCount("custom_netherite_sword"));
        Item entity = server.addSimpleWorld("w").dropItem(new org.bukkit.Location(server.getWorld("w"), 0, 64, 0), sword);
        var listener = new LimitedItemDestructionListener(plugin);
        listener.onItemRemoved(new EntityRemoveEvent(entity, EntityRemoveEvent.Cause.PICKUP));
        assertEquals(1, limits.getCurrentCount("custom_netherite_sword"), "pickup is not destruction");
        listener.onItemRemoved(new EntityRemoveEvent(entity, EntityRemoveEvent.Cause.OUT_OF_WORLD));
        assertEquals(0, limits.getCurrentCount("custom_netherite_sword"));
    }

    @Test void shulkerContentsAreFreedWithTheBox() {
        var sword = limitedSword();
        var box = new ItemStack(Material.SHULKER_BOX);
        var meta = (BlockStateMeta) box.getItemMeta();
        var state = (ShulkerBox) meta.getBlockState();
        state.getInventory().setItem(3, sword);
        meta.setBlockState(state); box.setItemMeta(meta);
        Item entity = server.addSimpleWorld("w").dropItem(new org.bukkit.Location(server.getWorld("w"), 0, 64, 0), box);
        new LimitedItemDestructionListener(plugin).onItemRemoved(new EntityRemoveEvent(entity, EntityRemoveEvent.Cause.DEATH));
        assertEquals(0, limits.getCurrentCount("custom_netherite_sword"));
    }
}
