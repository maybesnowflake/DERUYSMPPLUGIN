package com.deruy.plugin.timeline;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TimelineTest {
    @TempDir Path temp;
    ServerMock server; DeruyPlugin plugin; YamlConfiguration config;

    @BeforeEach void setup() {
        server = MockBukkit.mock();
        plugin = mock(DeruyPlugin.class);
        config = new YamlConfiguration();
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
    }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void csvEscaping() {
        assertEquals("abc", Timeline.csv("abc"));
        assertEquals("\"a,b\"", Timeline.csv("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", Timeline.csv("say \"hi\""));
        assertEquals("\"줄\n바꿈\"", Timeline.csv("줄\n바꿈"));
        assertEquals("'=1+1", Timeline.csv("=1+1"), "엑셀 수식 실행 방지");
        assertEquals("", Timeline.csv(null));
    }

    @Test void lineHasSecondsAndLocation() {
        var world = server.addSimpleWorld("world");
        String l = Timeline.line(LocalDateTime.of(2026, 11, 23, 21, 5, 9), "KILL", "A → B", "NETHERITE_SWORD", new org.bukkit.Location(world, 10.7, 64, -3.2));
        assertEquals("2026-11-23 21:05:09,KILL,A → B,NETHERITE_SWORD,world,10,64,-4\n", l);
        assertEquals("2026-11-23 21:05:09,KOTH_START,KOTH,a b,,,,\n", Timeline.line(LocalDateTime.of(2026, 11, 23, 21, 5, 9), "KOTH_START", "KOTH", "a b", null));
    }

    @Test void writesInOrderWithBomHeaderAndCounts() throws Exception {
        var t = new Timeline(plugin, temp.resolve("timeline"));
        for (int i = 0; i < 50; i++) t.record("TEST", "p" + i, "n," + i, null);
        t.flush();
        Path f = t.fileFor(LocalDate.now());
        byte[] bytes = Files.readAllBytes(f);
        assertEquals((byte) 0xEF, bytes[0], "UTF-8 BOM (엑셀 한글)");
        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        assertEquals("﻿" + Timeline.HEADER, lines.get(0));
        assertEquals(51, lines.size());
        for (int i = 0; i < 50; i++) assertTrue(lines.get(i + 1).contains(",p" + i + ",\"n," + i + "\""), "순서대로: " + lines.get(i + 1));
        assertEquals(50, t.count(LocalDate.now()));
        t.close();
    }

    @Test void disabledWritesNothing() {
        config.set("timeline.enabled", false);
        var t = new Timeline(plugin, temp.resolve("off"));
        t.record("TEST", "x", "", null);
        t.flush();
        assertEquals(0, t.count(LocalDate.now()));
        t.close();
    }

    @Test void logWithoutTimelineIsSafe() {
        Timeline.log(plugin, "TEST", "x", "", null);   // getTimeline() == null
        Timeline.log(null, "TEST", "x", "", null);
    }
}
