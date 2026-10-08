package com.deruy.plugin;
import com.deruy.plugin.lifesteal.*;
import com.deruy.plugin.lifesteal.listeners.CombatLogoutListener;
import com.deruy.plugin.role.RoleManager;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class HeartAndLogoutTest {
    ServerMock server;
    @BeforeEach void setup() { server=MockBukkit.mock(); }
    @AfterEach void cleanup() { MockBukkit.unmock(); }
    private DeruyPlugin plugin(YamlConfiguration c) {
        DeruyPlugin p=mock(DeruyPlugin.class); when(p.getConfig()).thenReturn(c);
        var audit=mock(HeartAuditLog.class); when(p.getHeartAuditLog()).thenReturn(audit);
        var serverState=mock(org.bukkit.Server.class); when(p.getServer()).thenReturn(serverState);
        var roles=mock(RoleManager.class); when(p.getRoleManager()).thenReturn(roles); return p;
    }
    @Test void adminSetAndAddRespectCapAndWriteAudit() {
        var c=new YamlConfiguration();c.set("lifesteal.heart-limits.min-hearts",5);c.set("lifesteal.heart-limits.max-hearts",13);
        var plugin=plugin(c);var manager=new LifeStealManager(plugin);var player=server.addPlayer();
        assertFalse(manager.setHearts(player,5,"TEST")); assertEquals(10,manager.getHearts(player));
        assertTrue(manager.setHearts(player,12,"ADMIN_SET")); assertTrue(manager.addHeart(player,5,"ADMIN_ADD"));
        assertEquals(13,manager.getHearts(player));
        verify(plugin.getHeartAuditLog()).record(player,12,13,"ADMIN_ADD");
        assertFalse(manager.addHeart(player,1,"ADMIN_ADD"));
    }
    @Test void withdrawalCannotReachBanFloor() {
        var c=new YamlConfiguration();c.set("lifesteal.heart-limits.min-hearts",5);var p=plugin(c);var manager=new LifeStealManager(p);var player=server.addPlayer();
        assertFalse(manager.withdrawHearts(player,5));assertEquals(10,manager.getHearts(player));
        assertTrue(manager.withdrawHearts(player,4));assertEquals(6,manager.getHearts(player));
        verify(p.getHeartAuditLog()).record(player,10,6,"WITHDRAW");
    }
    @Test void killLogoutModeRoutesOneDeathAndClearsTag() {
        var c=new YamlConfiguration();c.set("combat-tag.logout-penalty","KILL");var p=plugin(c);var manager=mock(LifeStealManager.class);when(p.getLifeStealManager()).thenReturn(manager);
        Player player=mock(Player.class);var id=java.util.UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);
        when(manager.isSystemEnabled()).thenReturn(true);when(manager.isCombatTagged(id)).thenReturn(true);
        var e=mock(PlayerQuitEvent.class);when(e.getPlayer()).thenReturn(player);
        new CombatLogoutListener(p).onQuit(e);
        verify(player).setHealth(0);verify(manager).beginLogoutDeath(id);verify(manager).endLogoutDeath(id);verify(manager).clearCombatTag(id);
    }
    @Test void noneLogoutModeLeavesHealthUntouched() {
        var c=new YamlConfiguration();c.set("combat-tag.logout-penalty","NONE");var p=plugin(c);var manager=mock(LifeStealManager.class);when(p.getLifeStealManager()).thenReturn(manager);
        Player player=mock(Player.class);var id=java.util.UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);
        when(manager.isSystemEnabled()).thenReturn(true);when(manager.isCombatTagged(id)).thenReturn(true);
        var e=mock(PlayerQuitEvent.class);when(e.getPlayer()).thenReturn(player);new CombatLogoutListener(p).onQuit(e);
        verify(player,never()).setHealth(anyDouble());verify(manager).clearCombatTag(id);
    }
    @Test void nonCombatQuitNeverKills() {
        var c=new YamlConfiguration();c.set("combat-tag.logout-penalty","KILL");var p=plugin(c);var manager=mock(LifeStealManager.class);when(p.getLifeStealManager()).thenReturn(manager);
        var player=server.addPlayer();when(manager.isSystemEnabled()).thenReturn(true);
        var e=mock(PlayerQuitEvent.class);when(e.getPlayer()).thenReturn(player);new CombatLogoutListener(p).onQuit(e);
        assertFalse(player.isDead());verify(manager,never()).beginLogoutDeath(any());
    }

    @Test void serverShutdownExemptsCombatLogout() {
        var c=new YamlConfiguration();c.set("combat-tag.logout-penalty","KILL");var p=plugin(c);
        when(p.getServer().isStopping()).thenReturn(true);
        var manager=mock(LifeStealManager.class);when(p.getLifeStealManager()).thenReturn(manager);
        Player player=mock(Player.class);var id=java.util.UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);
        var e=mock(PlayerQuitEvent.class);when(e.getPlayer()).thenReturn(player);new CombatLogoutListener(p).onQuit(e);
        verify(player,never()).setHealth(anyDouble());verify(manager).clearCombatTag(id);
    }
    @Test void chatModeBroadcastsWithoutKilling() {
        var c=new YamlConfiguration();c.set("combat-tag.logout-penalty","CHAT");var p=plugin(c);
        when(p.getMessage(eq("combat-logout"),anyString())).thenReturn("COMBAT {player}");
        var manager=mock(LifeStealManager.class);when(p.getLifeStealManager()).thenReturn(manager);
        var player=server.addPlayer();when(manager.isSystemEnabled()).thenReturn(true);when(manager.isCombatTagged(player.getUniqueId())).thenReturn(true);
        var e=mock(PlayerQuitEvent.class);when(e.getPlayer()).thenReturn(player);new CombatLogoutListener(p).onQuit(e);
        assertFalse(player.isDead());assertEquals("COMBAT "+player.getName(),player.nextMessage());
    }
}
