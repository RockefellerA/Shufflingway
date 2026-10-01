package shufflingway.net;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Lets a host announce itself on the local network so a joiner can pick it from a list instead of
 * typing an address. The host sends a small UDP broadcast every {@link #FAST_INTERVAL_MS} for the
 * first {@link #FAST_PHASE_MS}, so it shows up quickly, then every {@link #SLOW_INTERVAL_MS}.
 *
 * <p>Each announcement says how long until the next one, and a joiner drops a host that has been
 * silent for {@link #EXPIRY_MISSES} of those intervals. The expiry therefore follows the backoff
 * without the joiner needing to know the schedule.
 *
 * <p>The datagram carries only the host's game port and display name — the address is taken from
 * the packet's source, so it is always one the joiner can actually reach.
 */
public final class LanDiscovery {

    /** UDP port announcements are sent to and listened on. */
    static final int DISCOVERY_PORT = 7778;
    static final long FAST_INTERVAL_MS = 2000;
    static final long FAST_PHASE_MS = 30_000;
    static final long SLOW_INTERVAL_MS = 4000;
    /** How many announcements in a row may be missed before a host is dropped. */
    static final double EXPIRY_MISSES = 2.5;
    /** Bounds on an advertised interval, so a bad packet cannot pin a host in the list forever. */
    private static final long MIN_INTERVAL_MS = 500;
    private static final long MAX_INTERVAL_MS = 60_000;

    /** The interval a host waits after announcing at {@code elapsedMs} into its lobby. */
    static long intervalAfter(long elapsedMs) {
        return elapsedMs < FAST_PHASE_MS ? FAST_INTERVAL_MS : SLOW_INTERVAL_MS;
    }

    /** A decoded announcement: the host, and how long until it announces again. */
    record Announcement(Host host, long intervalMs) {}

    private static final String MAGIC = "shufflingway";

    private LanDiscovery() {}

    /** A host seen on the network. {@code name} is blank when the host has not set one. */
    public record Host(String address, int port, String name) {
        /** What the joiner's list shows: the host's name if set, otherwise its IPv4 address. */
        public String label() { return name == null || name.isBlank() ? address : name; }
        @Override public String toString() { return label(); }
    }

    // ── Wire format ──────────────────────────────────────────────────────

    static byte[] encode(int port, String name, long intervalMs) {
        return new JSONObject().put("app", MAGIC).put("port", port).put("interval", intervalMs)
                .put("name", name == null ? "" : name).toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Decodes an announcement from {@code address}, or returns {@code null} if it is not one. */
    static Announcement decode(byte[] data, int length, String address) {
        try {
            JSONObject o = new JSONObject(new String(data, 0, length, StandardCharsets.UTF_8));
            if (!MAGIC.equals(o.optString("app"))) return null;
            int port = o.getInt("port");
            if (port < 1 || port > 65535) return null;
            long interval = Math.max(MIN_INTERVAL_MS, Math.min(MAX_INTERVAL_MS,
                    o.optLong("interval", SLOW_INTERVAL_MS)));
            return new Announcement(
                    new Host(address, port, shufflingway.AppSettings.clampUsername(o.optString("name", ""))),
                    interval);
        } catch (JSONException e) {
            return null;
        }
    }

    // ── Host side ────────────────────────────────────────────────────────

    /** Handle for a running announcement; {@link #stop()} is idempotent. */
    public static final class Broadcaster {
        private final Thread thread;
        private volatile boolean stopped;

        private Broadcaster(int gamePort, String name) {
            thread = new Thread(() -> run(gamePort, name), "LanDiscovery-broadcast");
            thread.setDaemon(true);
            thread.start();
        }

        private void run(int gamePort, String name) {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setBroadcast(true);
                long start = System.nanoTime();
                while (!stopped) {
                    long interval = intervalAfter((System.nanoTime() - start) / 1_000_000);
                    byte[] payload = encode(gamePort, name, interval);
                    for (InetAddress target : broadcastTargets()) {
                        try {
                            socket.send(new DatagramPacket(payload, payload.length, target, DISCOVERY_PORT));
                        } catch (IOException ignored) {
                            // One unreachable interface must not silence the others.
                        }
                    }
                    try { Thread.sleep(interval); }
                    catch (InterruptedException e) { return; }
                }
            } catch (IOException ignored) {
                // No socket: the host stays reachable by typing its address.
            }
        }

        public void stop() {
            stopped = true;
            thread.interrupt();
        }
    }

    /** Starts announcing a host listening for games on {@code gamePort}. */
    public static Broadcaster startBroadcast(int gamePort, String name) {
        return new Broadcaster(gamePort, name);
    }

    private static List<InetAddress> broadcastTargets() {
        List<InetAddress> targets = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) continue;
                for (InterfaceAddress ia : iface.getInterfaceAddresses()) {
                    if (ia.getBroadcast() != null) targets.add(ia.getBroadcast());
                }
            }
        } catch (IOException ignored) {}
        try { targets.add(InetAddress.getByName("255.255.255.255")); }
        catch (IOException ignored) {}
        return targets;
    }

    // ── Joiner side ──────────────────────────────────────────────────────

    /** Handle for a running listener; {@link #stop()} is idempotent. */
    public static final class Listener {
        private final Thread thread;
        private final Consumer<List<Host>> onChange;
        private volatile boolean stopped;
        private volatile DatagramSocket socket;

        private Listener(Consumer<List<Host>> onChange) {
            this.onChange = onChange;
            thread = new Thread(this::run, "LanDiscovery-listen");
            thread.setDaemon(true);
            thread.start();
        }

        private void run() {
            Map<String, Seen> seen = new LinkedHashMap<>();
            try (DatagramSocket s = new DatagramSocket(null)) {
                socket = s;
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(DISCOVERY_PORT));
                s.setSoTimeout(500);
                byte[] buf = new byte[512];
                List<Host> last = List.of();
                while (!stopped) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        s.receive(p);
                        if (p.getAddress() instanceof Inet4Address) {
                            Announcement a = decode(p.getData(), p.getLength(),
                                    p.getAddress().getHostAddress());
                            if (a != null) seen.put(a.host().address() + ":" + a.host().port(),
                                    new Seen(a.host(), System.currentTimeMillis()
                                            + (long) (a.intervalMs() * EXPIRY_MISSES)));
                        }
                    } catch (SocketTimeoutException ignored) {
                        // Fall through to expire stale hosts.
                    }
                    long now = System.currentTimeMillis();
                    seen.values().removeIf(x -> now > x.expiresAt);
                    List<Host> current = new ArrayList<>();
                    for (Seen x : seen.values()) current.add(x.host);
                    current.sort(Comparator.comparing(Host::label, String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(Host::address));
                    if (!current.equals(last)) {
                        last = current;
                        onChange.accept(Collections.unmodifiableList(current));
                    }
                }
            } catch (IOException ignored) {
                // Port taken or networking unavailable: the list stays empty; manual entry still works.
            }
        }

        public void stop() {
            stopped = true;
            DatagramSocket s = socket;
            if (s != null) s.close();
        }

        private record Seen(Host host, long expiresAt) {}
    }

    /**
     * Starts listening for hosts. {@code onChange} receives the full current list whenever it
     * changes, on the listener thread (marshal to the EDT before touching Swing).
     */
    public static Listener startListening(Consumer<List<Host>> onChange) {
        return new Listener(onChange);
    }
}
