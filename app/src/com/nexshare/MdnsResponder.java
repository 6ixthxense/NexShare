package com.nexshare;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.Locale;

/**
 * Tiny mDNS responder that answers A queries for "nexshare.local" with this phone's LAN IPv4,
 * so other devices on the same Wi-Fi can open http://nexshare.local:8080.
 */
public class MdnsResponder implements Runnable {

    public static final String HOST = "nexshare.local";
    private static final int PORT = 5353;

    private final Context ctx;
    private volatile boolean running;
    private MulticastSocket sock;
    private InetAddress group;
    private WifiManager.MulticastLock lock;

    public MdnsResponder(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public void start() {
        running = true;
        WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            lock = wm.createMulticastLock("nexshare-mdns");
            lock.setReferenceCounted(false);
            lock.acquire();
        }
        new Thread(this, "mdns").start();
    }

    public void stop() {
        running = false;
        if (sock != null) sock.close();
        if (lock != null && lock.isHeld()) lock.release();
    }

    @Override
    public void run() {
        while (running) {
            try {
                group = InetAddress.getByName("224.0.0.251");
                sock = new MulticastSocket(null);
                sock.setReuseAddress(true);
                sock.bind(new InetSocketAddress(PORT));
                sock.setTimeToLive(255);
                sock.setSoTimeout(20000);
                joinAll();
                announce();
                byte[] buf = new byte[9000];
                while (running) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        sock.receive(p);
                    } catch (SocketTimeoutException e) {
                        joinAll(); // pick up interfaces that came up after start (Wi-Fi reconnects)
                        continue;
                    }
                    try {
                        handle(p);
                    } catch (Exception ignored) { }
                }
            } catch (Exception e) {
                if (sock != null) sock.close();
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private void joinAll() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast() || ipv4Of(ni) == null) continue;
                try {
                    sock.joinGroup(new InetSocketAddress(group, PORT), ni);
                } catch (Exception ignored) { } // already joined
            }
        } catch (Exception ignored) { }
    }

    private void announce() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                Inet4Address ip = ipv4Of(ni);
                if (!ni.isUp() || ni.isLoopback() || ip == null) continue;
                byte[] pkt = buildAnswer(0, null, ip, true);
                sock.setNetworkInterface(ni);
                sock.send(new DatagramPacket(pkt, pkt.length, group, PORT));
            }
        } catch (Exception ignored) { }
    }

    private void handle(DatagramPacket p) throws Exception {
        byte[] d = p.getData();
        int len = p.getLength();
        if (len < 12) return;
        int id = ((d[0] & 0xff) << 8) | (d[1] & 0xff);
        int flags = ((d[2] & 0xff) << 8) | (d[3] & 0xff);
        if ((flags & 0x8000) != 0) return; // a response, not a query
        int qd = ((d[4] & 0xff) << 8) | (d[5] & 0xff);
        int[] pos = {12};
        for (int i = 0; i < qd && pos[0] < len; i++) {
            String name = readName(d, len, pos);
            if (pos[0] + 4 > len) return;
            int qtype = ((d[pos[0]] & 0xff) << 8) | (d[pos[0] + 1] & 0xff);
            int qclass = ((d[pos[0] + 2] & 0xff) << 8) | (d[pos[0] + 3] & 0xff);
            pos[0] += 4;
            if (!HOST.equals(name.toLowerCase(Locale.ROOT))) continue;
            if (qtype != 1 && qtype != 255) continue;
            Inet4Address src = p.getAddress() instanceof Inet4Address ? (Inet4Address) p.getAddress() : null;
            Inet4Address ip = pickAddress(src);
            if (ip == null) return;
            boolean legacy = p.getPort() != PORT;
            boolean unicast = legacy || (qclass & 0x8000) != 0;
            byte[] pkt = buildAnswer(legacy ? id : 0, legacy ? HOST : null, ip, !legacy);
            if (unicast) {
                sock.send(new DatagramPacket(pkt, pkt.length, p.getAddress(), p.getPort()));
            } else {
                NetworkInterface ni = NetworkInterface.getByInetAddress(ip);
                if (ni != null) sock.setNetworkInterface(ni);
                sock.send(new DatagramPacket(pkt, pkt.length, group, PORT));
            }
            return;
        }
    }

    private static String readName(byte[] d, int len, int[] pos) {
        StringBuilder sb = new StringBuilder();
        int p = pos[0];
        boolean jumped = false;
        int guard = 0;
        while (p < len && guard++ < 64) {
            int l = d[p] & 0xff;
            if (l == 0) {
                p++;
                break;
            }
            if ((l & 0xc0) == 0xc0) {
                int ptr = ((l & 0x3f) << 8) | (d[p + 1] & 0xff);
                if (!jumped) pos[0] = p + 2;
                jumped = true;
                p = ptr;
                continue;
            }
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(d, p + 1, Math.min(l, len - p - 1), java.nio.charset.StandardCharsets.UTF_8));
            p += l + 1;
        }
        if (!jumped) pos[0] = p;
        return sb.toString();
    }

    private static byte[] buildAnswer(int id, String question, Inet4Address ip, boolean cacheFlush) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        writeShort(o, id);
        writeShort(o, 0x8400); // response, authoritative
        writeShort(o, question != null ? 1 : 0);
        writeShort(o, 1);
        writeShort(o, 0);
        writeShort(o, 0);
        if (question != null) {
            writeName(o, question);
            writeShort(o, 1);
            writeShort(o, 1);
        }
        writeName(o, HOST);
        writeShort(o, 1); // A
        writeShort(o, cacheFlush ? 0x8001 : 0x0001);
        writeShort(o, 0);
        writeShort(o, 120); // TTL
        writeShort(o, 4);
        byte[] a = ip.getAddress();
        o.write(a, 0, 4);
        return o.toByteArray();
    }

    private static void writeName(ByteArrayOutputStream o, String name) {
        for (String label : name.split("\\.")) {
            byte[] b = label.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            o.write(b.length);
            o.write(b, 0, b.length);
        }
        o.write(0);
    }

    private static void writeShort(ByteArrayOutputStream o, int v) {
        o.write((v >> 8) & 0xff);
        o.write(v & 0xff);
    }

    private static Inet4Address ipv4Of(NetworkInterface ni) {
        for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
            if (ia.getAddress() instanceof Inet4Address && !ia.getAddress().isLoopbackAddress())
                return (Inet4Address) ia.getAddress();
        }
        return null;
    }

    /** The local IPv4 on the same subnet as the asker, falling back to the best LAN address. */
    static Inet4Address pickAddress(Inet4Address peer) {
        try {
            if (peer != null) {
                int pv = toInt(peer);
                for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                        if (!(ia.getAddress() instanceof Inet4Address)) continue;
                        int prefix = ia.getNetworkPrefixLength();
                        int mask = prefix == 0 ? 0 : -1 << (32 - prefix);
                        if ((toInt((Inet4Address) ia.getAddress()) & mask) == (pv & mask))
                            return (Inet4Address) ia.getAddress();
                    }
                }
            }
        } catch (Exception ignored) { }
        return lanAddress();
    }

    /** Prefers Wi-Fi, then hotspot, then any private IPv4. */
    public static Inet4Address lanAddress() {
        Inet4Address any = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                Inet4Address ip = ipv4Of(ni);
                if (ip == null) continue;
                String n = ni.getName();
                if (n.startsWith("wlan")) return ip;
                if (n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("eth")) any = ip;
                else if (any == null && ip.isSiteLocalAddress()) any = ip;
            }
        } catch (Exception ignored) { }
        return any;
    }

    private static int toInt(Inet4Address a) {
        byte[] b = a.getAddress();
        return ((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16) | ((b[2] & 0xff) << 8) | (b[3] & 0xff);
    }
}
