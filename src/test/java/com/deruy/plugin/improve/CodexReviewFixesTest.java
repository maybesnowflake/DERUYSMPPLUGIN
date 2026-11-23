package com.deruy.plugin.improve;

import com.deruy.plugin.DeruyPlugin;
import com.deruy.plugin.bounty.BountyManager;
import com.deruy.plugin.data.DataStore;
import com.deruy.plugin.locatorbar.LocatorBarManager;
import com.deruy.plugin.question.QuestionTimeManager;
import com.deruy.plugin.randomevent.RandomEventManager;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.InputStreamReader;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Codex 리뷰(PR #5)에서 나온 5건 + 종료 시 바운티 페어 유지 */
class CodexReviewFixesTest {
    @TempDir Path temp;
    ServerMock server; DeruyPlugin plugin; YamlConfiguration config;

    @BeforeEach void setup() {
        server = MockBukkit.mock();
        var host = MockBukkit.createMockPlugin("DeruyHost");
        plugin = mock(DeruyPlugin.class);
        config = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/config.yml")));
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(temp.toFile());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getName()).thenReturn("DeruyPlugin");
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getPluginMeta()).thenReturn(host.getPluginMeta());
        when(plugin.getDescription()).thenReturn(host.getDescription());
        var data = new DataStore(plugin);
        when(plugin.getDataStore()).thenReturn(data);
    }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void infiniteStrengthRemovedOnJoinAfterEventEnded() {
        var m = new RandomEventManager(plugin);
        var p = server.addPlayer();
        p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, PotionEffect.INFINITE_DURATION, 2));
        m.join(new org.bukkit.event.player.PlayerJoinEvent(p, net.kyori.adventure.text.Component.empty()));
        assertNull(p.getPotionEffect(PotionEffectType.STRENGTH), "이벤트가 끝났으면 무한 힘 III 회수");
        p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 600, 1));
        m.join(new org.bukkit.event.player.PlayerJoinEvent(p, net.kyori.adventure.text.Component.empty()));
        assertNotNull(p.getPotionEffect(PotionEffectType.STRENGTH), "직접 마신 힘 포션은 유지");
    }

    @Test void pistonMovesPlacedTracking() throws Exception {
        var m = new RandomEventManager(plugin);
        var world = server.addSimpleWorld("w");
        var a = world.getBlockAt(0, 64, 0); var b = world.getBlockAt(1, 64, 0);
        Method mark = RandomEventManager.class.getDeclaredMethod("markPlacedForTest", org.bukkit.block.Block.class); mark.setAccessible(true);
        Method is = RandomEventManager.class.getDeclaredMethod("isPlacedForTest", org.bukkit.block.Block.class); is.setAccessible(true);
        Method move = RandomEventManager.class.getDeclaredMethod("movePlaced", List.class, BlockFace.class); move.setAccessible(true);
        mark.invoke(m, a); mark.invoke(m, b);
        move.invoke(m, List.of(a, b), BlockFace.EAST);   // 두 블록을 동쪽으로 한 칸
        assertTrue((boolean) is.invoke(m, b), "a가 b 자리로 옮겨짐 (b 기록이 지워지면 안 됨)");
        assertTrue((boolean) is.invoke(m, world.getBlockAt(2, 64, 0)), "b가 한 칸 옆으로");
        assertFalse((boolean) is.invoke(m, a), "원래 자리는 기록 없음");
    }

    @Test void shockwaveRespectsRolePvp() throws Exception {
        var roles = mock(com.deruy.plugin.role.RoleManager.class);
        when(plugin.getRoleManager()).thenReturn(roles);
        var m = new RandomEventManager(plugin);
        var attacker = server.addPlayer(); var bystander = server.addPlayer();
        var mob = attacker.getWorld().spawnEntity(attacker.getLocation(), org.bukkit.entity.EntityType.ZOMBIE);
        bystander.teleport(mob.getLocation().add(1, 0, 0));
        when(roles.isPvpAllowed(attacker)).thenReturn(true);
        when(roles.isPvpAllowed(bystander)).thenReturn(false);
        Method sw = RandomEventManager.class.getDeclaredMethod("shockwave", org.bukkit.entity.Player.class, org.bukkit.entity.Entity.class); sw.setAccessible(true);
        sw.invoke(m, attacker, mob);
        assertEquals(0, bystander.getVelocity().length(), 1e-9, "PvP 금지 대상은 충격파 영향 없음");
        when(roles.isPvpAllowed(bystander)).thenReturn(true);
        sw.invoke(m, attacker, mob);
        assertTrue(bystander.getVelocity().length() > 0, "허용이면 밀려남");
    }

    @Test void questionOnlyOneWinnerAndNoStaleReward() throws Exception {
        var reg = mock(com.deruy.plugin.supplydrop.SupplyChestRegistry.class);
        when(reg.rollRewards(any(), any())).thenReturn(List.of());
        when(plugin.getSupplyChestRegistry()).thenReturn(reg);
        var q = new QuestionTimeManager(plugin);
        Method open = QuestionTimeManager.class.getDeclaredMethod("open", String.class, List.class); open.setAccessible(true);
        open.invoke(q, "1+1?", List.of("2"));
        long id = q.currentQuestionId();
        var a = server.addPlayer(); var b = server.addPlayer();
        Method judge = QuestionTimeManager.class.getDeclaredMethod("judge", org.bukkit.entity.Player.class, String.class, long.class); judge.setAccessible(true);
        assertFalse((boolean) judge.invoke(q, a, "3", id), "오답");
        assertTrue((boolean) judge.invoke(q, a, "2", id), "첫 정답자");
        assertFalse((boolean) judge.invoke(q, b, "2", id), "동시에 맞힌 두 번째는 보상 없음");
        open.invoke(q, "2+2?", List.of("4"));
        assertFalse((boolean) judge.invoke(q, b, "4", id), "지난 문제 번호로 온 답은 무효");
        verify(reg, times(1)).rollRewards(any(), any());
        q.stop();
    }

    @Test void locatorShutdownKeepsEnabledSetting() {
        var l = new LocatorBarManager(plugin);
        l.start();
        assertTrue(config.getBoolean("locatorbar.enabled"));
        l.shutdown();
        assertFalse(l.isRunning());
        assertTrue(config.getBoolean("locatorbar.enabled"), "서버 종료로 설정이 꺼지면 안 됨");
    }

    @Test void bountyShutdownKeepsSavedPairs() {
        var bounty = new BountyManager(plugin);
        var a = server.addPlayer(); var b = server.addPlayer();
        bounty.start();
        bounty.pair(a, b);
        bounty.shutdown();
        assertEquals(2, plugin.getDataStore().loadBountyPairs().size(), "재시작해도 페어 유지");
    }
}
