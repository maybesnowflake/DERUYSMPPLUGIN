package com.deruy.plugin;

import com.deruy.plugin.events.koth.KothZone;
import org.bukkit.Location;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import static org.junit.jupiter.api.Assertions.*;

class KothZoneWorldTest {
    @AfterEach void cleanup() { MockBukkit.unmock(); }
    @Test void cornersInDifferentWorldsAreNotAZone() {
        var server = MockBukkit.mock();
        var a = server.addSimpleWorld("a"); var b = server.addSimpleWorld("b");
        var zone = new KothZone("1");
        zone.setCorner1(new Location(a, 0, 60, 0));
        zone.setCorner2(new Location(b, 10, 70, 10));
        assertFalse(zone.isComplete()); assertTrue(zone.cornersInDifferentWorlds());
        assertFalse(zone.contains(new Location(a, 5, 65, 5)));
        zone.setCorner2(new Location(a, 10, 70, 10));
        assertTrue(zone.isComplete()); assertTrue(zone.contains(new Location(a, 5, 65, 5)));
    }
}
