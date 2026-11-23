package com.deruy.plugin.randomevent;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class EventRouletteTest {
    ServerMock server;
    YamlConfiguration config;

    @BeforeEach void setup() {
        server = MockBukkit.mock();
    }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    private List<String> run(String style) {
        var mockPlugin = org.mockito.Mockito.mock(DeruyPlugin.class);
        config = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/config.yml")));
        config.set("random-events.roulette-style", style);
        config.set("random-events.roulette-seconds", 2);
        org.mockito.Mockito.when(mockPlugin.getConfig()).thenReturn(config);
        List<String> warnings = new ArrayList<>();
        var log = java.util.logging.Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        log.addHandler(new java.util.logging.Handler() {
            public void publish(java.util.logging.LogRecord r) { warnings.add(r.getMessage()); }
            public void flush() {} public void close() {}
        });
        org.mockito.Mockito.when(mockPlugin.getLogger()).thenReturn(log);
        org.mockito.Mockito.when(mockPlugin.isEnabled()).thenReturn(true);
        org.mockito.Mockito.when(mockPlugin.getName()).thenReturn("DeruyPlugin");
        org.mockito.Mockito.when(mockPlugin.getServer()).thenReturn(server);

        server.addPlayer(); server.addPlayer();
        List<RandomEventManager.Type> pool = List.of(RandomEventManager.Type.values());
        AtomicReference<RandomEventManager.Type> got = new AtomicReference<>();
        // 스케줄러에는 실제 플러그인 객체가 필요하므로 프레임은 수동으로 돌린다.
        var roulette = new EventRoulette(mockPlugin, pool, RandomEventManager.Type.LIFESTEAL, got::set);
        try {
            var frame = EventRoulette.class.getDeclaredMethod("frame"); frame.setAccessible(true);
            for (int i = 0; i < 40 + 40 + 5 && got.get() == null; i++) frame.invoke(roulette);
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        assertEquals(RandomEventManager.Type.LIFESTEAL, got.get(), "당첨 콜백");
        long left = server.getWorlds().stream().flatMap(w -> w.getEntitiesByClass(Display.class).stream())
                .filter(e -> e.getScoreboardTags().contains("deruy_event_roulette")).count();
        assertEquals(0, left, "룰렛 디스플레이가 남으면 안 됨");
        assertTrue(server.getOnlinePlayers().stream().noneMatch(p -> p.getOpenInventory().getTopInventory() != null && p.getOpenInventory().getTopInventory().getHolder() instanceof EventRoulette.Gui), "추첨 화면이 닫혀야 함");
        return warnings;
    }

    @Test void guiRunsToWinnerAndCleansUp() {
        assertEquals(List.of(), run("GUI"), "GUI 추첨은 오류 없이 끝까지 돌아야 함");
    }

    /** MockBukkit은 엔티티 보이기 설정을 지원하지 않아 카메라 화면은 실제 서버에서 확인. 여기선 오류가 나도 당첨이 진행되고 정리되는지만. */
    @Test void cameraFailureStillDeliversWinnerAndCleansUp() { run("CAMERA"); }
}
