package com.deruy.plugin.replay.buffer;

import com.deruy.plugin.replay.format.FbOut;
import com.deruy.plugin.replay.format.FlashbackFormat;

import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

/**
 * 버퍼의 한 구간(기본 30초): 시작 시점 스냅샷 + 이후 액션.
 * 쓰는 동안은 서버 스레드만 만지고, 닫힌 뒤에는 읽기 전용(압축은 저장 스레드).
 */
public final class Segment {
    public final long startTick;
    private final byte[] snapshot;
    private FbOut actions = new FbOut(1 << 14); // 닫으면 null (쓰기 버퍼 통째로 놓아줌)
    private final Set<String> refs = new HashSet<>();
    private int ticks;
    private boolean closed;
    private volatile byte[] closedActions;
    private volatile byte[] deflatedActions;
    private volatile int actionsLength;

    private final ChunkStore store;
    private boolean released;

    /** snapshotRefs: 스냅샷이 가리키는 청크. 구간이 살아 있는 동안 참조를 잡아 둔다 */
    public Segment(ChunkStore store, long startTick, byte[] snapshot, Set<String> snapshotRefs) {
        this.store = store;
        this.startTick = startTick;
        this.snapshot = snapshot;
        for (String h : snapshotRefs) if (refs.add(h) && !store.acquire(h)) throw new IllegalStateException("없는 청크 " + h);
    }

    public void action(int id, byte[] payload) { check(); FlashbackFormat.action(actions, id, payload); }
    public void action(int id, byte[] payload, int off, int len) { check(); FlashbackFormat.action(actions, id, payload, off, len); }

    /** 청크 참조(해시). 이 구간이 처음 가리키는 청크면 참조를 잡는다 */
    public void chunkRef(String hash) {
        check();
        if (refs.add(hash) && !store.acquire(hash)) { refs.remove(hash); throw new IllegalStateException("없는 청크 " + hash); }
        FlashbackFormat.action(actions, FlashbackFormat.ID_CHUNK_REF, HexFormat.of().parseHex(hash));
    }

    /** 버릴 때: 잡아 둔 청크 참조를 돌려준다 (한 번만) */
    public void release() {
        if (released) return;
        released = true;
        for (String h : refs) store.release(h);
    }

    /** 내보내기 동안 청크가 사라지지 않게 참조를 하나 더 잡는다 → 끝나면 releaseExtra */
    public void acquireExtra() { for (String h : refs) store.acquire(h); }
    public void releaseExtra() { for (String h : refs) store.release(h); }

    public void nextTick() { check(); FlashbackFormat.nextTick(actions); ticks++; }

    public void close() {
        if (closed) return;
        closed = true;
        closedActions = actions.toByteArray();
        actionsLength = closedActions.length;
        actions = null;
    }

    private void check() { if (closed) throw new IllegalStateException("닫힌 구간"); }

    public boolean closed() { return closed; }
    public int ticks() { return ticks; }
    public long endTick() { return startTick + ticks; }
    public Set<String> refs() { return refs; }
    public byte[] snapshot() { return snapshot; }

    /** 현재까지의 액션 사본 (열린 구간도 가능 — 서버 스레드에서만) */
    public byte[] actionsCopy() {
        if (!closed) return actions.toByteArray();
        byte[] c = closedActions;
        if (c != null) return c;
        return ChunkStore.inflate(deflatedActions, actionsLength);
    }

    public void compress() {
        byte[] c = closedActions;
        if (c == null) return;
        deflatedActions = ChunkStore.deflate(c);
        closedActions = null;
    }

    public long memoryBytes() {
        byte[] c = closedActions, d = deflatedActions;
        FbOut open = actions;
        long a = open != null ? open.array().length : c != null ? c.length : d != null ? d.length : 0; // 열린 구간은 버퍼 용량 기준
        return a + snapshot.length;
    }
}
