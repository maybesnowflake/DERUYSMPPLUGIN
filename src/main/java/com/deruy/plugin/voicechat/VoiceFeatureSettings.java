package com.deruy.plugin.voicechat;

import com.deruy.plugin.DeruyPlugin;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 투명화 관련 부가 기능(오토튠 음성 이펙트, 킬로그 노이즈 표시)의
 * 서버 전체 온/오프 상태를 관리한다.
 *
 * AtomicBoolean으로 메모리에 즉시 반영되어 인게임 명령어로 켜고 끄면
 * 바로 모든 투명화 유저에게 적용된다. config.yml에도 같이 저장해서
 * 서버 재시작 후에도 마지막 설정이 유지되게 한다.
 *
 * config:
 *   voice-effects.autotune-enabled
 *   voice-effects.killlog-obfuscation-enabled
 */
public class VoiceFeatureSettings {

    private static final String AUTOTUNE_KEY = "voice-effects.autotune-enabled";
    private static final String KILLLOG_KEY = "voice-effects.killlog-obfuscation-enabled";

    private final DeruyPlugin plugin;
    private final AtomicBoolean autotuneEnabled = new AtomicBoolean(true);
    private final AtomicBoolean killLogObfuscationEnabled = new AtomicBoolean(true);

    public VoiceFeatureSettings(DeruyPlugin plugin) {
        this.plugin = plugin;
        autotuneEnabled.set(plugin.getConfig().getBoolean(AUTOTUNE_KEY, true));
        killLogObfuscationEnabled.set(plugin.getConfig().getBoolean(KILLLOG_KEY, true));
    }

    public boolean isAutotuneEnabled() {
        return autotuneEnabled.get();
    }

    public void setAutotuneEnabled(boolean enabled) {
        autotuneEnabled.set(enabled);
        plugin.getConfig().set(AUTOTUNE_KEY, enabled);
        plugin.saveConfig();
    }

    public boolean isKillLogObfuscationEnabled() {
        return killLogObfuscationEnabled.get();
    }

    public void setKillLogObfuscationEnabled(boolean enabled) {
        killLogObfuscationEnabled.set(enabled);
        plugin.getConfig().set(KILLLOG_KEY, enabled);
        plugin.saveConfig();
    }
}
