package com.deruy.plugin.replay.format;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.Function;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 버퍼에 모인 구간(Part)들을 Flashback 이 여는 zip 한 개로 만든다.
 * zip: metadata.json, c0.flashback …, level_chunk_caches/0 …
 */
public final class ReplayArchive {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 한 청크 파일이 될 구간: 스냅샷(그 시점 화면 상태)과 이후 액션들, 길이(틱) */
    public record Part(byte[] snapshot, byte[] actions, int ticks) {}

    /** 마커: tick 은 리플레이 시작 기준 */
    public record Marker(int tick, int colour, String description, String dimension, double x, double y, double z) {}

    public record Meta(UUID id, String name, String worldName, String versionString, int dataVersion, int protocolVersion) {}

    public static final class MissingChunkException extends IOException {
        public MissingChunkException(String hash) { super("버퍼에 없는 청크: " + hash); }
    }

    private ReplayArchive() {}

    /**
     * @param chunks 청크 해시(hex) → 인코딩된 청크 패킷(패킷번호+내용)
     * @param maxTicks 0 보다 크면 그 틱까지만 남긴다(끝 다듬기)
     * @return 실제로 쓴 틱 수
     */
    public static int write(Path zip, Meta meta, List<Part> parts, List<Marker> markers,
                            Function<String, byte[]> chunks, int maxTicks) throws IOException {
        if (parts.isEmpty()) throw new IllegalArgumentException("구간이 없습니다");
        Map<String, Integer> cacheIndex = new HashMap<>();
        List<byte[]> cache = new ArrayList<>();
        List<byte[]> files = new ArrayList<>();
        List<Integer> durations = new ArrayList<>();
        int total = 0;
        byte[] header = FlashbackFormat.header();
        for (Part part : parts) {
            int budget = maxTicks > 0 ? maxTicks - total : Integer.MAX_VALUE;
            if (budget <= 0) break;
            FbOut snap = new FbOut(part.snapshot().length + 64);
            rewrite(part.snapshot(), snap, cacheIndex, cache, chunks, Integer.MAX_VALUE);
            FbOut acts = new FbOut(part.actions().length + 64);
            int ticks = rewrite(part.actions(), acts, cacheIndex, cache, chunks, budget);
            if (ticks == 0) { FlashbackFormat.nextTick(acts); ticks = 1; } // 길이 0 청크 방지
            FbOut file = new FbOut(header.length + 4 + snap.size() + acts.size());
            file.writeBytes(header);
            file.writeInt(snap.size());
            file.writeBytes(snap.array(), 0, snap.size());
            file.writeBytes(acts.array(), 0, acts.size());
            files.add(file.toByteArray());
            durations.add(ticks);
            total += ticks;
        }

        JsonObject json = new JsonObject();
        json.addProperty("uuid", meta.id().toString());
        json.addProperty("name", meta.name());
        if (meta.versionString() != null) json.addProperty("version_string", meta.versionString());
        if (meta.worldName() != null) json.addProperty("world_name", meta.worldName());
        if (meta.dataVersion() != 0) json.addProperty("data_version", meta.dataVersion());
        if (meta.protocolVersion() != 0) json.addProperty("protocol_version", meta.protocolVersion());
        json.addProperty("total_ticks", total);
        if (!markers.isEmpty()) {
            JsonObject jm = new JsonObject();
            for (Marker m : markers) {
                if (m.tick() < 0 || m.tick() > total) continue;
                JsonObject o = new JsonObject();
                o.addProperty("colour", m.colour());
                if (m.dimension() != null) {
                    JsonObject pos = new JsonObject();
                    com.google.gson.JsonArray v = new com.google.gson.JsonArray(); // Flashback 은 [x,y,z] 배열
                    v.add((float) m.x()); v.add((float) m.y()); v.add((float) m.z());
                    pos.add("position", v);
                    pos.addProperty("dimension", m.dimension());
                    o.add("position", pos);
                }
                if (m.description() != null) o.addProperty("description", m.description());
                jm.add(Integer.toString(m.tick()), o);
            }
            json.add("markers", jm);
        }
        JsonObject jc = new JsonObject();
        for (int i = 0; i < files.size(); i++) {
            JsonObject c = new JsonObject();
            c.addProperty("duration", durations.get(i));
            c.addProperty("forcePlaySnapshot", false);
            jc.add("c" + i + ".flashback", c);
        }
        json.add("chunks", jc);

        Files.createDirectories(zip.toAbsolutePath().getParent());
        Path tmp = zip.resolveSibling(zip.getFileName() + ".part");
        try (OutputStream os = Files.newOutputStream(tmp); ZipOutputStream z = new ZipOutputStream(os)) {
            z.setLevel(Deflater.BEST_SPEED);
            entry(z, "metadata.json", GSON.toJson(json).getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < files.size(); i++) entry(z, "c" + i + ".flashback", files.get(i));
            for (int start = 0; start < cache.size(); start += FlashbackFormat.CHUNK_CACHE_SIZE) {
                FbOut out = new FbOut(1 << 16);
                for (int i = start; i < Math.min(cache.size(), start + FlashbackFormat.CHUNK_CACHE_SIZE); i++) {
                    out.writeInt(cache.get(i).length);
                    out.writeBytes(cache.get(i));
                }
                entry(z, "level_chunk_caches/" + (start / FlashbackFormat.CHUNK_CACHE_SIZE), out.toByteArray());
            }
        }
        Files.move(tmp, zip, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return total;
    }

    private static void entry(ZipOutputStream z, String name, byte[] data) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(data);
        z.closeEntry();
    }

    /** 버퍼 액션을 그대로 옮기되 청크 해시 참조는 캐시 번호로 바꾼다. 반환: 옮긴 틱 수 */
    static int rewrite(byte[] ops, FbOut out, Map<String, Integer> cacheIndex, List<byte[]> cache,
                       Function<String, byte[]> chunks, int maxTicks) throws IOException {
        FbIn in = new FbIn(ops);
        int ticks = 0;
        while (in.hasMore()) {
            int id = in.readVarInt();
            int len = in.readInt();
            int at = in.position();
            in.skip(len);
            if (id == FlashbackFormat.ID_BLOB_REF) {
                String hash = HexFormat.of().formatHex(ops, at, at + len);
                byte[] blob = chunks.apply(hash);
                if (blob == null) throw new MissingChunkException(hash);
                ticks += rewrite(blob, out, cacheIndex, cache, chunks, Integer.MAX_VALUE);
            } else if (id == FlashbackFormat.ID_CHUNK_REF) {
                String hash = HexFormat.of().formatHex(ops, at, at + len);
                Integer idx = cacheIndex.get(hash);
                if (idx == null) {
                    byte[] packet = chunks.apply(hash);
                    if (packet == null) throw new MissingChunkException(hash);
                    idx = cache.size();
                    cache.add(packet);
                    cacheIndex.put(hash, idx);
                }
                FbOut p = new FbOut(5);
                p.writeVarInt(idx);
                FlashbackFormat.action(out, FlashbackFormat.ID_LEVEL_CHUNK_CACHED, p.array(), 0, p.size());
            } else {
                FlashbackFormat.action(out, id, ops, at, len);
                if (id == FlashbackFormat.ID_NEXT_TICK && ++ticks >= maxTicks) break;
            }
        }
        return ticks;
    }
}
