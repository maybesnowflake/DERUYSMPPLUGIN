package com.deruy.plugin.replay.format;

import java.nio.charset.StandardCharsets;

/** FbOut 의 반대: 검증·변환용 읽기 */
public final class FbIn {
    private final byte[] buf;
    private int pos;
    private final int end;

    public FbIn(byte[] buf) { this(buf, 0, buf.length); }
    public FbIn(byte[] buf, int off, int len) { this.buf = buf; this.pos = off; this.end = off + len; }

    public boolean hasMore() { return pos < end; }
    public int position() { return pos; }
    public int remaining() { return end - pos; }
    public byte[] array() { return buf; }

    public int readByte() { if (pos >= end) throw new IllegalStateException("EOF"); return buf[pos++] & 0xFF; }
    public boolean readBoolean() { return readByte() != 0; }
    public int readInt() { return (readByte() << 24) | (readByte() << 16) | (readByte() << 8) | readByte(); }
    public long readLong() { return ((long) readInt() << 32) | (readInt() & 0xFFFFFFFFL); }
    public float readFloat() { return Float.intBitsToFloat(readInt()); }
    public double readDouble() { return Double.longBitsToDouble(readLong()); }
    public int readVarInt() {
        int v = 0, shift = 0, b;
        do {
            if (shift >= 35) throw new IllegalStateException("VarInt too big");
            b = readByte();
            v |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return v;
    }
    public String readUtf() {
        int n = readVarInt();
        if (n < 0 || n > remaining()) throw new IllegalStateException("bad string length " + n);
        String s = new String(buf, pos, n, StandardCharsets.UTF_8);
        pos += n;
        return s;
    }
    public void skip(int n) {
        if (n < 0 || n > remaining()) throw new IllegalStateException("bad skip " + n);
        pos += n;
    }
}
