package com.deruy.plugin.voicechat;

import com.deruy.plugin.DeruyPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.potion.PotionEffectType;

/**
 * 투명화 상태인 플레이어가 다른 플레이어를 죽였을 때,
 * 바닐라 사망 메시지(킬로그)에 나오는 킬러 이름 부분만
 * 하얀색 노이즈(§k obfuscated) 문자로 가려서 표시한다.
 *
 * 메시지의 나머지 부분(사망 원인, 무기 이름 등)은 그대로 유지되고
 * 킬러 이름 텍스트만 정확히 치환된다 (Adventure Component#replaceText 사용).
 *
 * VoiceFeatureSettings.isKillLogObfuscationEnabled()로 서버 전체 온/오프 제어.
 */
public class KillLogObfuscationListener implements Listener {

    private final DeruyPlugin plugin;

    public KillLogObfuscationListener(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!plugin.getVoiceFeatureSettings().isKillLogObfuscationEnabled()) {
            return;
        }

        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return; // 플레이어에게 죽은 게 아니면(몹, 환경사 등) 처리 안 함
        }
        if (!killer.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
            return;
        }

        Component original = event.deathMessage();
        if (original == null) {
            return; // 사망 메시지가 비활성화되어 있으면 할 게 없음
        }

        String killerName = killer.getName();
        Component obfuscatedName = Component.text(killerName)
                .color(NamedTextColor.WHITE)
                .decorate(TextDecoration.OBFUSCATED);

        Component replaced = original.replaceText(builder -> builder
                .matchLiteral(killerName)
                .replacement(obfuscatedName));

        event.deathMessage(replaced);
    }
}
