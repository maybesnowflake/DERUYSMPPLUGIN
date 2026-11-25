package com.deruy.plugin.replay.nms;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * 한 플레이어가 받은 소리·파편·효과 패킷을 다른 녹화(카메라, 그 소리를 못 받은 본인 등)에 나눠 주기 위한 묶음.
 * 서버는 본인이 낸 소리(발소리·휘두르기)는 본인에게 안 보내고, 사람 없는 카메라는 아무것도 못 받으므로
 * 근처 다른 플레이어가 받은 것을 빌려 쓴다. 같은 방송은 받은 사람 모두 같은 바이트라 해시로 중복을 거른다.
 */
public record SoundShare(String dimension, double x, double y, double z, byte[] bytes, long hash) {

    /** 소리 계열 패킷이면 묶음을 만들고, 아니면 null */
    public static SoundShare of(Packet<?> p, byte[] bytes, ServerLevel level) {
        double x, y, z;
        switch (p) {
            case ClientboundSoundPacket s -> { x = s.getX(); y = s.getY(); z = s.getZ(); }
            case ClientboundSoundEntityPacket s -> {
                Entity e = level.getEntity(s.getId());
                if (e == null) return null;
                x = e.getX(); y = e.getY(); z = e.getZ();
            }
            case ClientboundLevelEventPacket s -> { x = s.getPos().getX() + .5; y = s.getPos().getY() + .5; z = s.getPos().getZ() + .5; }
            case ClientboundLevelParticlesPacket s -> { x = s.getX(); y = s.getY(); z = s.getZ(); }
            case ClientboundExplodePacket s -> { x = s.center().x; y = s.center().y; z = s.center().z; }
            default -> { return null; }
        }
        return new SoundShare(level.dimension().identifier().toString(), x, y, z, bytes, hash(bytes));
    }

    public static long hash(byte[] b) {
        long h = 0xcbf29ce484222325L; // FNV-1a 64
        for (byte v : b) { h ^= v; h *= 0x100000001b3L; }
        return h ^ ((long) b.length << 48);
    }

    public boolean near(String dim, double px, double py, double pz, double radius) {
        if (!dimension.equals(dim)) return false;
        double dx = x - px, dy = y - py, dz = z - pz;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }
}
