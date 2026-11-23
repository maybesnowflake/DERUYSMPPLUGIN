package com.deruy.plugin.transport;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LootSpillTest {
    private static List<Integer> loot(int n) { List<Integer> l = new ArrayList<>(); for (int i = 0; i < n; i++) l.add(i); return l; }

    @Test void spillsFractionRoundedAtLeastOne() {
        var remaining = loot(100);
        var out = TransportRaid.takeSpill(remaining, 0.15, new Random(1));
        assertEquals(15, out.size());
        assertEquals(85, remaining.size(), "쏟은 만큼 남은 보물이 줄어듦");
        var few = loot(2);
        assertEquals(1, TransportRaid.takeSpill(few, 0.15, new Random(1)).size(), "최소 1개");
    }

    @Test void noSpillWhenEmptyOrZero() {
        assertTrue(TransportRaid.takeSpill(new ArrayList<Integer>(), 0.5, new Random()).isEmpty());
        var l = loot(10);
        assertTrue(TransportRaid.takeSpill(l, 0, new Random()).isEmpty());
        assertEquals(10, l.size());
    }

    @Test void neverDuplicatesOrLoses() {
        var remaining = loot(50);
        Set<Integer> seen = new HashSet<>();
        for (int stage = 0; stage < 3; stage++) seen.addAll(TransportRaid.takeSpill(remaining, 0.15, new Random(stage)));
        seen.addAll(remaining);
        assertEquals(50, seen.size(), "쏟은 것 + 남은 것 = 원래 보물 (복사·분실 없음)");
    }

    @Test void configCopyIsNotTouched() {
        List<Integer> config = List.copyOf(loot(20));
        var perRun = new ArrayList<>(config);
        TransportRaid.takeSpill(perRun, 0.5, new Random());
        assertEquals(20, config.size(), "다음 수송은 원래 개수로 시작");
    }
}
