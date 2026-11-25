package com.deruy.plugin.misc;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 실제 접속자 목록. 녹화 카메라용 가짜 관전자(CameraEar)는 서버에선 플레이어지만
 * 이벤트 추첨·보스바·전투 태그 같은 기능에는 끼면 안 되므로 여기서 뺀다.
 * 플러그인 코드는 Bukkit.getOnlinePlayers() 대신 이걸 쓴다.
 */
public final class Online {
    private static final Set<UUID> FAKE = ConcurrentHashMap.newKeySet();

    private Online() {}

    public static Collection<? extends Player> players() {
        Collection<? extends Player> all = Bukkit.getOnlinePlayers();
        if (FAKE.isEmpty()) return all;
        return all.stream().filter(p -> !FAKE.contains(p.getUniqueId())).toList();
    }

    public static boolean isFake(Player p) { return p != null && FAKE.contains(p.getUniqueId()); }
    public static boolean isFake(UUID id) { return FAKE.contains(id); }
    public static void markFake(UUID id) { FAKE.add(id); }
    public static void unmarkFake(UUID id) { FAKE.remove(id); }
}
