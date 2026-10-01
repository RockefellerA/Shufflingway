package shufflingway.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LanDiscoveryTest {

    @Test
    void roundTripsNameAndPort() {
        byte[] data = LanDiscovery.encode(7777, "Cloud");
        LanDiscovery.Host h = LanDiscovery.decode(data, data.length, "192.168.1.5");
        assertEquals(new LanDiscovery.Host("192.168.1.5", 7777, "Cloud"), h);
        assertEquals("Cloud", h.label());
    }

    @Test
    void unnamedHostIsLabelledByAddress() {
        byte[] data = LanDiscovery.encode(7777, "");
        assertEquals("10.0.0.2", LanDiscovery.decode(data, data.length, "10.0.0.2").label());
    }

    @Test
    void ignoresForeignOrMalformedPackets() {
        byte[] junk = "hello".getBytes();
        assertNull(LanDiscovery.decode(junk, junk.length, "10.0.0.2"));
        byte[] other = "{\"app\":\"other\",\"port\":1}".getBytes();
        assertNull(LanDiscovery.decode(other, other.length, "10.0.0.2"));
    }
}
