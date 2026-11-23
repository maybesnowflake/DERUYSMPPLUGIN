package com.deruy.plugin.improve;

import com.deruy.plugin.DeruyPlugin;
import com.deruy.plugin.bingo.BingoCell;
import com.deruy.plugin.bingo.BingoManager;
import com.deruy.plugin.contract.ContractManager;
import com.deruy.plugin.data.DataStore;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
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

/** 1.5.1: 미검토 부분 점검에서 나온 수정 */
class Fixes151Test {
    @TempDir Path temp;
    ServerMock server; DeruyPlugin plugin; YamlConfiguration config; DataStore data;

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
        data = new DataStore(plugin);
        when(plugin.getDataStore()).thenReturn(data);
    }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    private static Object field(Object o, String name) throws Exception { Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o); }
    private static void setField(Object o, String name, Object v) throws Exception { Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); f.set(o, v); }

    @Test void bingoNeverDrawsUnobtainable() throws Exception {
        Method ob = BingoManager.class.getDeclaredMethod("obtainable", Material.class); ob.setAccessible(true);
        for (Material m : List.of(Material.BEDROCK, Material.COMMAND_BLOCK, Material.ZOMBIE_SPAWN_EGG, Material.INFESTED_STONE, Material.BARRIER, Material.REINFORCED_DEEPSLATE))
            assertFalse((boolean) ob.invoke(null, m), m.name());
        for (Material m : List.of(Material.DIAMOND, Material.CRAFTING_TABLE, Material.GOLDEN_APPLE))
            assertTrue((boolean) ob.invoke(null, m), m.name());
    }

    private BingoManager runningBingo(List<BingoCell> cells, int size) throws Exception {
        var b = new BingoManager(plugin);
        setField(b, "board", new ArrayList<>(cells));
        setField(b, "size", size);
        setField(b, "lineMilestones", new ArrayList<>(List.of(1)));
        setField(b, "running", true);
        return b;
    }

    @Test void bingoCountsCraftedItemsInInventory() throws Exception {
        var cells = new ArrayList<BingoCell>();
        for (int i = 0; i < 4; i++) cells.add(new BingoCell(BingoCell.Type.ITEM, i == 0 ? "CRAFTING_TABLE" : "BEACON"));
        var b = runningBingo(cells, 2);
        var p = server.addPlayer();
        b.setTeam(p, "red");
        p.getInventory().addItem(new ItemStack(Material.CRAFTING_TABLE));  // 조합으로 얻은 것처럼(줍기 이벤트 없음)
        Method scan = BingoManager.class.getDeclaredMethod("scanInventories"); scan.setAccessible(true);
        scan.invoke(b);
        assertTrue(b.getProgress("red").contains(0), "인벤토리에 있는 아이템도 칸 완료");
    }

    @Test void bingoShutdownKeepsStateAndDottedTeamNames() throws Exception {
        var cells = new ArrayList<BingoCell>();
        for (int i = 0; i < 4; i++) cells.add(new BingoCell(BingoCell.Type.ITEM, i == 0 ? "CRAFTING_TABLE" : "BEACON"));
        var b = runningBingo(cells, 2);
        var p = server.addPlayer();
        b.setTeam(p, "red.blue");
        b.checkItem(p, Material.CRAFTING_TABLE);
        b.shutdown();                                // 정상 종료·리로드
        assertFalse(b.isRunning());
        assertNotNull(data.section("bingo.state"), "종료해도 저장 상태 유지");
        var again = new BingoManager(plugin);
        assertTrue(again.restoreState());
        assertTrue(again.getProgress("red.blue").contains(0), "점 들어간 팀 이름도 진행도 유지");
        assertTrue(again.getProgress("red").isEmpty(), "이름이 갈라져 빈 팀이 생기지 않음");
    }

    @Test void bingoSurvivesRestart() throws Exception {
        var cells = new ArrayList<BingoCell>();
        for (int i = 0; i < 4; i++) cells.add(new BingoCell(BingoCell.Type.ITEM, i == 0 ? "CRAFTING_TABLE" : "BEACON"));
        var b = runningBingo(cells, 2);
        var p = server.addPlayer();
        b.setTeam(p, "red");
        b.checkItem(p, Material.CRAFTING_TABLE);   // 진행 → 저장
        var again = new BingoManager(plugin);        // 재시작
        assertTrue(again.restoreState());
        assertTrue(again.isRunning());
        assertEquals(4, again.getBoard().size());
        assertEquals("CRAFTING_TABLE", again.getBoard().get(0).getValue());
        assertTrue(again.getProgress("red").contains(0), "진행도 유지");
        again.stop();
        assertNull(data.section("bingo.state"), "끝나면 저장 상태 삭제");
        assertFalse(new BingoManager(plugin).restoreState());
    }

    @Test void dragonEggCannotBePistoned() {
        var l = new com.deruy.plugin.dragon.DragonListener(plugin);
        var w = server.addSimpleWorld("end");
        var piston = w.getBlockAt(0, 64, 0); var egg = w.getBlockAt(1, 64, 0);
        egg.setType(Material.DRAGON_EGG);
        var e = new org.bukkit.event.block.BlockPistonExtendEvent(piston, List.of(egg), BlockFace.EAST);
        l.onPistonExtend(e);
        assertTrue(e.isCancelled());
        var stone = w.getBlockAt(2, 64, 0); stone.setType(Material.STONE);
        var ok = new org.bukkit.event.block.BlockPistonExtendEvent(piston, List.of(stone), BlockFace.EAST);
        l.onPistonExtend(ok);
        assertFalse(ok.isCancelled());
    }

    @Test void voiceFifoWrapsGrowsAndTrims() throws Exception {
        Class<?> c = Class.forName("com.deruy.plugin.voicechat.DeruyVoicePitchPlugin$FloatFifo");
        Constructor<?> k = c.getDeclaredConstructor(); k.setAccessible(true);
        Object f = k.newInstance();
        Method add = c.getDeclaredMethod("add", float.class), poll = c.getDeclaredMethod("poll"), size = c.getDeclaredMethod("size"), drop = c.getDeclaredMethod("dropOldest", int.class);
        for (Method m : List.of(add, poll, size, drop)) m.setAccessible(true);
        for (int i = 0; i < 20000; i++) add.invoke(f, (float) i);          // 처음 크기(7680)보다 많이 → 늘어남
        assertEquals(20000, size.invoke(f));
        drop.invoke(f, 19000);
        assertEquals(19000f, (float) poll.invoke(f), "가장 오래된 것부터 버림");
        for (int i = 0; i < 10000; i++) { add.invoke(f, (float) i); poll.invoke(f); }   // 고리 한 바퀴 넘게
        assertEquals(999, size.invoke(f));
    }

    @Test void soundNamesBothFormats() {
        assertEquals("minecraft:entity.wither.spawn", DeruyPlugin.resolveSoundKey("ENTITY_WITHER_SPAWN"));
        assertEquals("entity.wither.spawn", DeruyPlugin.resolveSoundKey("entity.wither.spawn"));
        assertEquals("minecraft:item.goat_horn.sound.5", DeruyPlugin.resolveSoundKey("minecraft:item.goat_horn.sound.5"));
        assertNull(DeruyPlugin.resolveSoundKey("NOT_A_SOUND"));
        assertNull(DeruyPlugin.resolveSoundKey("  "));
    }

    @Test void contractSurvivesRestartAndExpiredIsReported() {
        config.set("risk-contracts.contracts.angler.type", "FISH");
        config.set("risk-contracts.contracts.angler.amount", 10);
        config.set("risk-contracts.contracts.angler.time-seconds", 300);
        var p = server.addPlayer();
        var cm = new ContractManager(plugin);
        assertTrue(cm.accept(p, "angler"));
        cm.stop();                                    // 서버 종료
        var again = new ContractManager(plugin);      // 재시작
        assertTrue(again.hasPending(p.getUniqueId()), "재시작 후에도 계약 유지");
        assertTrue(again.status(p).startsWith("angler FISH 0/10"));
        again.stop();
        data.set("contracts." + p.getUniqueId() + ".deadline", System.currentTimeMillis() - 1000);   // 나가 있는 동안 만료
        var later = new ContractManager(plugin);
        assertFalse(later.hasPending(p.getUniqueId()));
        p.nextMessage(); while (p.nextMessage() != null) { }
        later.join(new org.bukkit.event.player.PlayerJoinEvent(p, net.kyori.adventure.text.Component.empty()));
        assertTrue(p.nextMessage().contains("제한시간이 끝났습니다"));
    }

    @Test void contractProgressReachesDiskWithoutShutdown() throws Exception {
        config.set("risk-contracts.contracts.angler.type", "FISH");
        config.set("risk-contracts.contracts.angler.amount", 10);
        config.set("risk-contracts.contracts.angler.time-seconds", 300);
        var p = server.addPlayer();
        var cm = new ContractManager(plugin);
        assertTrue(cm.accept(p, "angler"));
        Method add = ContractManager.class.getDeclaredMethod("add", org.bukkit.entity.Player.class, String.class, double.class);
        add.setAccessible(true);
        add.invoke(cm, p, "FISH", 3.0);
        server.getScheduler().performTicks(101);       // 5초 뒤 디스크 저장 (stop 없이 = 크래시 가정)
        var onDisk = new DataStore(plugin);
        assertEquals(3.0, onDisk.section("contracts." + p.getUniqueId()).getDouble("progress"), 1e-9);
        cm.stop();
    }

    @Test void lastAttackerForgottenAfterCombatEnds() throws Exception {
        var ls = new com.deruy.plugin.lifesteal.LifeStealManager(plugin);
        var a = server.addPlayer(); var b = server.addPlayer();
        ls.tagCombat(a, b, 1);
        Thread.sleep(5);
        assertFalse(ls.isCombatTagged(a.getUniqueId()));
        assertNull(ls.getLastAttacker(a.getUniqueId()), "전투 끝나면 상대 기록 정리");
    }
}
