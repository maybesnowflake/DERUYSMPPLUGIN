package com.deruy.plugin.replay.format;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** 마인크래프트 네트워크 형식(빅엔디언, VarInt) 바이트 쓰기. 서버 클래스 없이 쓰는 부분용. */
public final class FbOut {
    private byte[] buf;
    private int len;

    public FbOut() { this(256); }
    public FbOut(int capacity) { buf = new byte[Math.max(16, capacity)]; }

    private void ensure(int more) {
        if (len + more > buf.length) buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + more));
    }

    public FbOut writeByte(int b) { ensure(1); buf[len++] = (byte) b; return this; }
    public FbOut writeBoolean(boolean b) { return writeByte(b ? 1 : 0); }
    public FbOut writeInt(int v) {
        ensure(4);
        buf[len++] = (byte) (v >>> 24); buf[len++] = (byte) (v >>> 16); buf[len++] = (byte) (v >>> 8); buf[len++] = (byte) v;
        return this;
    }
    public FbOut writeLong(long v) { writeInt((int) (v >>> 32)); return writeInt((int) v); }
    public FbOut writeFloat(float f) { return writeInt(Float.floatToIntBits(f)); }
    public FbOut writeDouble(double d) { return writeLong(Double.doubleToLongBits(d)); }
    public FbOut writeVarInt(int v) {
        while ((v & ~0x7F) != 0) { writeByte((v & 0x7F) | 0x80); v >>>= 7; }
        return writeByte(v);
    }
    public FbOut writeUtf(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(b.length);
        return writeBytes(b);
    }
    public FbOut writeBytes(byte[] b) { return writeBytes(b, 0, b.length); }
    public FbOut writeBytes(byte[] b, int off, int n) { ensure(n); System.arraycopy(b, off, buf, len, n); len += n; return this; }

    /** 나중에 채울 4바이트 자리 */
    public int reserveInt() { int at = len; writeInt(0); return at; }
    public void putInt(int at, int v) {
        buf[at] = (byte) (v >>> 24); buf[at + 1] = (byte) (v >>> 16); buf[at + 2] = (byte) (v >>> 8); buf[at + 3] = (byte) v;
    }

    public int size() { return len; }
    public void clear() { len = 0; }
    public byte[] toByteArray() { return Arrays.copyOf(buf, len); }
    public byte[] array() { return buf; }

    public static int varIntSize(int v) {
        int n = 1;
        while ((v & ~0x7F) != 0) { n++; v >>>= 7; }
        return n;
    }
}
