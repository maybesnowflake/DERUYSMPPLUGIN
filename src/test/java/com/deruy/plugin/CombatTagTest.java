package com.deruy.plugin;
import com.deruy.plugin.lifesteal.LifeStealManager;
import com.deruy.plugin.lifesteal.listeners.DeathListener;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class CombatTagTest {
    @org.junit.jupiter.api.BeforeEach void setupServer() { org.mockbukkit.mockbukkit.MockBukkit.mock(); }
    @org.junit.jupiter.api.AfterEach void teardownServer() { org.mockbukkit.mockbukkit.MockBukkit.unmock(); }

    @Test void projectileShooterAndVictimAreTagged() {
        var plugin=mock(DeruyPlugin.class); var manager=mock(LifeStealManager.class);
        var c=new YamlConfiguration(); c.set("combat-tag.duration-seconds",15);
        when(plugin.getConfig()).thenReturn(c); when(plugin.getLifeStealManager()).thenReturn(manager); when(manager.isSystemEnabled()).thenReturn(true);
        Player attacker=mock(Player.class), victim=mock(Player.class); Arrow arrow=mock(Arrow.class);
        when(arrow.getShooter()).thenReturn(attacker);
        var event=mock(EntityDamageByEntityEvent.class); when(event.getEntity()).thenReturn(victim); when(event.getDamager()).thenReturn(arrow); when(event.getFinalDamage()).thenReturn(2.0);
        new DeathListener(plugin).onDamage(event); verify(manager).tagCombat(victim,attacker,15000L);
    }
    @Test void zeroDamageDoesNotTag() {
        var plugin=mock(DeruyPlugin.class); var manager=mock(LifeStealManager.class);
        when(plugin.getLifeStealManager()).thenReturn(manager); when(manager.isSystemEnabled()).thenReturn(true);
        var event=mock(EntityDamageByEntityEvent.class); when(event.getEntity()).thenReturn(mock(Player.class)); when(event.getFinalDamage()).thenReturn(0.0);
        new DeathListener(plugin).onDamage(event); verify(manager,never()).tagCombat(any(Player.class),any(Player.class),anyLong());
    }
    @Test void cancelledDamageIsFilteredByEventRegistration() throws Exception {
        var annotation=DeathListener.class.getMethod("onDamage",EntityDamageByEntityEvent.class).getAnnotation(org.bukkit.event.EventHandler.class);
        assertTrue(annotation.ignoreCancelled()); assertEquals(org.bukkit.event.EventPriority.MONITOR,annotation.priority());
    }
}
