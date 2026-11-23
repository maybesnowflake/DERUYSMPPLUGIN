package com.deruy.plugin.randomevent;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KillVoucherTest {
    ServerMock server; RandomEventManager m;

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock();
        var plugin = mock(DeruyPlugin.class);
        var config = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/config.yml")));
        config.set("random-events.kill-voucher-items", List.of("DIAMOND:1"));
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        var reg = mock(com.deruy.plugin.supplydrop.SupplyChestRegistry.class);
        when(reg.rollRewards(any(), any())).thenAnswer(i -> List.of(new ItemStack(Material.DIAMOND)));
        when(plugin.getSupplyChestRegistry()).thenReturn(reg);
        m = new RandomEventManager(plugin);
        Field f = RandomEventManager.class.getDeclaredField("active"); f.setAccessible(true);
        f.set(m, RandomEventManager.Type.KILL_VOUCHER);
    }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    private long dropped(org.bukkit.World w) {
        return w.getEntitiesByClass(org.bukkit.entity.Item.class).stream().filter(i -> i.getItemStack().getType() == Material.DIAMOND).count();
    }

    @Test void playerKillGivesVoucher() {
        var killer = server.addPlayer(); var victim = server.addPlayer();
        victim.setKiller(killer);
        m.death(new EntityDeathEvent(victim, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.PLAYER_ATTACK).build(), new ArrayList<ItemStack>()));
        assertEquals(1, dropped(victim.getWorld()), "플레이어 처치 → 상품권");
    }

    @Test void mobKillGivesNothing() {
        var killer = server.addPlayer();
        var zombie = (org.bukkit.entity.LivingEntity) killer.getWorld().spawnEntity(killer.getLocation(), EntityType.ZOMBIE);
        var e = spy(new EntityDeathEvent(zombie, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.PLAYER_ATTACK).build(), new ArrayList<ItemStack>()));
        var z = spy(zombie); when(z.getKiller()).thenReturn(killer); when(e.getEntity()).thenReturn(z);
        m.death(e);
        assertEquals(0, dropped(killer.getWorld()), "몹 처치 → 상품권 없음");
    }
}
