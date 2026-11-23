package com.deruy.plugin.transport;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WagonMotionTest {
    private File resource(String name) throws IOException {
        Path tmp = Files.createTempFile("model", ".yml");
        try (InputStream in = getClass().getResourceAsStream("/" + name)) { Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING); }
        return tmp.toFile();
    }

    @Test void wheelSpinMatchesDistance() {
        // 반지름 0.5 바퀴가 π 블록 굴러가면 한 바퀴(360도) → 0
        assertEquals(0, WagonModel.spinDegrees(Math.PI, .5, 1), 1e-3);
        assertEquals(90, WagonModel.spinDegrees(Math.PI / 4, .5, 1), 1e-3);
        assertEquals(-90, WagonModel.spinDegrees(Math.PI / 4, .5, -1), 1e-3);
        assertEquals(0, WagonModel.spinDegrees(0, .5, 1), 1e-6, "멈추면 회전 없음");
    }

    @Test void wobbleGrowsWithDamage() throws IOException {
        var motion = WagonModel.readMotion(resource("transport-model.yml"));
        float healthy = motion.wobbleFor(-1), s75 = motion.wobbleFor(0), s50 = motion.wobbleFor(1), s25 = motion.wobbleFor(2);
        assertTrue(healthy < s75 && s75 < s50 && s50 < s25, "손상될수록 더 흔들림");
        float max = 0;
        for (double d = 0; d < 20; d += .05) max = Math.max(max, Math.abs(WagonModel.wobble(d, s25)[1]));
        assertTrue(max <= s25 + 1e-4 && max > s25 * .9, "진폭 범위 안");
        assertArrayEquals(new float[]{0, 0}, WagonModel.wobble(3, 0));
    }

    @Test void stagePartOverrides() throws IOException {
        var parts = WagonModel.read(resource("transport-model.yml"));
        var stages = WagonModel.readStages(resource("transport-model.yml"));
        var body = parts.get(0);
        var fl = parts.stream().filter(p -> p.name().equals("wheel-fl")).findFirst().orElseThrow();
        var br = parts.stream().filter(p -> p.name().equals("wheel-br")).findFirst().orElseThrow();
        assertFalse(body.spin()); assertTrue(fl.spin());
        assertEquals("deruy:treasure_wagon_body", WagonModel.modelFor(body, null));
        assertEquals("deruy:wagon_wheel", WagonModel.modelFor(br, null));
        assertEquals("deruy:treasure_wagon_75_body", WagonModel.modelFor(body, stages.get(0)));
        assertEquals("deruy:wagon_wheel_b1", WagonModel.modelFor(br, stages.get(0)));
        assertEquals("deruy:wagon_wheel", WagonModel.modelFor(fl, stages.get(1)), "덮어쓰기 없는 바퀴는 그대로");
        assertNull(WagonModel.modelFor(fl, stages.get(2)), "25%에서 앞왼쪽 바퀴 없음");
    }

    @Test void spinOnlyRotatesWheelInPlace() throws IOException {
        var fl = WagonModel.read(resource("transport-model.yml")).stream().filter(p -> p.name().equals("wheel-fl")).findFirst().orElseThrow();
        var a = fl.matrix(0, 0, 0, 0).getTranslation(new org.joml.Vector3f());
        var b = fl.matrix(0, 0, 0, 137).getTranslation(new org.joml.Vector3f());
        assertEquals(a.x, b.x, 1e-5); assertEquals(a.y, b.y, 1e-5); assertEquals(a.z, b.z, 1e-5);
    }

    @Test void blockFallbackHasNoSpinAndNoMotion() throws IOException {
        var parts = WagonModel.read(resource("transport-model-blocks.yml"));
        assertTrue(parts.stream().noneMatch(WagonModel.Part::spin));
        assertSame(WagonModel.Motion.NONE, WagonModel.readMotion(resource("transport-model-blocks.yml")));
        var blocks = new WagonModel(parts, List.of());
        assertFalse(blocks.animated(), "블록판은 이동마다 변환 갱신 안 함");
    }

    @Test void newModelIsAnimated() throws IOException {
        var f = resource("transport-model.yml");
        assertTrue(new WagonModel(WagonModel.read(f), WagonModel.readStages(f)).animated());
    }

    @Test void packHasAllReferencedModels() throws IOException {
        var root = Path.of("resourcepack/DERUY_TreasureWagon/assets/deruy/items");
        String yml = Files.readString(resource("transport-model.yml").toPath());
        var m = java.util.regex.Pattern.compile("deruy:([a-z0-9_]+)").matcher(yml);
        int n = 0;
        while (m.find()) { n++; assertTrue(Files.exists(root.resolve(m.group(1) + ".json")), m.group(1)); }
        assertTrue(n >= 8);
    }
}
