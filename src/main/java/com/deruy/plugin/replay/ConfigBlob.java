package com.deruy.plugin.replay;

import com.deruy.plugin.replay.format.FbOut;
import com.deruy.plugin.replay.format.FlashbackFormat;
import com.deruy.plugin.replay.nms.Codecs;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.network.protocol.configuration.ClientboundUpdateEnabledFeaturesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.world.flag.FeatureFlags;

import java.util.Set;

/** 모든 스냅샷 앞에 들어가는 설정 단계 묶음(기능 플래그·레지스트리 전체·태그). 서버당 한 번만 만든다. */
final class ConfigBlob {
    private ConfigBlob() {}

    static byte[] build(MinecraftServer server, Codecs codecs) {
        FbOut out = new FbOut(1 << 18);
        FlashbackFormat.action(out, FlashbackFormat.ID_CONFIGURATION_PACKET, codecs.config(
                new ClientboundUpdateEnabledFeaturesPacket(FeatureFlags.REGISTRY.toNames(server.getWorldData().enabledFeatures()))));
        var registries = server.registries();
        var ops = registries.compositeAccess().createSerializationContext(NbtOps.INSTANCE);
        // 알려진 팩 없이(빈 Set) 보내면 리플레이 쪽이 자체 데이터 없이도 그대로 쓸 수 있다
        RegistrySynchronization.packRegistries(ops, registries.getAccessFrom(RegistryLayer.WORLDGEN), Set.of(),
                (key, entries) -> FlashbackFormat.action(out, FlashbackFormat.ID_CONFIGURATION_PACKET,
                        codecs.config(new ClientboundRegistryDataPacket(key, entries))));
        FlashbackFormat.action(out, FlashbackFormat.ID_CONFIGURATION_PACKET,
                codecs.config(new ClientboundUpdateTagsPacket(TagNetworkSerialization.serializeTagsToNetwork(registries))));
        return out.toByteArray();
    }
}
