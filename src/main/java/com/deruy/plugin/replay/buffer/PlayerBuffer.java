package com.deruy.plugin.replay.buffer;

import java.util.*;

/**
 * 한 플레이어 시점의 되감기 버퍼.
 * 평소엔 최근 preRoll 만큼(+구간 하나)만 들고 있다가, 하이라이트(싸움·이벤트)가 걸리면
 * 그 시작점부터 끝날 때까지 구간을 붙잡아 두고 끝나면 Clip 으로 넘긴다.
 * 서버 스레드 전용.
 */
public final class PlayerBuffer {
    public record Marker(long tick, int colour, String description, String dimension, double x, double y, double z) {}

    /** 내보낼 묶음: 닫힌 구간들(청크 참조를 하나씩 더 잡아 둔 상태 → 다 쓰면 done()) */
    public record Clip(Set<String> labels, List<Segment> segments, long startTick, long endTick, List<Marker> markers, boolean continued) {
        public void done() { segments.forEach(Segment::releaseExtra); }
    }

    private static final class Window {
        long start, end;
        final LinkedHashSet<String> labels = new LinkedHashSet<>();
        final List<Marker> markers = new ArrayList<>();
        boolean continued;
    }

    private final Deque<Segment> segments = new ArrayDeque<>();
    private final int preRollTicks;
    private final int maxClipTicks;
    private Window window;

    public PlayerBuffer(int preRollTicks, int maxClipTicks) {
        this.preRollTicks = preRollTicks;
        this.maxClipTicks = maxClipTicks;
    }

    public void startSegment(Segment s) {
        Segment cur = segments.peekLast();
        if (cur != null) cur.close();
        segments.addLast(s);
    }

    public Segment current() { return segments.peekLast(); }
    public Collection<Segment> segments() { return Collections.unmodifiableCollection(segments); }
    public boolean recording() { return window != null; }
    public Set<String> labels() { return window == null ? Set.of() : Collections.unmodifiableSet(window.labels); }
    public long windowStart() { return window == null ? -1 : window.start; }
    public long windowEnd() { return window == null ? -1 : window.end; }

    /**
     * 하이라이트를 건다/늘린다.
     * @param from 이 시점부터 보이게 (예: 맞기 1분 전)
     * @param until 적어도 이 시점까지 녹화
     */
    public void highlight(String label, long from, long until, Marker marker) {
        if (window == null) {
            window = new Window();
            window.start = Math.max(from, oldestTick());
            window.end = until;
        } else {
            window.end = Math.max(window.end, until);
            // 이벤트(30초 전)에 PvP(60초 전)가 합쳐지면 더 이른 시작점으로. 이미 나눠 저장한 뒤라면 겹치지 않게 그대로
            if (!window.continued) window.start = Math.min(window.start, Math.max(from, oldestTick()));
        }
        window.labels.add(label);
        if (marker != null) window.markers.add(marker);
    }

    private long oldestTick() {
        Segment s = segments.peekFirst();
        return s == null ? Long.MIN_VALUE : s.startTick;
    }

    /** 지금 내보낼 차례인가 (끝났거나 최대 길이를 넘음). true 면 호출 쪽이 구간을 새로 시작한 뒤 takeClip() */
    public boolean exportDue(long now) {
        if (window == null) return false;
        return now >= window.end || now - clipStartTick() >= maxClipTicks;
    }

    private long clipStartTick() {
        Segment first = null;
        for (Segment s : segments) { if (s.startTick <= window.start) first = s; else break; }
        return first == null ? oldestTick() : first.startTick;
    }

    /** 닫힌 구간들로 Clip 을 만든다. 최대 길이 때문이면 같은 하이라이트를 이어서 계속 녹화한다 */
    public Clip takeClip(long now) {
        if (window == null) return null;
        long from = window.start;
        List<Segment> parts = new ArrayList<>();
        for (Segment s : segments) {
            // 시작점이 든 구간부터 지금까지 닫힌 구간 (스냅샷이 구간 머리에만 있어서 최대 구간 하나만큼 앞에서 시작)
            if (!s.closed() || s.endTick() <= from || s.startTick >= now) continue;
            parts.add(s);
        }
        if (parts.isEmpty()) { if (now >= window.end) window = null; return null; }
        parts.forEach(Segment::acquireExtra);
        boolean ended = now >= window.end;
        Clip clip = new Clip(new LinkedHashSet<>(window.labels), parts, parts.getFirst().startTick, now,
                List.copyOf(window.markers), window.continued);
        if (ended) {
            window = null;
        } else { // 너무 길어서 끊음 → 바로 이어서
            window.start = now;
            window.markers.clear();
            window.continued = true;
        }
        return clip;
    }

    /** 오래된 구간 버리기: 하이라이트 중이면 그 시작점이 든 구간부터, 아니면 최근 preRoll 이 든 구간부터 남긴다 */
    public void purge(long now) {
        // 녹화 중이어도 기본 되감기 분량은 남겨 둔다 (곧 PvP 가 더 이른 시작점을 요청할 수 있음)
        long keepFrom = window != null ? Math.min(window.start, now - preRollTicks) : now - preRollTicks;
        while (segments.size() > 1) {
            Iterator<Segment> it = segments.iterator();
            it.next();
            Segment second = it.next();
            if (second.startTick > keepFrom) break;
            segments.removeFirst().release();
        }
    }

    public void clear() {
        for (Segment s : segments) s.release();
        segments.clear();
        window = null;
    }

    public long memoryBytes() {
        long n = 0;
        for (Segment s : segments) n += s.memoryBytes();
        return n;
    }
}
