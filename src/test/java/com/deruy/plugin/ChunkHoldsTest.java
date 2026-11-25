package com.deruy.plugin;

import com.deruy.plugin.misc.ChunkHolds;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import static org.junit.jupiter.api.Assertions.*;

class ChunkHoldsTest {
    @AfterEach void cleanup() { MockBukkit.unmock(); }
    @Test void ticketStaysUntilLastHolderReleases() {
        var server = MockBukkit.mock();
        var plugin = MockBukkit.createMockPlugin();
        var world = server.addSimpleWorld("w");
        var chunk = world.getChunkAt(3, 4);
        var holds = new ChunkHolds(plugin);
        holds.hold(chunk);                 // 운송 열차
        holds.hold(world.getChunkAt(3, 4)); // 카메라 (같은 청크, 다른 객체)
        assertEquals(2, holds.holders(chunk));
        assertTrue(chunk.getPluginChunkTickets().contains(plugin));
        holds.release(chunk);
        assertTrue(chunk.getPluginChunkTickets().contains(plugin), "한쪽이 놓아도 유지");
        holds.release(chunk);
        assertFalse(chunk.getPluginChunkTickets().contains(plugin), "마지막이 놓으면 해제");
        holds.release(chunk);               // 더 놓아도 문제없음
        assertEquals(0, holds.holders(chunk));
    }
}
