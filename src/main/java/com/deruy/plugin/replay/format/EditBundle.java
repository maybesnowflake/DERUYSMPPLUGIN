package com.deruy.plugin.replay.format;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * 편집용 묶음: 리플레이를 영상으로 처음부터 내보냈을 때 맞는 DaVinci Resolve 마커(.edl)와 날짜 폴더 목록(index.csv).
 * EDL 은 tools/timeline-to-resolve.html 과 같은 Resolve 마커 형식, 타임라인 시작 01:00:00:00.
 */
public final class EditBundle {
    private EditBundle() {}

    public static final String INDEX_HEADER = "시각,파일,종류,누구,길이(초),마커";

    static String tc(long frame, int fps) {
        long ff = frame % fps, s = frame / fps;
        return String.format("%02d:%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60, ff);
    }

    /** 리플레이 틱(초당 20) → 영상 프레임 */
    static long frame(int tick, int fps) { return Math.round(tick / 20.0 * fps); }

    static String colour(int rgb) {
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        if (r > 200 && g < 120 && b < 120) return "Red";
        if (r > 200 && g > 150 && b < 100) return "Yellow";
        if (b > 200 && r < 120) return "Blue";
        if (g > 200 && r < 150) return "Green";
        return "Cream";
    }

    public static String edl(String title, List<ReplayArchive.Marker> markers, int fps) {
        long base = 3600L * fps; // 01:00:00:00
        StringBuilder sb = new StringBuilder("TITLE: ").append(title.replaceAll("[\r\n]", " ")).append("\r\nFCM: NON-DROP FRAME\r\n\r\n");
        int n = 1;
        for (ReplayArchive.Marker m : markers) {
            if (m.tick() < 0) continue;
            long f = base + frame(m.tick(), fps);
            String a = tc(f, fps), b = tc(f + 1, fps);
            sb.append(String.format("%03d  001      V     C        %s %s %s %s  \r\n", n++, a, b, a, b));
            String name = (m.description() == null ? "" : m.description()).replace('|', '/').replaceAll("[\r\n]+", " ");
            sb.append(" |C:ResolveColor").append(colour(m.colour())).append(" |M:").append(name.length() > 120 ? name.substring(0, 120) : name).append(" |D:1\r\n\r\n");
        }
        return sb.toString();
    }

    static String csv(String v) {
        String s = v == null ? "" : v;
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) s = "'" + s; // 엑셀 수식 방지
        return s.matches(".*[\",\\r\\n].*") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    /** 날짜 폴더의 index.csv 에 한 줄 추가(없으면 BOM+머리줄부터) */
    public static void appendIndex(Path dir, String time, String file, String kind, String who, int seconds, int markers) throws IOException {
        Path f = dir.resolve("index.csv");
        if (!Files.exists(f)) Files.writeString(f, "﻿" + INDEX_HEADER + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.writeString(f, String.join(",", csv(time), csv(file), csv(kind), csv(who), Integer.toString(seconds), Integer.toString(markers)) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }
}
