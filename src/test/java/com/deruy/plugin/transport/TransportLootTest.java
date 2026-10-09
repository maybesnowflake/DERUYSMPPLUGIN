package com.deruy.plugin.transport;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class TransportLootTest {
 ServerMock server;World world;TransportLoot loot;
 @BeforeEach void setup(){server=MockBukkit.mock();world=server.addSimpleWorld("loot");loot=new TransportLoot(null);}
 @AfterEach void cleanup(){MockBukkit.unmock();}
 Item item(String group,long merge){Item i=world.dropItem(new Location(world,0,64,0),new ItemStack(Material.DIAMOND));if(group!=null){i.getPersistentDataContainer().set(TransportLoot.GROUPS,PersistentDataType.STRING,group);i.getPersistentDataContainer().set(TransportLoot.MERGE_AT,PersistentDataType.LONG,merge);}return i;}
 @Test void splittingPreservesCustomItemDataAndOriginalStack(){var stack=new ItemStack(Material.PAPER,3);var meta=stack.getItemMeta();meta.getPersistentDataContainer().set(new NamespacedKey("deruy","heart"),PersistentDataType.INTEGER,7);stack.setItemMeta(meta);var split=TransportLoot.split(List.of(stack),512);assertEquals(3,split.size());assertEquals(3,stack.getAmount());for(var one:split){assertEquals(1,one.getAmount());assertEquals(7,one.getItemMeta().getPersistentDataContainer().get(new NamespacedKey("deruy","heart"),PersistentDataType.INTEGER));}split.getFirst().setAmount(2);assertEquals(1,split.get(1).getAmount());}
 @Test void capPreventsExcessiveEntities(){assertThrows(IllegalArgumentException.class,()->TransportLoot.split(List.of(new ItemStack(Material.DIAMOND,513)),512));assertThrows(IllegalArgumentException.class,()->TransportLoot.split(List.of(),512));}
 @Test void mergingWaitsForBothStacks(){assertFalse(TransportLoot.mergeAllowed("raiders","raiders",100,200,150));assertTrue(TransportLoot.mergeAllowed("raiders","raiders",100,200,200));assertFalse(TransportLoot.mergeAllowed("raiders","other",100,100,200));}
 @Test void taggedLootCannotMergeIntoUnrestrictedItem(){var event=new ItemMergeEvent(item("raiders",0),item(null,0));loot.onMerge(event);assertTrue(event.isCancelled());}
 @Test void taggedLootCanMergeAfterDelay(){var event=new ItemMergeEvent(item("raiders",0),item("raiders",0));loot.onMerge(event);assertFalse(event.isCancelled());}
 @Test void taggedLootCannotMergeDuringBurst(){var event=new ItemMergeEvent(item("raiders",Long.MAX_VALUE),item("raiders",Long.MAX_VALUE));loot.onMerge(event);assertTrue(event.isCancelled());}
 @Test void hopperCannotBypassGroupRestriction(){var event=new InventoryPickupItemEvent(mock(Inventory.class),item("raiders",0));loot.onHopper(event);assertTrue(event.isCancelled());}
 @Test void mobCannotPickUpRestrictedLoot(){var event=new EntityPickupItemEvent(mock(LivingEntity.class),item("raiders",0),0);loot.onPickup(event);assertTrue(event.isCancelled());}
 @Test void differentAttackerSetsCannotMerge(){var a=item("raiders",0);var b=item("raiders",0);a.getPersistentDataContainer().set(TransportLoot.PARTICIPANTS,PersistentDataType.STRING,"player-one");b.getPersistentDataContainer().set(TransportLoot.PARTICIPANTS,PersistentDataType.STRING,"player-two");var event=new ItemMergeEvent(a,b);loot.onMerge(event);assertTrue(event.isCancelled());}
 @Test void nonAttackerCannotPickUp(){var drop=item("raiders",0);drop.getPersistentDataContainer().set(TransportLoot.PARTICIPANTS,PersistentDataType.STRING,UUID.randomUUID().toString());var player=mock(Player.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());var event=new EntityPickupItemEvent(player,drop,0);loot.onPickup(event);assertTrue(event.isCancelled());}
 @Test void ordinaryLootIsUnchanged(){var event=new InventoryPickupItemEvent(mock(Inventory.class),item(null,0));loot.onHopper(event);assertFalse(event.isCancelled());}
}
