package com.deruy.plugin.replay.buffer;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * 청크 패킷 저장소. 같은 내용은 한 번만 들고(여러 플레이어·여러 스냅샷이 공유), 아무도 안 쓰면 버린다.
 * put/acquire/release 는 서버 스레드에서, compress 는 저장 스레드에서 불러도 안전하다.
 */
public final class ChunkStore {
    private static final class Entry {
        final String hash;
        Entry(String hash) { this.hash = hash; }
        volatile byte[] raw;          // 압축 전(막 들어온 것)
        volatile byte[] deflated;     // 압축 후
        volatile int rawLength;
        int refs; // 해시별 compute 안에서만 바꾼다 (등록·증가·마지막 제거가 한 번에 일어나도록)
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong bytes = new AtomicLong();

    public static String hash(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** 내용을 넣고 참조 1개를 잡는다. 반환: 해시 */
    public String put(byte[] packet) {
        String h = hash(packet);
        entries.compute(h, (k, e) -> {
            if (e == null) {
                e = new Entry(k);
                e.raw = packet; e.rawLength = packet.length;
                bytes.addAndGet(packet.length);
            }
            e.refs++;
            return e;
        });
        return h;
    }

    /** 이미 있는 내용의 참조를 하나 더 잡는다. 없으면 false */
    public boolean acquire(String h) {
        return entries.computeIfPresent(h, (k, e) -> { e.refs++; return e; }) != null;
    }

    public void release(String h) {
        entries.computeIfPresent(h, (k, e) -> {
            if (--e.refs > 0) return e;
            synchronized (e) {
                byte[] d = e.deflated, r = e.raw;
                bytes.addAndGet(-(d != null ? d.length : r != null ? r.length : 0));
            }
            return null; // 마지막 참조 → 같은 원자 연산 안에서 제거
        });
    }

    /** 압축 안 된 항목을 압축해 메모리를 줄인다 (저장 스레드에서 주기적으로) */
    public void compressPending() {
        for (Entry e : entries.values()) {
            byte[] r = e.raw;
            if (r == null) continue;
            byte[] d = deflate(r);
            synchronized (e) {
                // 그 사이 제거됐으면(맵에 없으면) 집계를 건드리지 않는다
                if (e.raw == r && entries.get(e.hash) == e) { e.deflated = d; e.raw = null; bytes.addAndGet(d.length - r.length); }
            }
        }
    }

    public byte[] get(String h) {
        Entry e = entries.get(h);
        if (e == null) return null;
        byte[] r, d; int len;
        synchronized (e) { r = e.raw; d = e.deflated; len = e.rawLength; }
        if (r != null) return r;
        return d == null ? null : inflate(d, len);
    }

    public int size() { return entries.size(); }
    public long memoryBytes() { return bytes.get(); }
    public int refs(String h) { Entry e = entries.get(h); return e == null ? 0 : e.refs; }

    static byte[] deflate(byte[] in) {
        Deflater d = new Deflater(Deflater.BEST_SPEED);
        d.setInput(in); d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, in.length / 3));
        byte[] buf = new byte[8192];
        while (!d.finished()) out.write(buf, 0, d.deflate(buf));
        d.end();
        return out.toByteArray();
    }

    static byte[] inflate(byte[] in, int len) {
        Inflater i = new Inflater();
        i.setInput(in);
        byte[] out = new byte[len];
        try {
            int n = 0;
            while (n < len && !i.finished()) n += i.inflate(out, n, len - n);
        } catch (DataFormatException e) { throw new IllegalStateException(e); }
        finally { i.end(); }
        return out;
    }
}
