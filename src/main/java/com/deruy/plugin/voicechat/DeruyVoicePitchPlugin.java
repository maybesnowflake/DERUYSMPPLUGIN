package com.deruy.plugin.voicechat;

import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.PitchShifter;
import be.tarsos.dsp.io.TarsosDSPAudioFormat;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 투명화 상태인 플레이어의 마이크 오디오를 가로채서
 * 고정 피치 다운시프트(기본 -6반음, 성인 남성 저음 느낌 = "오토튠")를 적용하는
 * SimpleVoiceChat 서버 플러그인.
 *
 * VoiceFeatureSettings.isAutotuneEnabled()로 서버 전체 온/오프 제어.
 * 꺼져 있거나 투명화가 아니면 원본 오디오를 그대로 통과시켜 서버 부하를 최소화한다.
 */
public class DeruyVoicePitchPlugin implements VoicechatPlugin {

    // 몇 반음 낮출지. 음수 = 다운피치. -4~-8 사이에서 취향껏 조절 권장.
    private static final double PITCH_SEMITONES = -6.0;
    private static final double PITCH_FACTOR = Math.pow(2.0, PITCH_SEMITONES / 12.0);

    private static final int SAMPLE_RATE = 48000;
    private static final int BUFFER_SIZE = 960; // 20ms @ 48kHz mono
    private static final int OVERLAP = 240;

    private static final Logger LOGGER = Logger.getLogger("DeruyVoicePitchPlugin");

    private final InvisibilityVoiceEffectTracker tracker;
    private final VoiceFeatureSettings settings;

    // 플레이어별 상태. opus 코덱과 피치 이펙트는 프레임 간 연속성이 있어야 하므로
    // 매 패킷마다 새로 만들지 않고 재사용해야 함.
    private final ConcurrentHashMap<UUID, OpusDecoder> decoders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, OpusEncoder> encoders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PitchShifter> pitchEffects = new ConcurrentHashMap<>();

    private VoicechatApi api;

    public DeruyVoicePitchPlugin(InvisibilityVoiceEffectTracker tracker, VoiceFeatureSettings settings) {
        this.tracker = tracker;
        this.settings = settings;
    }

    @Override
    public String getPluginId() {
        return "deruyplugin-voicepitch";
    }

    @Override
    public void initialize(VoicechatApi api) {
        this.api = api;
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
    }

    private void onMicrophonePacket(MicrophonePacketEvent event) {
        if (!settings.isAutotuneEnabled()) {
            return; // 서버 전체 설정으로 꺼져 있으면 원본 그대로 통과 (즉시 반영됨)
        }

        if (event.getSenderConnection() == null || event.getSenderConnection().getPlayer() == null) {
            return;
        }

        UUID playerId = event.getSenderConnection().getPlayer().getUuid();

        if (!tracker.isActive(playerId)) {
            return; // 투명화 아니면 원본 그대로 통과
        }

        try {
            byte[] opusData = event.getPacket().getOpusEncodedData();
            if (opusData == null || opusData.length == 0) {
                return;
            }

            OpusDecoder decoder = decoders.computeIfAbsent(playerId, id -> api.createDecoder());
            OpusEncoder encoder = encoders.computeIfAbsent(playerId, id -> api.createEncoder());
            PitchShifter pitchEffect = pitchEffects.computeIfAbsent(playerId,
                    id -> new PitchShifter(PITCH_FACTOR, SAMPLE_RATE, BUFFER_SIZE, OVERLAP));

            short[] pcm = decoder.decode(opusData);

            TarsosDSPAudioFormat format = new TarsosDSPAudioFormat(SAMPLE_RATE, 16, 1, true, false);
            AudioEvent audioEvent = new AudioEvent(format);
            audioEvent.setFloatBuffer(shortsToFloats(pcm));

            pitchEffect.process(audioEvent);

            float[] processedFloats = audioEvent.getFloatBuffer();

            // TarsosDSP의 피치시프터는 처리 후 버퍼 길이가 입력과 달라질 수 있는데(WSOLA 알고리즘 특성),
            // Opus 인코더는 정해진 프레임 길이(20ms=960샘플)가 아니면 무음에 가까운 깨진 패킷을 만든다.
            // 그래서 길이를 원본 pcm 길이에 강제로 맞춘 뒤 인코딩한다.
            if (processedFloats == null || processedFloats.length == 0) {
                return; // 처리 실패, 원본 오디오 그대로 통과 (이펙트만 스킵됨)
            }

            short[] processedPcm = floatsToShorts(fixLength(processedFloats, pcm.length));
            byte[] newOpus = encoder.encode(processedPcm);

            if (newOpus == null || newOpus.length == 0) {
                return; // 인코딩 실패, 원본 오디오 그대로 통과
            }

            event.getPacket().setOpusEncodedData(newOpus);
        } catch (Exception e) {
            // 처리 실패해도 목소리가 아예 끊기는 것보단 원본 오디오가 그대로 나가는 게 나음.
            LOGGER.log(Level.WARNING, "투명화 오토튠 이펙트 처리 중 오류 (플레이어: " + playerId + ")", e);
        }
    }

    /**
     * 플레이어가 투명화를 해제했을 때 호출. 코덱/이펙트 상태를 정리해서
     * 메모리 누수를 막고, 다음에 다시 투명화했을 때 새 상태로 시작하게 함.
     */
    public void cleanupPlayer(UUID playerId) {
        decoders.remove(playerId);
        encoders.remove(playerId);
        pitchEffects.remove(playerId);
    }

    private static float[] shortsToFloats(short[] shorts) {
        float[] floats = new float[shorts.length];
        for (int i = 0; i < shorts.length; i++) {
            floats[i] = shorts[i] / 32768f;
        }
        return floats;
    }

    /** 버퍼 길이를 targetLength에 강제로 맞춘다. 부족하면 0으로 채우고, 넘치면 자른다. */
    private static float[] fixLength(float[] input, int targetLength) {
        if (input.length == targetLength) return input;
        float[] result = new float[targetLength];
        System.arraycopy(input, 0, result, 0, Math.min(input.length, targetLength));
        return result;
    }

    private static short[] floatsToShorts(float[] floats) {
        short[] shorts = new short[floats.length];
        for (int i = 0; i < floats.length; i++) {
            float v = Math.max(-1f, Math.min(1f, floats[i]));
            shorts[i] = (short) (v * 32767f);
        }
        return shorts;
    }
}
