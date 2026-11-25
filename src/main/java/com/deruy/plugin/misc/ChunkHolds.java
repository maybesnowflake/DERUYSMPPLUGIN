package com.deruy.plugin.misc;

import org.bukkit.Chunk;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 플러그인 청크 티켓은 청크당 플러그인 하나뿐이라, 여러 기능(운송 열차·녹화 카메라)이 같은 청크를 잡으면
 * 한쪽이 놓을 때 다른 쪽 것까지 풀린다. 여기서 사용 수를 세어 처음 잡을 때만 붙이고 마지막에 놓을 때만 뗀다.
 * 서버 스레드 전용.
 */
public final class ChunkHolds {
    private record Key(UUID world, int x, int z) {}
    private final Plugin plugin;
    private final Map<Key, Integer> count = new HashMap<>();

    public ChunkHolds(Plugin plugin) { this.plugin = plugin; }

    private static Key key(Chunk c) { return new Key(c.getWorld().getUID(), c.getX(), c.getZ()); }

    public void hold(Chunk c) {
        if (count.merge(key(c), 1, Integer::sum) == 1) c.addPluginChunkTicket(plugin);
    }

    public void release(Chunk c) {
        Key k = key(c);
        Integer n = count.get(k);
        if (n == null) return;
        if (n <= 1) { count.remove(k); c.removePluginChunkTicket(plugin); }
        else count.put(k, n - 1);
    }

    public int holders(Chunk c) { return count.getOrDefault(key(c), 0); }
}
