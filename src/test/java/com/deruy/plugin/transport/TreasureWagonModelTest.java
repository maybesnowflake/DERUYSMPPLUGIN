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
        assertEquals(1, parts.size());
        var p = parts.getFirst();
        assertEquals("item", p.kind());
        assertEquals("deruy:treasure_wagon", p.model());
        assertEquals(3.0f, p.y(), 1e-6);
        assertEquals(2.0f, p.sx(), 1e-6);
    }

    @Test void blockFallbackStillLoads() throws IOException {
        assertEquals(238, WagonModel.read(resource("transport-model-blocks.yml")).size());
    }

    @Test void packModelMatchesItemModelKey() throws IOException {
        Path root = Path.of("resourcepack/DERUY_TreasureWagon");
        String item = Files.readString(root.resolve("assets/deruy/items/treasure_wagon.json"));
        assertTrue(item.contains("deruy:item/treasure_wagon"));
        String model = Files.readString(root.resolve("assets/deruy/models/item/treasure_wagon.json"));
        assertTrue(model.contains("\"elements\""));
        assertTrue(Files.exists(root.resolve("assets/deruy/textures/item/heart.png")));
        assertTrue(Files.readString(root.resolve("pack.mcmeta")).contains("75"));
    }
}
