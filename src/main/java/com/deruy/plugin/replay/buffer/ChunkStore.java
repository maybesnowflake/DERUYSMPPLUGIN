package com.deruy.plugin.replay.buffer;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
        volatile byte[] raw;          // 압축 전(막 들어온 것)
        volatile byte[] deflated;     // 압축 후
        volatile int rawLength;
        final AtomicInteger refs = new AtomicInteger();
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
        Entry e = entries.computeIfAbsent(h, k -> {
            Entry n = new Entry();
            n.raw = packet; n.rawLength = packet.length;
            bytes.addAndGet(packet.length);
            return n;
        });
        e.refs.incrementAndGet();
        return h;
    }

    public boolean acquire(String h) {
        Entry e = entries.get(h);
        if (e == null) return false;
        e.refs.incrementAndGet();
        return true;
    }

    public void release(String h) {
        Entry e = entries.get(h);
        if (e == null) return;
        if (e.refs.decrementAndGet() <= 0) {
            if (entries.remove(h, e)) {
                byte[] d = e.deflated, r = e.raw;
                bytes.addAndGet(-(d != null ? d.length : r != null ? r.length : 0));
            }
        }
    }

    /** 압축 안 된 항목을 압축해 메모리를 줄인다 (저장 스레드에서 주기적으로) */
    public void compressPending() {
        for (Entry e : entries.values()) {
            byte[] r = e.raw;
            if (r == null) continue;
            byte[] d = deflate(r);
            synchronized (e) {
                if (e.raw == r) { e.deflated = d; e.raw = null; bytes.addAndGet(d.length - r.length); }
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
    public int refs(String h) { Entry e = entries.get(h); return e == null ? 0 : e.refs.get(); }

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
