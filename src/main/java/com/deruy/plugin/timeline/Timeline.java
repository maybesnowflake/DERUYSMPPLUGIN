package com.deruy.plugin.timeline;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.Location;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;

/**
 * 영상 편집용 타임라인: 킬·하트·이벤트·수송 등을 초 단위 시각과 함께 날짜별 CSV로 남긴다.
 * 파일: plugins/DeruyPlugin/timeline/YYYY-MM-DD.csv (UTF-8 BOM → 엑셀에서 한글 안 깨짐)
 * 저장은 서버 스레드 밖 단일 스레드에서 순서대로.
 */
public final class Timeline implements AutoCloseable {
    public static final String HEADER = "시각,종류,누구,내용,월드,x,y,z";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final DeruyPlugin plugin;
    private final Path dir;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Deruy-Timeline"); t.setDaemon(true); return t;
    });

    public Timeline(DeruyPlugin plugin) { this(plugin, plugin.getDataFolder().toPath().resolve("timeline")); }
    public Timeline(DeruyPlugin plugin, Path dir) { this.plugin = plugin; this.dir = dir; }

    /** plugin 에 타임라인이 없으면(테스트 등) 조용히 무시 */
    public static void log(DeruyPlugin plugin, String type, String who, String detail, Location at) {
        if (plugin == null) return;
        var replay = plugin.getReplayService();
        if (replay != null) {
            try { replay.onTimeline(type, who == null ? "" : who, detail == null ? "" : detail, at); }
            catch (RuntimeException e) { plugin.getLogger().warning("서버 녹화 이벤트 처리 실패: " + e); }
        }
        Timeline t = plugin.getTimeline();
        if (t != null) t.record(type, who, detail, at);
    }

    public void record(String type, String who, String detail, Location at) {
        if (!plugin.getConfig().getBoolean("timeline.enabled", true)) return;
        LocalDateTime now = LocalDateTime.now();
        String line = line(now, type, who, detail, at);
        Path file = fileFor(now.toLocalDate());
        writer.execute(() -> {
            try {
                Files.createDirectories(dir);
                if (!Files.exists(file)) Files.writeString(file, "﻿" + HEADER + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            } catch (IOException e) { plugin.getLogger().warning("타임라인 저장 실패: " + e.getMessage()); }
        });
    }

    static String line(LocalDateTime time, String type, String who, String detail, Location at) {
        String w = "", x = "", y = "", z = "";
        if (at != null && at.getWorld() != null) {
            w = at.getWorld().getName();
            x = String.valueOf(at.getBlockX()); y = String.valueOf(at.getBlockY()); z = String.valueOf(at.getBlockZ());
        }
        return String.join(",", csv(TIME.format(time)), csv(type), csv(who), csv(detail), csv(w), x, y, z) + "\n";
    }

    /** 쉼표·따옴표·줄바꿈이 있으면 따옴표로 감싸고 따옴표는 두 번. 엑셀 수식 실행 방지로 =,+,-,@ 시작은 ' 붙임 */
    static String csv(String s) {
        if (s == null) return "";
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    public Path fileFor(LocalDate day) { return dir.resolve(day + ".csv"); }

    /** 기록 건수(헤더 제외). 쓰기 대기 중인 것까지 반영하려면 flush 후 호출 */
    public long count(LocalDate day) {
        Path f = fileFor(day);
        if (!Files.exists(f)) return 0;
        try (var lines = Files.lines(f, StandardCharsets.UTF_8)) { return Math.max(0, lines.count() - 1); }
        catch (IOException e) { return -1; }
    }

    /** 지금까지 넣은 기록을 다 쓸 때까지 기다림 (명령어·테스트용, 최대 2초) */
    public void flush() {
        try { writer.submit(() -> {}).get(2, TimeUnit.SECONDS); } catch (Exception ignored) { }
    }

    @Override public void close() {
        writer.shutdown();
        try { if (!writer.awaitTermination(5, TimeUnit.SECONDS)) plugin.getLogger().warning("타임라인 저장이 지연되고 있습니다."); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
