package com.deruy.plugin.randomevent;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import java.io.InputStreamReader;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RandomEventBossBarTest {
    ServerMock server; DeruyPlugin plugin; YamlConfiguration config; RandomEventManager m;

    @BeforeEach void setup() {
        server = MockBukkit.mock();
        var host = MockBukkit.createMockPlugin("DeruyHost");   // 스케줄러용 실제 플러그인
        plugin = mock(DeruyPlugin.class);
        config = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/config.yml")));
        config.set("random-events.duration-seconds", 120);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getName()).thenReturn("DeruyPlugin");
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getPluginMeta()).thenReturn(host.getPluginMeta());
        when(plugin.getDescription()).thenReturn(host.getDescription());
        m = new RandomEventManager(plugin);
    }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void remainingFormat() {
        assertEquals("2:00", RandomEventManager.formatRemaining(120));
        assertEquals("0:05", RandomEventManager.formatRemaining(5));
        assertEquals("0:00", RandomEventManager.formatRemaining(-3));
    }

    @Test void barShowsDuringEventAndDisappearsAfterStop() {
        server.addPlayer();
        m.start(RandomEventManager.Type.DOUBLE_DROPS);
        assertTrue(m.barVisible(), "이벤트 중 보스바");
        var bar = m.bossBar();
        assertTrue(bar.getPlayers().size() == 1, "접속자에게 표시");
        assertTrue(bar.getTitle().contains("드롭"), bar.getTitle());
        assertTrue(bar.getTitle().contains("2:00") || bar.getTitle().contains("1:59"), bar.getTitle());
        var late = server.addPlayer();
        m.join(new org.bukkit.event.player.PlayerJoinEvent(late, net.kyori.adventure.text.Component.empty()));
        assertTrue(bar.getPlayers().contains(late), "중간 접속자도 보임");
        m.stopActive();
        assertFalse(m.barVisible(), "끝나면 제거");
        assertTrue(bar.getPlayers().isEmpty());
    }

    @Test void barCanBeDisabled() {
        config.set("random-events.bossbar", false);
        m.start(RandomEventManager.Type.TOTEM_BAN);
        assertFalse(m.barVisible());
        m.stopScheduler();
    }
}
