package com.deruy.plugin;
import com.deruy.plugin.data.DataStore;
import com.deruy.plugin.lifesteal.*;
import com.deruy.plugin.lifesteal.listeners.ItemLimitListener;
import com.deruy.plugin.supplydrop.SupplyDropManager;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import java.io.*;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BehaviorTest {
    @TempDir Path temp;
    ServerMock server;
    DeruyPlugin plugin;
    YamlConfiguration config;
    ItemLimitManager limits;
    @BeforeEach void setup() {
        server=MockBukkit.mock();
        plugin=mock(DeruyPlugin.class);
        config=YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/config.yml")));
        when(plugin.getConfig()).thenReturn(config); when(plugin.getDataFolder()).thenReturn(temp.toFile());
        when(plugin.getName()).thenReturn("DeruyPlugin"); when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getServer()).thenReturn(server); when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        var data=new DataStore(plugin); when(plugin.getDataStore()).thenReturn(data);
        limits=new ItemLimitManager(plugin); when(plugin.getItemLimitManager()).thenReturn(limits);
        when(plugin.getRecipeManager()).thenReturn(new RecipeManager(plugin));
    }
    private ItemStack ingredient() {
        // MockBukkit has no crafting-remainder implementation; DIAMOND has no remainder.
        var item=spy(new ItemStack(Material.DIAMOND,10));
        var material=mock(Material.class); when(material.isAir()).thenReturn(false);
        doReturn(material).when(item).getType(); return item;
    }
    @AfterEach void cleanup() { MockBukkit.unmock(); }
    @Test void shiftCraftStopsAtCapAndGivesDistinctIds() {
        var player=server.addPlayer();
        var matrix=new ItemStack[]{ingredient(),null,null,null,null,null,null,null,null};
        var inv=mock(CraftingInventory.class); when(inv.getMatrix()).thenReturn(matrix);
        var recipe=mock(CraftingRecipe.class); when(recipe.getKey()).thenReturn(new NamespacedKey("deruyplugin","custom_netherite_sword"));
        ItemStack template=plugin.getRecipeManager().createItemForRecipe("custom_netherite_sword"); when(recipe.getResult()).thenReturn(template);
        var event=mock(CraftItemEvent.class); when(event.getRecipe()).thenReturn(recipe); when(event.getInventory()).thenReturn(inv);
        when(event.getWhoClicked()).thenReturn(player); when(event.getClick()).thenReturn(ClickType.SHIFT_LEFT); when(event.isShiftClick()).thenReturn(true);
        when(event.getCurrentItem()).thenReturn(template);
        new ItemLimitListener(plugin).onCraft(event);
        assertEquals(2,limits.getCurrentCount("custom_netherite_sword")); assertEquals(8,matrix[0].getAmount());
        var ids=java.util.Arrays.stream(player.getInventory().getStorageContents()).filter(java.util.Objects::nonNull).map(ItemLimitManager::extractInstanceId).toList();
        assertEquals(2,ids.size()); assertNotNull(ids.get(0)); assertNotEquals(ids.get(0),ids.get(1));
        verify(event).setCancelled(true);
    }
    @Test void fullInventoryConsumesNoMaterialsOrInstances() {
        var player=server.addPlayer(); for(int i=0;i<36;i++) player.getInventory().setItem(i,new ItemStack(Material.STONE,64));
        var matrix=new ItemStack[]{ingredient(),null,null,null};
        var inv=mock(CraftingInventory.class); when(inv.getMatrix()).thenReturn(matrix);
        var recipe=mock(CraftingRecipe.class); when(recipe.getKey()).thenReturn(new NamespacedKey("deruyplugin","custom_netherite_sword"));
        ItemStack template=plugin.getRecipeManager().createItemForRecipe("custom_netherite_sword"); when(recipe.getResult()).thenReturn(template);
        var event=mock(CraftItemEvent.class); when(event.getRecipe()).thenReturn(recipe); when(event.getInventory()).thenReturn(inv);
        when(event.getWhoClicked()).thenReturn(player); when(event.getClick()).thenReturn(ClickType.SHIFT_LEFT); when(event.isShiftClick()).thenReturn(true); when(event.getCurrentItem()).thenReturn(template);
        new ItemLimitListener(plugin).onCraft(event);
        assertEquals(0,limits.getCurrentCount("custom_netherite_sword")); assertEquals(10,matrix[0].getAmount());
    }
    @Test void stopCancelsForcedDropsEvenWhenSchedulerNotStarted() {
        config.set("supplydrop.normal.count-min",5);config.set("supplydrop.normal.count-max",5);
        var manager=new SupplyDropManager(plugin); manager.triggerDrop();
        assertEquals(5,manager.getPendingDropCount()); manager.stop(); assertEquals(0,manager.getPendingDropCount());
    }
    @Test void regularUserCannotStartBingo() {
        var sender=mock(org.bukkit.command.CommandSender.class); when(sender.hasPermission("deruy.bingo.view")).thenReturn(true);
        var manager=mock(com.deruy.plugin.bingo.BingoManager.class); when(plugin.getBingoManager()).thenReturn(manager);
        new com.deruy.plugin.bingo.commands.BingoCommand(plugin).onCommand(sender,null,"bingo",new String[]{"start"});
        verify(manager,never()).start();
    }
    @Test void regularUserCanListEvents() {
        var sender=mock(org.bukkit.command.CommandSender.class); when(sender.hasPermission("deruy.event.view")).thenReturn(true);
        var manager=new com.deruy.plugin.events.EventManager(); when(plugin.getEventManager()).thenReturn(manager);
        var e=mock(com.deruy.plugin.events.GameEvent.class); when(e.getName()).thenReturn("bingo"); when(e.isRunning()).thenReturn(true);manager.register(e);
        new com.deruy.plugin.events.commands.DeruyEventCommand(plugin).onCommand(sender,null,"devent",new String[]{"list"});
        verify(sender).sendMessage(contains("bingo")); verify(e,never()).start();
    }

    @Test void offlineReviveSurvivesDataReload() {
        var id=java.util.UUID.randomUUID(); plugin.getDataStore().queueHeartRestore(id,10);
        var reloaded=new DataStore(plugin);assertEquals(10,reloaded.getHeartRestore(id));
        reloaded.clearHeartRestore(id);assertNull(new DataStore(plugin).getHeartRestore(id));
    }
    @Test void protectedSupplyChestCannotBeBroken() {
        var world=server.addSimpleWorld("world");var block=world.getBlockAt(0,65,0);block.setType(Material.CHEST);
        var registry=new com.deruy.plugin.supplydrop.SupplyChestRegistry(plugin);when(plugin.getSupplyChestRegistry()).thenReturn(registry);
        registry.register(block.getLocation(),java.util.List.of(new ItemStack(Material.DIAMOND)));
        var event=new org.bukkit.event.block.BlockBreakEvent(block,server.addPlayer());
        new com.deruy.plugin.supplydrop.SupplyChestProtectionListener(plugin).onBreak(event);
        assertTrue(event.isCancelled());assertTrue(registry.isTracked(block.getLocation()));
    }
    @Test void dropModeClearsChestAndRegistry() {
        config.set("supplydrop.chest-destruction","DROP");
        var world=server.addSimpleWorld("world");var block=world.getBlockAt(0,65,0);block.setType(Material.CHEST);
        var registry=new com.deruy.plugin.supplydrop.SupplyChestRegistry(plugin);when(plugin.getSupplyChestRegistry()).thenReturn(registry);
        registry.register(block.getLocation(),java.util.List.of(new ItemStack(Material.DIAMOND)));
        var event=new org.bukkit.event.block.BlockBreakEvent(block,server.addPlayer());
        new com.deruy.plugin.supplydrop.SupplyChestProtectionListener(plugin).onBreakDrop(event);
        assertFalse(registry.isTracked(block.getLocation()));assertEquals(Material.AIR,block.getType());
        assertTrue(new DataStore(plugin).loadAllSupplyChests().isEmpty());
    }
    @Test void hopperCannotExtractFromSupplyChest() {
        var world=server.addSimpleWorld("world");var block=world.getBlockAt(0,65,0);block.setType(Material.CHEST);
        var registry=new com.deruy.plugin.supplydrop.SupplyChestRegistry(plugin);when(plugin.getSupplyChestRegistry()).thenReturn(registry);
        registry.register(block.getLocation(),java.util.List.of());
        var source=mock(Inventory.class);when(source.getLocation()).thenReturn(block.getLocation());
        var event=mock(InventoryMoveItemEvent.class);when(event.getSource()).thenReturn(source);
        new com.deruy.plugin.supplydrop.SupplyChestProtectionListener(plugin).onHopper(event);
        verify(event).setCancelled(true);
    }
}
