package com.deruy.plugin.replay;

import com.deruy.plugin.replay.buffer.ChunkStore;
import com.deruy.plugin.replay.buffer.PlayerBuffer;
import com.deruy.plugin.replay.buffer.Segment;
import com.deruy.plugin.replay.format.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ReplayFormatTest {
    @TempDir Path dir;

    static byte[] packet(int id, int size) {
        FbOut o = new FbOut(); o.writeVarInt(id); for (int i = 0; i < size; i++) o.writeByte(i * 7 + id); return o.toByteArray();
    }

    /** 스냅샷: 설정 패킷 1 + 로컬 플레이어 + 청크 참조 */
    static byte[] snapshot(String chunkHash) {
        FbOut s = new FbOut();
        FlashbackFormat.action(s, FlashbackFormat.ID_CONFIGURATION_PACKET, packet(7, 20));
        FlashbackFormat.action(s, FlashbackFormat.ID_GAME_PACKET, packet(43, 30));
        FlashbackFormat.action(s, FlashbackFormat.ID_CREATE_LOCAL_PLAYER, new byte[40]);
        FlashbackFormat.action(s, FlashbackFormat.ID_CHUNK_REF, HexFormat.of().parseHex(chunkHash));
        return s.toByteArray();
    }

    @Test void archiveRoundTripsThroughVerifier() throws Exception {
        ChunkStore store = new ChunkStore();
        String c1 = store.put(packet(39, 500));
        String c2 = store.put(packet(39, 600));
        List<Segment> segs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Segment s = new Segment(store, i * 600L, snapshot(c1), Set.of(c1));
            for (int t = 0; t < 600; t++) {
                if (t % 100 == 0) s.action(FlashbackFormat.ID_GAME_PACKET, packet(10, 5));
                if (t == 50) s.chunkRef(c2);
                s.nextTick();
            }
            s.close(); s.compress();
            segs.add(s);
        }
        List<ReplayArchive.Part> parts = segs.stream().map(s -> new ReplayArchive.Part(s.snapshot(), s.actionsCopy(), s.ticks())).toList();
        Path zip = dir.resolve("clip.zip");
        int ticks = ReplayArchive.write(zip, new ReplayArchive.Meta(UUID.randomUUID(), "시험", "world", "1.21.11", 4671, 774),
                parts, List.of(new ReplayArchive.Marker(700, 0xFF0000, "싸움 시작", "minecraft:overworld", 1, 64, 2)), store::get, 1500);
        assertEquals(1500, ticks, "끝을 1500틱에서 자름");
        var r = ReplayVerifier.verify(zip, null);
        assertTrue(r.ok(), r.problems().toString());
        assertEquals(3, r.files());
        assertEquals(1500, r.totalTicks());
        assertEquals(2, r.cachedChunks(), "같은 청크는 캐시에 한 번만");
        assertEquals(3, r.localPlayers());
    }

    @Test void verifierCatchesBrokenFiles() throws Exception {
        ChunkStore store = new ChunkStore();
        String c1 = store.put(packet(39, 100));
        Segment s = new Segment(store, 0, snapshot(c1), Set.of(c1));
        s.nextTick(); s.close();
        Path zip = dir.resolve("bad.zip");
        // 청크 저장소에 없는 해시를 가리키면 쓰기 단계에서 막힘
        store.release(c1); store.release(c1);
        assertThrows(ReplayArchive.MissingChunkException.class, () -> ReplayArchive.write(zip,
                new ReplayArchive.Meta(UUID.randomUUID(), "x", null, null, 0, 0),
                List.of(new ReplayArchive.Part(s.snapshot(), s.actionsCopy(), 1)), List.of(), store::get, 0));
    }

    @Test void chunkStoreSharesAndFrees() {
        ChunkStore store = new ChunkStore();
        byte[] p = packet(39, 2000);
        String a = store.put(p), b = store.put(p.clone());
        assertEquals(a, b); assertEquals(1, store.size()); assertEquals(2, store.refs(a));
        store.compressPending();
        assertArrayEquals(p, store.get(a), "압축 후에도 같은 내용");
        assertTrue(store.memoryBytes() < p.length);
        store.release(a); store.release(a);
        assertEquals(0, store.size());
    }

    private static Segment seg(ChunkStore store, long start, String hash, int ticks) {
        Segment s = new Segment(store, start, snapshot(hash), Set.of(hash));
        for (int i = 0; i < ticks; i++) s.nextTick();
        return s;
    }

    @Test void bufferKeepsPreRollAndExportsFight() {
        ChunkStore store = new ChunkStore();
        String h = store.put(packet(39, 50));
        PlayerBuffer buf = new PlayerBuffer(1200, 20 * 60 * 15);
        long now = 0;
        for (int i = 0; i < 10; i++) {           // 10구간 = 5분, 싸움 없음
            buf.startSegment(seg(store, now, h, 600)); now += 600;
            buf.purge(now);
        }
        assertTrue(buf.segments().size() <= 4, "평소엔 1분+구간 하나 정도만: " + buf.segments().size());
        long hit = now;                          // 6000틱에 맞음 → 1분 전(4800)부터
        buf.highlight("PvP", now - 1200, now + 600, new PlayerBuffer.Marker(now, 0xFF0000, "첫 타격", null, 0, 0, 0));
        assertFalse(buf.exportDue(now));
        buf.startSegment(seg(store, now, h, 600)); now += 600;
        buf.highlight("PvP", now - 1200, now + 100, null);    // 싸움 계속 → 연장
        assertFalse(buf.exportDue(now));
        now += 100;
        assertTrue(buf.exportDue(now));
        buf.startSegment(seg(store, now - 100, h, 100));       // 호출 쪽이 구간을 닫고 새로 시작
        buf.startSegment(seg(store, now, h, 0));
        var clip = buf.takeClip(now);
        assertNotNull(clip);
        assertTrue(clip.startTick() <= hit - 1200, "1분 전이 든 구간부터: " + clip.startTick());
        assertTrue(clip.startTick() > hit - 1200 - 600, "필요 이상 앞에서 시작하지 않음");
        assertEquals(Set.of("PvP"), clip.labels());
        assertEquals(1, clip.markers().size());
        assertFalse(buf.recording());
        clip.done();
        buf.purge(now);
        buf.clear();
        store.release(h);                         // 맨 처음 put 의 참조
        assertEquals(0, store.size(), "다 버리면 청크도 사라짐");
    }

    @Test void longFightIsSplitAndContinues() {
        ChunkStore store = new ChunkStore();
        String h = store.put(packet(39, 50));
        PlayerBuffer buf = new PlayerBuffer(1200, 1800);
        long now = 0;
        buf.startSegment(seg(store, now, h, 600)); now += 600;
        buf.highlight("PvP", 0, 100000, null);
        for (int i = 0; i < 3; i++) { buf.startSegment(seg(store, now, h, 600)); now += 600; }
        assertTrue(buf.exportDue(now), "최대 길이(1800틱) 넘음");
        buf.startSegment(seg(store, now, h, 0));
        var first = buf.takeClip(now);
        assertFalse(first.continued());
        assertTrue(buf.recording(), "같은 싸움은 이어서 녹화");
        first.done();
    }

    @Test void fileTagsAreAscii() {
        assertEquals("pvp", com.deruy.plugin.replay.ReplayService.tag("PvP"));
        assertEquals("randomevent", com.deruy.plugin.replay.ReplayService.tag("랜덤 이벤트 방패 사용 금지 (40초)"));
        assertEquals("transport", com.deruy.plugin.replay.ReplayService.tag("하트 운송전"));
        assertEquals("koth", com.deruy.plugin.replay.ReplayService.tag("KOTH 1"));
        assertEquals("event", com.deruy.plugin.replay.ReplayService.tag("새 이벤트"));
        for (String l : List.of("PvP", "보급 슈퍼", "수동", "퇴장", "Custom Fight!"))
            assertTrue(com.deruy.plugin.replay.ReplayService.tag(l).matches("[a-z0-9_-]+"), l);
    }

    @Test void chunkStoreRefsAreAtomicAcrossThreads() throws Exception {
        ChunkStore store = new ChunkStore();
        byte[] p = packet(39, 300);
        String held = store.put(p);                  // 이 참조는 끝까지 들고 있음
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        var failed = new java.util.concurrent.atomic.AtomicBoolean();
        for (int t = 0; t < 4; t++) pool.execute(() -> {
            for (int i = 0; i < 20000; i++) {
                String h = store.put(p.clone());
                if (store.get(held) == null) failed.set(true);
                store.release(h);
                if (i % 97 == 0) store.compressPending();
            }
        });
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS));
        assertFalse(failed.get(), "참조를 들고 있는 동안 사라지면 안 됨");
        assertEquals(1, store.refs(held));
        store.release(held);
        assertEquals(0, store.size());
        assertEquals(0, store.memoryBytes(), "메모리 집계도 0으로 돌아옴");
    }

    @Test void closedSegmentDropsWriteBuffer() {
        ChunkStore store = new ChunkStore();
        String h = store.put(packet(39, 10));
        Segment s = new Segment(store, 0, snapshot(h), Set.of(h));
        byte[] big = new byte[1 << 20];
        for (int i = 0; i < 8; i++) s.action(FlashbackFormat.ID_GAME_PACKET, big);
        long open = s.memoryBytes();
        assertTrue(open >= 8L << 20, "열린 구간은 버퍼 용량만큼: " + open);
        s.close(); s.compress();
        assertTrue(s.memoryBytes() < 1 << 20, "닫고 압축하면 작아짐: " + s.memoryBytes());
        assertEquals(8 * ((1 << 20) + 5 + FbOut.varIntSize(FlashbackFormat.ID_GAME_PACKET) - 1), s.actionsCopy().length, "내용은 그대로");
        s.release(); store.release(h);
    }
}
