package com.deruy.plugin.timeline;

import com.deruy.plugin.DeruyPlugin;
import com.deruy.plugin.misc.commands.DeruyCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeruyCommandTest {
    DeruyPlugin plugin;

    @BeforeEach void setup() {
        MockBukkit.mock();
        plugin = mock(DeruyPlugin.class, RETURNS_DEEP_STUBS);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        var koth = mock(com.deruy.plugin.events.koth.KothManager.class);
        when(koth.getName()).thenReturn("koth"); when(koth.isRunning()).thenReturn(true); when(koth.isEnabled()).thenReturn(true);
        when(koth.getActiveZoneIds()).thenReturn(new LinkedHashSet<>(List.of("a", "b")));
        var superKoth = mock(com.deruy.plugin.events.koth.KothManager.class);
        when(superKoth.getName()).thenReturn("superkoth");
        when(plugin.getKothManager()).thenReturn(koth);
        when(plugin.getSuperKothManager()).thenReturn(superKoth);
        when(plugin.getBountyManager().getGlobalTarget()).thenReturn(null);
        when(plugin.getBountyManager().getDesignatedPursuer()).thenReturn(null);
        when(plugin.getRandomEventManager().getActive()).thenReturn(null);
        when(plugin.getTransportManager().status()).thenReturn("대기 중");
    }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    private List<String> run(boolean admin, String... args) {
        List<String> got = new ArrayList<>();
        CommandSender s = mock(CommandSender.class);
        when(s.hasPermission("deruy.admin")).thenReturn(admin);
        doAnswer(i -> { got.add(i.getArgument(0)); return null; }).when(s).sendMessage(anyString());
        new DeruyCommand(plugin).onCommand(s, mock(Command.class), "deruy", args);
        return got;
    }

    @Test void statusShowsEverySystem() {
        String all = String.join("\n", run(true, "status"));
        for (String part : List.of("라이프스틸", "koth", "superkoth", "바운티", "서플라이드랍", "슈퍼", "랜덤 이벤트", "하트 운송전", "질문타임", "빙고", "로케이터바", "타임라인"))
            assertTrue(all.contains(part), "상태에 " + part + " 없음\n" + all);
        assertTrue(all.contains("(a, b)"), "진행 중 KOTH 구역 표시");
    }

    @Test void deniedWithoutPermission() {
        assertEquals(List.of("§c권한이 없습니다."), run(false, "status"));
        CommandSender s = mock(CommandSender.class);
        assertTrue(new DeruyCommand(plugin).onTabComplete(s, mock(Command.class), "deruy", new String[]{""}).isEmpty());
    }

    @Test void tabComplete() {
        CommandSender s = mock(CommandSender.class);
        when(s.hasPermission("deruy.admin")).thenReturn(true);
        assertEquals(List.of("timeline"), new DeruyCommand(plugin).onTabComplete(s, mock(Command.class), "deruy", new String[]{"ti"}));
    }
}
