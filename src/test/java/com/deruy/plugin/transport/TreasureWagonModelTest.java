package com.deruy.plugin.transport;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class TreasureWagonModelTest {
    private File resource(String name) throws IOException {
        Path tmp = Files.createTempFile("model", ".yml");
        try (InputStream in = getClass().getResourceAsStream("/" + name)) { Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING); }
        return tmp.toFile();
    }

    @Test void defaultModelIsResourcePackItem() throws IOException {
        var parts = WagonModel.read(resource("transport-model.yml"));
        assertEquals(5, parts.size(), "몸체 + 바퀴 4");
        var p = parts.getFirst();
        assertEquals("item", p.kind());
        assertEquals("deruy:treasure_wagon_body", p.model());
        assertEquals(3.0f, p.y(), 1e-6);
        assertEquals(2.0f, p.sx(), 1e-6);
    }

    @Test void damageStagesPickByHealth() throws IOException {
        var stages = WagonModel.readStages(resource("transport-model.yml"));
        assertEquals(3, stages.size());
        var m = new WagonModel(WagonModel.read(resource("transport-model.yml")), stages);
        assertEquals(-1, m.stageFor(1.0));
        assertEquals(-1, m.stageFor(0.76));
        assertEquals("deruy:treasure_wagon_75_body", stages.get(m.stageFor(0.75)).model());
        assertEquals("deruy:treasure_wagon_50_body", stages.get(m.stageFor(0.5)).model());
        assertEquals("deruy:treasure_wagon_50_body", stages.get(m.stageFor(0.3)).model());
        assertEquals("deruy:treasure_wagon_25_body", stages.get(m.stageFor(0.01)).model());
    }

    @Test void tiltZeroKeepsOldMatrix() throws IOException {
        var p = WagonModel.read(resource("transport-model.yml")).getFirst();
        assertTrue(p.matrix().equals(p.matrix(0, 0, 0), 1e-6f));
    }

    @Test void blockFallbackStillLoads() throws IOException {
        assertEquals(238, WagonModel.read(resource("transport-model-blocks.yml")).size());
    }

    @Test void packModelMatchesItemModelKey() throws IOException {
        Path root = Path.of("resourcepack/DERUY_TreasureWagon");
        for (String n : new String[]{"treasure_wagon", "treasure_wagon_75", "treasure_wagon_50", "treasure_wagon_25"}) {
            String item = Files.readString(root.resolve("assets/deruy/items/" + n + ".json"));
            assertTrue(item.contains("deruy:item/" + n + "\""), n);
            assertTrue(Files.exists(root.resolve("assets/deruy/models/item/" + n + ".json")), n);
        }
        String model = Files.readString(root.resolve("assets/deruy/models/item/treasure_wagon.json"));
        assertTrue(model.contains("\"elements\""));
        // 바닐라 텍스처만 사용(팩에 그림 파일 없음), 아틀라스에 아이템·하트 텍스처 등록
        String atlas = Files.readString(root.resolve("assets/minecraft/atlases/blocks.json"));
        assertTrue(atlas.contains("minecraft:gui/sprites/hud/heart/hardcore_full"));
        assertTrue(atlas.contains("minecraft:item/nether_star"));
        assertTrue(Files.readString(root.resolve("pack.mcmeta")).contains("75"));
    }
}
