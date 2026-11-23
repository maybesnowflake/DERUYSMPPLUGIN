package com.deruy.plugin.randomevent;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RouletteMathTest {
    final List<String> pool = List.of("A", "B", "C", "D", "E");

    @Test void stopsExactlyOnWinner() {
        for (int seed = 0; seed < 200; seed++) {
            Random r = new Random(seed);
            String winner = pool.get(r.nextInt(pool.size()));
            int spin = 100, total = RouletteMath.totalSteps(spin, 2, 16);
            List<String> strip = RouletteMath.strip(pool, winner, total, 8, r);
            double end = RouletteMath.position(spin, spin, total);
            assertEquals(total, end);
            int center = (int) Math.floor(end + .5);
            assertEquals(winner, strip.get(center + 8));
            assertNotEquals(winner, strip.get(center + 8 - 1), "바로 위 칸은 당첨과 달라야 함");
            assertNotEquals(winner, strip.get(center + 8 + 1), "바로 아래 칸은 당첨과 달라야 함");
        }
    }

    @Test void positionDeceleratesMonotonically() {
        int spin = 100, total = 17;
        double prev = 0, prevSpeed = Double.MAX_VALUE;
        for (int t = 1; t <= spin; t++) {
            double p = RouletteMath.position(t, spin, total);
            double speed = p - prev;
            assertTrue(speed >= 0, "뒤로 가면 안 됨");
            assertTrue(speed <= prevSpeed + 1e-9, "점점 느려져야 함");
            assertTrue(speed <= 1.0, "한 틱에 한 칸 넘게 건너뛰면 안 보임");
            prev = p; prevSpeed = speed;
        }
        assertEquals(0, RouletteMath.position(0, spin, total));
    }

    @Test void stripCoversEveryVisibleIndex() {
        int total = 20, pad = 8;
        List<String> s = RouletteMath.strip(pool, "C", total, pad, new Random(1));
        assertEquals(total + 2 * pad + 1, s.size());
        // 화면에 보이는 k 범위: -3 .. total+4
        assertTrue(-3 + pad >= 0 && total + 4 + pad < s.size());
    }

    @Test void singleEventPool() {
        List<String> s = RouletteMath.strip(List.of("ONLY"), "ONLY", 16, 8, new Random());
        assertTrue(s.stream().allMatch("ONLY"::equals));
    }

    @Test void weightedPickRespectsWeights() {
        Map<String, Double> w = Map.of("A", 0.0, "B", 3.0, "C", 1.0, "D", 0.0, "E", 0.0);
        int b = 0, c = 0;
        for (int i = 0; i < 4000; i++) {
            String x = RouletteMath.weightedPick(pool, w::get, i / 4000.0);
            if (x.equals("B")) b++; else if (x.equals("C")) c++; else fail("가중치 0 당첨: " + x);
        }
        assertEquals(3000, b);
        assertEquals(1000, c);
    }

    @Test void allZeroWeightsFallBackToUniform() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5; i++) seen.add(RouletteMath.weightedPick(pool, x -> 0, (i + .5) / 5));
        assertEquals(new HashSet<>(pool), seen);
    }

    @Test void sizeCurvePeaksAtCenter() {
        assertEquals(2f, EventRoulette.sizeAt(4), 1e-6);
        assertEquals(1f, EventRoulette.sizeAt(-0.5), 1e-6);
        assertEquals(1f, EventRoulette.sizeAt(7.9), 1e-6);
        assertTrue(EventRoulette.sizeAt(3.5) > EventRoulette.sizeAt(3));
    }
}
