package com.deruy.plugin.improve;

import com.deruy.plugin.DeruyPlugin;
import com.deruy.plugin.bounty.BountyManager;
import com.deruy.plugin.data.DataStore;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImprovementsTest {
    @TempDir Path temp;
    ServerMock server;
    DeruyPlugin plugin;

    @BeforeEach void setup() {
        server = MockBukkit.mock();
        plugin = mock(DeruyPlugin.class);
        var config = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/config.yml")));
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(temp.toFile());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        var data = new DataStore(plugin);
        when(plugin.getDataStore()).thenReturn(data);
    }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    private static Object call(Class<?> c, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = c.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(null, args);
    }

    @Test void kothOnlyAutoStartsRightAfterScheduledTime() throws Exception {
        Class<?> k = com.deruy.plugin.events.koth.KothManager.class;
        Class<?>[] t = {LocalTime.class, LocalTime.class};
        LocalTime target = LocalTime.of(20, 0);
        assertFalse((boolean) call(k, "withinStartWindow", t, LocalTime.of(19, 59, 50), target));
        assertTrue((boolean) call(k, "withinStartWindow", t, LocalTime.of(20, 0, 10), target));
        assertTrue((boolean) call(k, "withinStartWindow", t, LocalTime.of(20, 1, 50), target));
        assertFalse((boolean) call(k, "withinStartWindow", t, LocalTime.of(22, 30), target), "서버를 늦게 켜면 지난 KOTH는 시작하지 않음");
    }

    @Test void kothIgnoresSpectatorsAndCreative() throws Exception {
        Class<?> k = com.deruy.plugin.events.koth.KothManager.class;
        Class<?>[] t = {org.bukkit.entity.Player.class};
        var p = server.addPlayer();
        p.setGameMode(GameMode.SURVIVAL);
        assertTrue((boolean) call(k, "canCapture", t, p));
        p.setGameMode(GameMode.SPECTATOR);
        assertFalse((boolean) call(k, "canCapture", t, p));
        p.setGameMode(GameMode.CREATIVE);
        assertFalse((boolean) call(k, "canCapture", t, p));
    }

    @SuppressWarnings("unchecked")
    @Test void supplyDropNeverSchedulesPastTimes() throws Exception {
        Class<?> s = com.deruy.plugin.supplydrop.SupplyDropManager.class;
        Class<?>[] t = {int.class, int.class, int.class, LocalTime.class, Random.class};
        Random r = new Random(3);
        LocalTime now = LocalTime.of(20, 30);
        var list = (List<LocalTime>) call(s, "rollTargets", t, 15, 24, 50, now, r);
        assertEquals(50, list.size());
        assertTrue(list.stream().allMatch(x -> x.isAfter(now)), "켠 시각 이후만");
        var morning = (List<LocalTime>) call(s, "rollTargets", t, 15, 24, 50, LocalTime.of(9, 0), r);
        assertTrue(morning.stream().allMatch(x -> !x.isBefore(LocalTime.of(15, 0))), "창 시작 이후만");
        var late = (List<LocalTime>) call(s, "rollTargets", t, 15, 22, 3, LocalTime.of(23, 0), r);
        assertTrue(late.isEmpty(), "오늘 창이 끝났으면 내일로");
    }

    @Test void bountyRepairClearsOldPartnerAndStopClearsSaved() {
        var bounty = new BountyManager(plugin);
        when(plugin.getBountyManager()).thenReturn(bounty);
        var a = server.addPlayer(); var b = server.addPlayer(); var c = server.addPlayer();
        bounty.pair(a, b);
        bounty.pair(a, c);
        assertEquals(c.getUniqueId(), bounty.getTarget(a));
        assertNull(bounty.getTarget(b), "예전 짝 b에게 a가 타겟으로 남으면 안 됨");
        bounty.start();
        bounty.stop();
        assertTrue(plugin.getDataStore().loadBountyPairs().isEmpty(), "끝난 바운티 페어가 재시작 후 되살아나면 안 됨");
    }
}
