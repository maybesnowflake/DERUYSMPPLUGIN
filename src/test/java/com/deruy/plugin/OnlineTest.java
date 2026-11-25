package com.deruy.plugin;

import com.deruy.plugin.misc.Online;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OnlineTest {
    @Test void fakeMarksAreTrackedAndCleared() {
        UUID id = UUID.randomUUID();
        assertFalse(Online.isFake(id));
        Online.markFake(id);
        assertTrue(Online.isFake(id), "카메라 관전자는 실제 접속자에서 빠져야 함");
        Online.unmarkFake(id);
        assertFalse(Online.isFake(id));
        assertFalse(Online.isFake((org.bukkit.entity.Player) null));
    }
}
