package shufflingway.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LanDiscoveryTest {

    @Test
    void roundTripsNameAndPort() {
        byte[] data = LanDiscovery.encode(7777, "Cloud", 2000);
        LanDiscovery.Announcement a = LanDiscovery.decode(data, data.length, "192.168.1.5");
        assertEquals(new LanDiscovery.Host("192.168.1.5", 7777, "Cloud"), a.host());
        assertEquals(2000, a.intervalMs());
        assertEquals("Cloud", a.host().label());
    }

    @Test
    void unnamedHostIsLabelledByAddress() {
        byte[] data = LanDiscovery.encode(7777, "", 2000);
        assertEquals("10.0.0.2", LanDiscovery.decode(data, data.length, "10.0.0.2").host().label());
    }

    @Test
    void ignoresForeignOrMalformedPackets() {
        byte[] junk = "hello".getBytes();
        assertNull(LanDiscovery.decode(junk, junk.length, "10.0.0.2"));
        byte[] other = "{\"app\":\"other\",\"port\":1}".getBytes();
        assertNull(LanDiscovery.decode(other, other.length, "10.0.0.2"));
    }

    @Test
    void intervalBacksOffAfterFastPhase() {
        assertEquals(2000, LanDiscovery.intervalAfter(0));
        assertEquals(2000, LanDiscovery.intervalAfter(29_999));
        assertEquals(4000, LanDiscovery.intervalAfter(30_000));
    }

    @Test
    void advertisedIntervalIsClamped() {
        byte[] huge = LanDiscovery.encode(7777, "", 10_000_000);
        assertEquals(60_000, LanDiscovery.decode(huge, huge.length, "10.0.0.2").intervalMs());
        byte[] tiny = LanDiscovery.encode(7777, "", 1);
        assertEquals(500, LanDiscovery.decode(tiny, tiny.length, "10.0.0.2").intervalMs());
    }
}
