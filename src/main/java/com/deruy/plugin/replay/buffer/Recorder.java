package com.deruy.plugin.replay.buffer;

import java.util.List;

/** 녹화기 공통(플레이어 시점·카메라). 서버 스레드 전용 */
public interface Recorder {
    /** 파일·제목에 쓰는 이름 */
    String name();
    PlayerBuffer buffer();
    /** 매 틱. 반환: 이번에 끝나 저장할 클립들 */
    List<PlayerBuffer.Clip> tick(long now);
    boolean alive();
    String stoppedReason();
    void stop(String reason);
    /** 끝낼 때: 녹화 중이던 것까지 클립으로 */
    List<PlayerBuffer.Clip> finish(long now);
    /** 버퍼·청크 참조 반납 (저장 스레드에서 불러도 됨) */
    void release();
}
