package com.nexshare;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Unit tests for the Android-independent core (HTTP server, crypto, TLS cert generation,
 * path safety). Plain Java, no JUnit, so they run anywhere a JDK is available:
 *
 *   ./test/run-tests.sh
 */
public final class Tests {

    private static int passed, failed;
    private static final List<String> failures = new ArrayList<>();

    interface Body {
        void run() throws Exception;
    }

    static void test(String name, Body b) {
        try {
            b.run();
            passed++;
            System.out.println("  ok   " + name);
        } catch (Throwable t) {
            failed++;
            failures.add(name + ": " + t);
            System.out.println("  FAIL " + name + " — " + t);
        }
    }

    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    static void eq(Object want, Object got) {
        if (want == null ? got != null : !want.equals(got)) throw new AssertionError("expected <" + want + "> but got <" + got + ">");
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Crypto");
        cryptoTests();
        System.out.println("Paths");
        pathTests();
        System.out.println("HttpServer");
        httpTests();
        System.out.println("TLS");
        tlsTests();
        IntegrationTests.run();
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        for (String f : failures) System.out.println("  " + f);
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---------------------------------------------------------------- crypto

    static void cryptoTests() {
        test("TOTP matches RFC 6238 SHA1 vectors", () -> {
            byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
            // RFC 6238 appendix B uses 8 digits; the 6-digit codes are the last 6 digits.
            eq("287082", Crypto.totp(secret, 59 / 30));
            eq("081804", Crypto.totp(secret, 1111111109L / 30));
            eq("050471", Crypto.totp(secret, 1111111111L / 30));
            eq("005924", Crypto.totp(secret, 1234567890L / 30));
            eq("279037", Crypto.totp(secret, 2000000000L / 30));
        });
        test("TOTP verify accepts +/-1 step, rejects others", () -> {
            String b32 = Crypto.base32Encode("12345678901234567890".getBytes(StandardCharsets.US_ASCII));
            long t = 1111111109L * 1000;
            check(Crypto.verifyTotp(b32, "081804", t) >= 0, "current");
            check(Crypto.verifyTotp(b32, "081804", t + 30000) >= 0, "previous step");
            check(Crypto.verifyTotp(b32, "081804", t + 90000) < 0, "too old");
            check(Crypto.verifyTotp(b32, "08180", t) < 0, "short");
            check(Crypto.verifyTotp(b32, "abcdef", t) < 0, "non-digit");
        });
        test("base32 round trip", () -> {
            byte[] d = Crypto.random(20);
            check(Arrays.equals(d, Crypto.base32Decode(Crypto.base32Encode(d))), "round trip");
            eq("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Crypto.base32Encode("12345678901234567890".getBytes(StandardCharsets.US_ASCII)));
        });
        test("base64 matches JDK", () -> {
            for (int n = 0; n < 40; n++) {
                byte[] d = Crypto.random(n);
                eq(java.util.Base64.getEncoder().encodeToString(d), Crypto.base64Encode(d));
                check(Arrays.equals(d, Crypto.base64Decode(Crypto.base64Encode(d))), "decode " + n);
            }
        });
        test("password hashing", () -> {
            String h = Crypto.hashPassword("correct horse");
            check(h.startsWith("pbkdf2$"), "format");
            check(Crypto.verifyPassword("correct horse", h), "good password");
            check(!Crypto.verifyPassword("correct hors", h), "bad password");
            check(!Crypto.verifyPassword("x", "garbage"), "bad hash");
            check(!h.equals(Crypto.hashPassword("correct horse")), "salted");
        });
        test("constant-time equals", () -> {
            check(Crypto.constantTimeEquals("123456", "123456"), "same");
            check(!Crypto.constantTimeEquals("123456", "123457"), "different");
            check(!Crypto.constantTimeEquals(null, "1"), "null");
        });
        test("WebSocket accept key (RFC 6455 example)", () ->
            eq("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", HttpServer.webSocketAccept("dGhlIHNhbXBsZSBub25jZQ==")));
    }

    // ---------------------------------------------------------------- paths

    static void pathTests() {
        test("within() keeps paths inside the roots", () -> {
            File tmp = Files.createTempDirectory("ns").toFile().getCanonicalFile();
            File root = new File(tmp, "storage");
            File sub = new File(root, "DCIM");
            sub.mkdirs();
            File outside = new File(tmp, "storage-evil");
            outside.mkdirs();
            List<String> roots = Collections.singletonList(root.getPath());
            eq(sub.getPath(), Storage.within(sub.getPath(), roots).getPath());
            eq(root.getPath(), Storage.within(root.getPath(), roots).getPath());
            eq(sub.getPath(), Storage.within(root.getPath() + "/x/../DCIM", roots).getPath());
            for (String bad : new String[]{root + "/..", root + "/../storage-evil", outside.getPath(), "/etc/passwd", "", root + "/a\0b"}) {
                try {
                    Storage.within(bad, roots);
                    throw new AssertionError("accepted " + bad);
                } catch (SecurityException expected) { }
            }
        });
        test("within() resolves symlinks before checking", () -> {
            File tmp = Files.createTempDirectory("ns").toFile().getCanonicalFile();
            File root = new File(tmp, "storage");
            root.mkdirs();
            File secret = new File(tmp, "secret");
            secret.mkdirs();
            File link = new File(root, "link");
            try {
                Files.createSymbolicLink(link.toPath(), secret.toPath());
            } catch (java.nio.file.FileSystemException winNoPrivilege) {
                Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.getPath(), secret.getPath()).start();
                if (p.waitFor() != 0) throw winNoPrivilege;
            }
            try {
                Storage.within(link.getPath(), Collections.singletonList(root.getPath()));
                throw new AssertionError("followed symlink out of storage");
            } catch (SecurityException expected) { }
        });
        test("trash folder detection", () -> {
            check(Storage.inTrash(new File("/s/.nexshare-trash")), "dir itself");
            check(Storage.inTrash(new File("/s/.nexshare-trash/123")), "inside");
            check(!Storage.inTrash(new File("/s/.nexshare-trash-not")), "similar name");
        });
        test("cleanName strips separators", () -> {
            eq("a_b", Storage.cleanName("a/b"));
            eq(null, Storage.cleanName(".."));
            eq(null, Storage.cleanName("  "));
        });
        test("urlEncodePath keeps slashes", () -> {
            eq("/dav/internal/My%20Files/%E0%B8%81.txt", Storage.urlEncodePath("/dav/internal/My Files/ก.txt"));
        });
    }

    // ---------------------------------------------------------------- HTTP server

    static HttpServer server(HttpServer.Handler h) throws IOException {
        HttpServer s = new HttpServer(h);
        s.start(0);
        return s;
    }

    static String exchange(int port, String raw) throws IOException {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            s.getOutputStream().write(raw.getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            s.shutdownOutput();
            return new String(readAll(s.getInputStream()), StandardCharsets.UTF_8);
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try {
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        } catch (java.net.SocketTimeoutException ignored) { }
        return bo.toByteArray();
    }

    static void httpTests() throws Exception {
        final AtomicReference<HttpServer.Request> last = new AtomicReference<>();
        final AtomicReference<String> lastBody = new AtomicReference<>();
        HttpServer s = server((req, res) -> {
            last.set(req);
            if (req.path.equals("/ws")) {
                HttpServer.WebSocket ws = HttpServer.upgrade(req, res);
                if (ws == null) return;
                byte[] m;
                while ((m = ws.receive()) != null) ws.sendText("echo:" + new String(m, StandardCharsets.UTF_8));
                return;
            }
            if (req.path.equals("/boom")) throw new SecurityException("nope");
            lastBody.set(req.bodyString(1 << 20));
            res.text(200, "text/plain", req.method + " " + req.path + " " + lastBody.get());
        });
        int port = s.port();
        try {
            test("GET with query decoding", () -> {
                String r = exchange(port, "GET /a%20b?x=1+2&y=%E0%B8%81&y=z HTTP/1.1\r\nHost: t\r\nConnection: close\r\n\r\n");
                check(r.startsWith("HTTP/1.1 200"), r);
                eq("/a b", last.get().path);
                eq("/a%20b", last.get().rawPath);
                eq("1+2", last.get().param("x"));
                eq(Arrays.asList("ก", "z"), last.get().params("y"));
            });
            test("Content-Length body", () -> {
                String r = exchange(port, "POST /p HTTP/1.1\r\nContent-Length: 5\r\nConnection: close\r\n\r\nhello");
                check(r.endsWith("POST /p hello"), r);
            });
            test("chunked body", () -> {
                String r = exchange(port, "PUT /c HTTP/1.1\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                    + "5\r\nhello\r\n6;ext=1\r\n world\r\n0\r\nX-Trailer: 1\r\n\r\n");
                check(r.endsWith("PUT /c hello world"), r);
                eq(-1L, last.get().contentLength);
            });
            test("Expect: 100-continue gets an interim response", () -> {
                String r = exchange(port, "PUT /e HTTP/1.1\r\nExpect: 100-continue\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok");
                check(r.startsWith("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200"), r);
            });
            test("keep-alive serves several requests on one connection", () -> {
                String r = exchange(port, "GET /1 HTTP/1.1\r\n\r\nPOST /2 HTTP/1.1\r\nContent-Length: 3\r\n\r\nabcGET /3 HTTP/1.1\r\nConnection: close\r\n\r\n");
                check(r.contains("GET /1 ") && r.contains("POST /2 abc") && r.contains("GET /3 "), r);
            });
            test("SecurityException maps to 403", () -> {
                String r = exchange(port, "GET /boom HTTP/1.1\r\nConnection: close\r\n\r\n");
                check(r.startsWith("HTTP/1.1 403"), r);
            });
            test("absolute-form request target", () -> {
                exchange(port, "GET http://host:8080/abs/x?q=1 HTTP/1.1\r\nConnection: close\r\n\r\n");
                eq("/abs/x", last.get().path);
            });
            test("gzip compression and Server-Timing", () -> {
                StringBuilder big = new StringBuilder(2048);
                for (int i = 0; i < 80; i++) big.append("repeated text block line ").append(i).append("\n");
                String r = exchange(port, "POST /big HTTP/1.1\r\nAccept-Encoding: gzip\r\nContent-Length: " + big.length() + "\r\nConnection: close\r\n\r\n" + big);
                check(r.contains("Content-Encoding: gzip"), "missing gzip: " + r.substring(0, Math.min(200, r.length())));
                check(r.contains("Server-Timing: app;dur="), "missing server-timing: " + r.substring(0, Math.min(200, r.length())));
            });
            test("client IP: tunnel header trusted only from loopback", () -> {
                HttpServer.Request r = new HttpServer.Request();
                r.remote = "127.0.0.1";
                r.headers.put("cf-connecting-ip", "203.0.113.9");
                eq("203.0.113.9", r.clientIp());
                check(r.viaTunnel(), "via tunnel");
                r.remote = "192.168.1.20";
                eq("192.168.1.20", r.clientIp());
                check(!r.viaTunnel(), "LAN spoof");
            });
            test("WebSocket handshake + masked echo", () -> {
                try (Socket sock = new Socket("127.0.0.1", port)) {
                    sock.setSoTimeout(5000);
                    OutputStream out = sock.getOutputStream();
                    out.write(("GET /ws HTTP/1.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                        + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    InputStream in = sock.getInputStream();
                    String head = readHead(in);
                    check(head.startsWith("HTTP/1.1 101"), head);
                    check(head.contains("s3pPLMBiTxaQ9kYGzzhZRbK+xOo="), head);
                    byte[] payload = "hi".getBytes(StandardCharsets.US_ASCII);
                    byte[] mask = {1, 2, 3, 4};
                    out.write(new byte[]{(byte) 0x81, (byte) (0x80 | payload.length)});
                    out.write(mask);
                    for (int i = 0; i < payload.length; i++) out.write(payload[i] ^ mask[i & 3]);
                    out.flush();
                    int b0 = in.read(), len = in.read();
                    eq(0x81, b0);
                    byte[] got = new byte[len];
                    int off = 0;
                    while (off < len) off += in.read(got, off, len - off);
                    eq("echo:hi", new String(got, StandardCharsets.US_ASCII));
                    out.write(new byte[]{(byte) 0x88, (byte) 0x80, 0, 0, 0, 0}); // close
                    out.flush();
                }
            });
            test("parseForm decodes '+' as space", () -> {
                eq("a b", HttpServer.parseForm("password=a+b&x=1").get("password"));
            });
        } finally {
            s.stop();
        }
    }

    static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0) {
            bo.write(b);
            byte[] a = bo.toByteArray();
            int n = a.length;
            if (n >= 4 && a[n - 4] == '\r' && a[n - 3] == '\n' && a[n - 2] == '\r' && a[n - 1] == '\n') break;
        }
        return new String(bo.toByteArray(), StandardCharsets.US_ASCII);
    }

    // ---------------------------------------------------------------- TLS

    static void tlsTests() throws Exception {
        test("self-signed cert parses, has SANs (including Tailscale IP), and detects IP changes", () -> {
            char[] pw = "pw".toCharArray();
            KeyStore ks = CertGen.createKeyStore("nexshare.local", Arrays.asList("nexshare.local", "localhost"),
                Arrays.asList("192.168.1.5", "100.100.1.2", "127.0.0.1"), pw);
            X509Certificate c = (X509Certificate) ks.getCertificate(CertGen.ALIAS);
            c.checkValidity();
            c.verify(c.getPublicKey());
            eq(2, c.getVersion() - 1);
            String sans = String.valueOf(c.getSubjectAlternativeNames());
            check(sans.contains("nexshare.local") && sans.contains("192.168.1.5") && sans.contains("100.100.1.2"), sans);
            check(CertGen.hasIps(c, Arrays.asList("127.0.0.1", "192.168.1.5", "100.100.1.2")), "hasIps current");
            check(!CertGen.hasIps(c, Arrays.asList("127.0.0.1", "192.168.1.99")), "hasIps detects changed LAN IP");
            check(!CertGen.hasIps(c, Arrays.asList("127.0.0.1", "100.101.2.3")), "hasIps detects new Tailscale IP");
            check(c.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.1"), "serverAuth");
            check(c.getBasicConstraints() == -1, "not a CA");
            check(CertGen.pem(c).startsWith("-----BEGIN CERTIFICATE-----\n"), "pem");
            check(CertGen.fingerprint(c).length() == 95, "fingerprint");
        });
        test("HTTPS server works with generated cert and redirects plain HTTP on the HTTPS port", () -> {
            char[] pw = "secret".toCharArray();
            KeyStore ks = CertGen.createKeyStore("nexshare.local", Collections.singletonList("localhost"),
                Collections.singletonList("127.0.0.1"), pw);
            File f = File.createTempFile("tls", ".p12");
            try (OutputStream o = new java.io.FileOutputStream(f)) {
                ks.store(o, pw);
            }
            KeyStore loaded = KeyStore.getInstance("PKCS12");
            try (InputStream i = new java.io.FileInputStream(f)) {
                loaded.load(i, pw);
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(loaded, pw);
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(kmf.getKeyManagers(), null, null);
            HttpServer s = new HttpServer((req, res) -> res.text(200, "text/plain", "secure=" + req.secure), true);
            s.start(0, sc.getSocketFactory());
            try {
                // 1) Plain HTTP request sent to the HTTPS port gets a 301 redirect to https://
                String plain = exchange(s.port(), "GET /files/a?x=1 HTTP/1.1\r\nHost: nexshare.local:" + s.port() + "\r\nConnection: close\r\n\r\n");
                check(plain.startsWith("HTTP/1.1 301"), plain);
                check(plain.contains("Location: https://nexshare.local:" + s.port() + "/files/a?x=1\r\n"), plain);
                // A hostile Host header can't inject headers or markup into the redirect.
                String evil = exchange(s.port(), "GET / HTTP/1.1\r\nHost: evil\"><script>\r\n\r\n");
                check(evil.startsWith("HTTP/1.1 301") && !evil.contains("<script>") && evil.contains("Location: https://127.0.0.1:"), evil);

                // 2) Real TLS handshake on the same port succeeds
                final X509Certificate want = (X509Certificate) loaded.getCertificate(CertGen.ALIAS);
                SSLContext client = SSLContext.getInstance("TLS");
                client.init(null, new TrustManager[]{new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] c, String a) { }
                    public void checkServerTrusted(X509Certificate[] c, String a) throws java.security.cert.CertificateException {
                        if (!c[0].equals(want)) throw new java.security.cert.CertificateException("unexpected cert");
                    }
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }}, null);
                try (SSLSocket sock = (SSLSocket) client.getSocketFactory().createSocket("127.0.0.1", s.port())) {
                    sock.setSoTimeout(5000);
                    sock.getOutputStream().write("GET / HTTP/1.1\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    String r = new String(readAll(sock.getInputStream()), StandardCharsets.US_ASCII);
                    check(r.startsWith("HTTP/1.1 200") && r.endsWith("secure=true"), r);
                }
            } finally {
                s.stop();
                f.delete();
            }
        });
    }
}
