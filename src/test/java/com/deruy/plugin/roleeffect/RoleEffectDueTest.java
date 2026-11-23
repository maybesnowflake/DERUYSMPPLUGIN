package com.deruy.plugin.roleeffect;

import org.junit.jupiter.api.Test;
import java.time.LocalTime;
import static org.junit.jupiter.api.Assertions.*;

class RoleEffectDueTest {
    @Test void exactMinuteOnFirstCheck() {
        assertTrue(RoleEffectScheduler.due(LocalTime.of(9, 0), null, LocalTime.of(9, 0, 30)));
        assertFalse(RoleEffectScheduler.due(LocalTime.of(9, 0), null, LocalTime.of(9, 5)), "재시작 후 지난 시각을 몰아 주지 않음");
    }
    @Test void lagSpanningTargetStillFires() {
        assertTrue(RoleEffectScheduler.due(LocalTime.of(9, 0), LocalTime.of(8, 59, 40), LocalTime.of(9, 1, 20)));
        assertFalse(RoleEffectScheduler.due(LocalTime.of(9, 0), LocalTime.of(9, 1, 20), LocalTime.of(9, 1, 40)));
    }
    @Test void newDayStartsFromMidnight() {
        assertTrue(RoleEffectScheduler.due(LocalTime.of(0, 0), LocalTime.MIDNIGHT, LocalTime.of(0, 0, 10)));
        assertFalse(RoleEffectScheduler.due(LocalTime.of(23, 59), LocalTime.MIDNIGHT, LocalTime.of(0, 0, 10)));
    }
}
