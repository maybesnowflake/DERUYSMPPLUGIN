package com.deruy.plugin.replay.format;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 만든 리플레이 zip 이 Flashback 형식에 맞는지 구조를 끝까지 읽어 본다.
 * 패킷 내용 해석은 packetCheck 에 맡긴다(서버 코덱으로 디코드해 보는 쪽은 nms 패키지).
 */
public final class ReplayVerifier {
    public interface PacketCheck {
        /** kind: "game" / "config" / "chunk". 문제 있으면 예외 */
        void check(String kind, byte[] data, int off, int len) throws Exception;
    }

    public record Report(int files, int totalTicks, int actions, int gamePackets, int configPackets, int cachedChunks,
                         int localPlayers, List<String> problems) {
        public boolean ok() { return problems.isEmpty(); }
    }

    private ReplayVerifier() {}

    public static Report verify(Path zip, PacketCheck packetCheck) throws IOException {
        List<String> problems = new ArrayList<>();
        int files = 0, ticks = 0, actions = 0, games = 0, configs = 0, locals = 0;
        try (ZipFile z = new ZipFile(zip.toFile())) {
            ZipEntry metaEntry = z.getEntry("metadata.json");
            if (metaEntry == null) return new Report(0, 0, 0, 0, 0, 0, 0, List.of("metadata.json 없음"));
            JsonObject meta = JsonParser.parseString(new String(read(z, metaEntry), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject chunks = meta.getAsJsonObject("chunks");
            if (chunks == null || chunks.isEmpty()) problems.add("chunks 비어 있음");

            // 청크 캐시
            List<byte[]> cache = new ArrayList<>();
            for (int i = 0; ; i++) {
                ZipEntry e = z.getEntry("level_chunk_caches/" + i);
                if (e == null) break;
                FbIn in = new FbIn(read(z, e));
                while (in.hasMore()) {
                    int size = in.readInt();
                    byte[] p = Arrays.copyOfRange(in.array(), in.position(), in.position() + size);
                    in.skip(size);
                    cache.add(p);
                    check(packetCheck, "chunk", p, 0, p.length, problems, "캐시 청크 " + (cache.size() - 1));
                }
            }

            if (chunks != null) for (String name : chunks.keySet()) {
                files++;
                int duration = chunks.getAsJsonObject(name).get("duration").getAsInt();
                ZipEntry e = z.getEntry(name);
                if (e == null) { problems.add(name + " 파일 없음"); continue; }
                FbIn in = new FbIn(read(z, e));
                if (in.readInt() != FlashbackFormat.MAGIC) { problems.add(name + ": 매직 불일치"); continue; }
                int n = in.readVarInt();
                List<String> table = new ArrayList<>();
                for (int i = 0; i < n; i++) table.add(in.readUtf());
                int snapSize = in.readInt();
                if (snapSize < 0 || snapSize > in.remaining()) { problems.add(name + ": 스냅샷 크기 이상 " + snapSize); continue; }
                int snapEnd = in.position() + snapSize;
                int fileTicks = 0;
                boolean inSnapshot = true;
                while (in.hasMore()) {
                    if (inSnapshot && in.position() >= snapEnd) inSnapshot = false;
                    int id = in.readVarInt();
                    int size = in.readInt();
                    if (id < 0 || id >= table.size()) { problems.add(name + ": 모르는 액션 " + id); break; }
                    if (size < 0 || size > in.remaining()) { problems.add(name + ": 액션 크기 이상"); break; }
                    int at = in.position();
                    in.skip(size);
                    actions++;
                    String act = table.get(id);
                    switch (act) {
                        case FlashbackFormat.NEXT_TICK -> { if (inSnapshot) problems.add(name + ": 스냅샷 안에 next_tick"); else fileTicks++; }
                        case FlashbackFormat.GAME_PACKET -> { games++; check(packetCheck, "game", in.array(), at, size, problems, name + " 게임 패킷 #" + games); }
                        case FlashbackFormat.CONFIGURATION_PACKET -> { configs++; check(packetCheck, "config", in.array(), at, size, problems, name + " 설정 패킷 #" + configs); }
                        case FlashbackFormat.CREATE_LOCAL_PLAYER -> locals++;
                        case FlashbackFormat.LEVEL_CHUNK_CACHED -> {
                            int idx = new FbIn(in.array(), at, size).readVarInt();
                            if (idx < 0 || idx >= cache.size()) problems.add(name + ": 없는 캐시 청크 " + idx);
                        }
                        case FlashbackFormat.MOVE_ENTITIES -> checkMove(in.array(), at, size, problems, name);
                        default -> { }
                    }
                    if (inSnapshot && in.position() > snapEnd) { problems.add(name + ": 스냅샷 경계를 넘는 액션"); break; }
                }
                if (fileTicks != duration) problems.add(name + ": duration " + duration + " ≠ next_tick " + fileTicks);
                ticks += fileTicks;
            }
            if (meta.has("total_ticks") && meta.get("total_ticks").getAsInt() != ticks)
                problems.add("total_ticks " + meta.get("total_ticks").getAsInt() + " ≠ " + ticks);
            if (locals == 0) problems.add("create_local_player 없음");
            return new Report(files, ticks, actions, games, configs, cache.size(), locals, problems);
        }
    }

    private static void checkMove(byte[] a, int at, int size, List<String> problems, String name) {
        try {
            FbIn in = new FbIn(a, at, size);
            int levels = in.readVarInt();
            for (int l = 0; l < levels; l++) {
                in.readUtf();
                int count = in.readVarInt();
                for (int i = 0; i < count; i++) {
                    in.readVarInt(); in.readDouble(); in.readDouble(); in.readDouble();
                    in.readFloat(); in.readFloat(); in.readFloat(); in.readBoolean();
                }
            }
            if (in.hasMore()) problems.add(name + ": move_entities 남는 바이트");
        } catch (RuntimeException e) { problems.add(name + ": move_entities 깨짐 " + e.getMessage()); }
    }

    private static void check(PacketCheck c, String kind, byte[] d, int off, int len, List<String> problems, String what) {
        if (c == null) return;
        try { c.check(kind, d, off, len); }
        catch (Exception e) { if (problems.size() < 50) problems.add(what + ": " + e); }
    }

    private static byte[] read(ZipFile z, ZipEntry e) throws IOException {
        try (InputStream in = z.getInputStream(e)) { return in.readAllBytes(); }
    }
}
