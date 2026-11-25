package com.deruy.plugin.replay.format;

import java.util.List;

/**
 * Flashback 리플레이 파일 형식(1.21.11 기준)의 상수.
 * 청크 파일(c0.flashback …) = MAGIC, 액션 이름 표, 스냅샷 크기+스냅샷, 이후 액션들.
 * 액션 하나 = VarInt 액션번호, int 길이, 내용.
 * 이 플러그인은 아래 순서로만 액션 표를 쓰므로 번호가 고정된다.
 */
public final class FlashbackFormat {
    private FlashbackFormat() {}

    public static final int MAGIC = 0xD780E884;

    public static final String NEXT_TICK = "flashback:action/next_tick";
    public static final String GAME_PACKET = "flashback:action/game_packet";
    public static final String CONFIGURATION_PACKET = "flashback:action/configuration_packet";
    public static final String CREATE_LOCAL_PLAYER = "flashback:action/create_local_player";
    public static final String MOVE_ENTITIES = "flashback:action/move_entities";
    public static final String LEVEL_CHUNK_CACHED = "flashback:action/level_chunk_cached";

    public static final List<String> ACTIONS = List.of(
            NEXT_TICK, GAME_PACKET, CONFIGURATION_PACKET, CREATE_LOCAL_PLAYER, MOVE_ENTITIES, LEVEL_CHUNK_CACHED);

    public static final int ID_NEXT_TICK = 0;
    public static final int ID_GAME_PACKET = 1;
    public static final int ID_CONFIGURATION_PACKET = 2;
    public static final int ID_CREATE_LOCAL_PLAYER = 3;
    public static final int ID_MOVE_ENTITIES = 4;
    public static final int ID_LEVEL_CHUNK_CACHED = 5;

    /**
     * 버퍼 안에서만 쓰는 가짜 액션: 청크 패킷을 내용 해시(20바이트)로 가리킨다.
     * 내보낼 때 level_chunk_caches 번호를 가리키는 LEVEL_CHUNK_CACHED 로 바뀐다.
     */
    public static final int ID_CHUNK_REF = 0x7F;

    /** 버퍼 안에서만 쓰는 가짜 액션: 공용 묶음(이미 액션 형식인 바이트)을 해시로 가리킨다. 내보낼 때 그 자리에 펼친다 */
    public static final int ID_BLOB_REF = 0x7E;

    /** Flashback 이 한 캐시 파일에 담는 청크 수 */
    public static final int CHUNK_CACHE_SIZE = 10_000;

    /** 청크 파일 머리(매직 + 액션 표) */
    public static byte[] header() {
        FbOut out = new FbOut(256);
        out.writeInt(MAGIC);
        out.writeVarInt(ACTIONS.size());
        for (String a : ACTIONS) out.writeUtf(a);
        return out.toByteArray();
    }

    /** 액션 하나를 out 에 쓴다 */
    public static void action(FbOut out, int id, byte[] payload) {
        action(out, id, payload, 0, payload.length);
    }

    public static void action(FbOut out, int id, byte[] payload, int off, int len) {
        out.writeVarInt(id);
        out.writeInt(len);
        out.writeBytes(payload, off, len);
    }

    public static void nextTick(FbOut out) {
        out.writeVarInt(ID_NEXT_TICK);
        out.writeInt(0);
    }
}
