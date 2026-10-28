package com.nexshare;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * Minimal HTTP/1.1 server with keep-alive, chunked request bodies, 100-continue,
 * optional TLS and WebSocket upgrades — enough for the web UI, WebDAV and file transfers.
 *
 * Deliberately free of Android classes so it can be unit-tested on a desktop JVM.
 */
public class HttpServer {

    /** Upper bound on worker threads; each open connection (keep-alive, stream, WebSocket) holds one. */
    public static final int MAX_THREADS = 96;

    public interface Handler {
        void handle(Request req, Response res) throws IOException;
    }

    public static class Request {
        public String method;
        public String path;
        /** Raw (still percent-encoded) request target path, needed by WebDAV to echo hrefs. */
        public String rawPath;
        public String remote;
        public boolean secure;
        public final Map<String, List<String>> query = new HashMap<>();
        public final Map<String, String> headers = new HashMap<>();
        public Body body;
        /** Declared Content-Length, or -1 for a chunked body. */
        public long contentLength;
        InputStream raw;

        public String param(String k) {
            List<String> v = query.get(k);
            return v == null || v.isEmpty() ? null : v.get(0);
        }

        public List<String> params(String k) {
            List<String> v = query.get(k);
            return v == null ? new ArrayList<String>() : v;
        }

        public String header(String k) {
            return headers.get(k.toLowerCase(Locale.ROOT));
        }

        public String cookie(String name) {
            String c = header("cookie");
            if (c == null) return null;
            for (String part : c.split(";")) {
                int eq = part.indexOf('=');
                if (eq > 0 && part.substring(0, eq).trim().equals(name)) return part.substring(eq + 1).trim();
            }
            return null;
        }

        public String bodyString(int max) throws IOException {
            return new String(bodyBytes(max), StandardCharsets.UTF_8);
        }

        public byte[] bodyBytes(int max) throws IOException {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = body.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > max) throw new IOException("body too large");
            }
            return bo.toByteArray();
        }

        /**
         * The real client address. Requests that arrive through the Cloudflare Tunnel all come
         * from the local cloudflared process (loopback), so for those we use the address
         * Cloudflare reports. The header is only trusted from loopback, so LAN clients
         * cannot spoof it.
         */
        public String clientIp() {
            if (isLoopback(remote)) {
                String cf = header("cf-connecting-ip");
                if (cf != null && !cf.trim().isEmpty() && cf.length() <= 64) return cf.trim();
            }
            return remote;
        }

        /** True when the request came through the tunnel (cloudflared on this phone). */
        public boolean viaTunnel() {
            return isLoopback(remote) && header("cf-connecting-ip") != null;
        }

        public boolean fromLoopback() {
            return isLoopback(remote);
        }
    }

    static boolean isLoopback(String ip) {
        return ip != null && (ip.startsWith("127.") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1"));
    }

    public static class Response {
        final OutputStream out;
        boolean keepAlive;
        boolean sent;
        boolean upgraded;
        final Map<String, String> extra = new LinkedHashMap<>();
        final List<String> cookies = new ArrayList<>();

        Response(OutputStream out, boolean keepAlive) {
            this.out = out;
            this.keepAlive = keepAlive;
        }

        public void header(String k, String v) {
            if (k.equalsIgnoreCase("Set-Cookie")) cookies.add(v);
            else extra.put(k, v);
        }

        public boolean isSent() {
            return sent;
        }

        /** Writes status + headers. length < 0 means unknown length: connection closes after body. */
        public OutputStream begin(int code, String type, long length) throws IOException {
            sent = true;
            if (length < 0) keepAlive = false;
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 ").append(code).append(' ').append(statusText(code)).append("\r\n");
            sb.append("Server: NexShare\r\n");
            sb.append("Date: ").append(httpDate(System.currentTimeMillis())).append("\r\n");
            if (type != null) sb.append("Content-Type: ").append(type).append("\r\n");
            if (length >= 0) sb.append("Content-Length: ").append(length).append("\r\n");
            sb.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n");
            for (Map.Entry<String, String> e : extra.entrySet())
                sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            for (String c : cookies) sb.append("Set-Cookie: ").append(c).append("\r\n");
            sb.append("\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            return out;
        }

        public void bytes(int code, String type, byte[] data) throws IOException {
            begin(code, type, data.length);
            out.write(data);
        }

        public void text(int code, String type, String s) throws IOException {
            bytes(code, type, s.getBytes(StandardCharsets.UTF_8));
        }

        public void json(int code, String json) throws IOException {
            header("Cache-Control", "no-store");
            text(code, "application/json; charset=utf-8", json);
        }

        public void error(int code, String msg) throws IOException {
            json(code, "{\"error\":" + jsonQuote(msg) + "}");
        }

        public void empty(int code) throws IOException {
            begin(code, null, 0);
        }

        public void redirect(int code, String location) throws IOException {
            header("Location", location);
            begin(code, null, 0);
        }
    }

    /** A request body: either Content-Length bounded or chunked. */
    public abstract static class Body extends InputStream {
        /** Bytes still expected; for chunked bodies 0 once the final chunk was read, otherwise 1. */
        public abstract long remaining();

        void drain() throws IOException {
            byte[] buf = new byte[8192];
            while (read(buf, 0, buf.length) > 0) { }
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n <= 0 ? -1 : b[0] & 0xff;
        }
    }

    /** Input stream bounded by Content-Length so keep-alive requests don't bleed into each other. */
    public static class LimitedInputStream extends Body {
        private final InputStream in;
        private long remaining;

        LimitedInputStream(InputStream in, long len) {
            this.in = in;
            this.remaining = len;
        }

        @Override
        public long remaining() {
            return remaining;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }
    }

    /** Decodes Transfer-Encoding: chunked (used by macOS Finder and other WebDAV clients). */
    public static class ChunkedInputStream extends Body {
        private final InputStream in;
        private long chunkLeft;
        private boolean done;

        ChunkedInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public long remaining() {
            return done ? 0 : 1;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (done) return -1;
            if (chunkLeft == 0) {
                String line = readLine(in);
                if (line == null) throw new IOException("truncated chunked body");
                int semi = line.indexOf(';');
                String hex = (semi >= 0 ? line.substring(0, semi) : line).trim();
                try {
                    chunkLeft = Long.parseLong(hex, 16);
                } catch (NumberFormatException e) {
                    throw new IOException("bad chunk size");
                }
                if (chunkLeft < 0) throw new IOException("bad chunk size");
                if (chunkLeft == 0) {
                    // trailers until an empty line
                    String t;
                    while ((t = readLine(in)) != null && !t.isEmpty()) { }
                    done = true;
                    return -1;
                }
            }
            int n = in.read(b, off, (int) Math.min(len, chunkLeft));
            if (n < 0) throw new IOException("truncated chunked body");
            chunkLeft -= n;
            if (chunkLeft == 0) readLine(in); // CRLF after the chunk data
            return n;
        }
    }

    // ---------------------------------------------------------------- WebSocket

    /** Server side of an upgraded WebSocket connection (RFC 6455, no extensions). */
    public static class WebSocket {
        private final InputStream in;
        private final OutputStream out;
        private volatile boolean closed;

        WebSocket(InputStream in, OutputStream out) {
            this.in = in;
            this.out = out;
        }

        public boolean isClosed() {
            return closed;
        }

        public synchronized void sendBinary(byte[] data, int off, int len) throws IOException {
            frame(0x2, data, off, len);
        }

        public synchronized void sendText(String s) throws IOException {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            frame(0x1, b, 0, b.length);
        }

        public synchronized void close() {
            if (closed) return;
            closed = true;
            try {
                frame(0x8, new byte[0], 0, 0);
            } catch (IOException ignored) { }
        }

        private void frame(int opcode, byte[] data, int off, int len) throws IOException {
            if (closed && opcode != 0x8) throw new IOException("closed");
            byte[] head;
            if (len < 126) head = new byte[]{(byte) (0x80 | opcode), (byte) len};
            else if (len < 65536) head = new byte[]{(byte) (0x80 | opcode), 126, (byte) (len >> 8), (byte) len};
            else {
                head = new byte[10];
                head[0] = (byte) (0x80 | opcode);
                head[1] = 127;
                for (int i = 0; i < 8; i++) head[9 - i] = (byte) ((long) len >> (8 * i));
            }
            out.write(head);
            out.write(data, off, len);
            out.flush();
        }

        /**
         * Reads the next text or binary message from the client, answering pings.
         * Returns null when the client closed the connection.
         */
        public byte[] receive() throws IOException {
            ByteArrayOutputStream msg = new ByteArrayOutputStream();
            while (true) {
                int b0 = in.read(), b1 = in.read();
                if (b0 < 0 || b1 < 0) { closed = true; return null; }
                int opcode = b0 & 0x0f;
                boolean fin = (b0 & 0x80) != 0;
                long len = b1 & 0x7f;
                if (len == 126) len = (readByte() << 8) | readByte();
                else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | readByte(); }
                if (len > 1 << 20) throw new IOException("frame too large");
                byte[] mask = new byte[4];
                boolean masked = (b1 & 0x80) != 0;
                if (masked) readFully(mask);
                byte[] payload = new byte[(int) len];
                readFully(payload);
                if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
                if (opcode == 0x8) { close(); return null; }
                if (opcode == 0x9) { synchronized (this) { frame(0xA, payload, 0, payload.length); } continue; }
                if (opcode == 0xA) continue;
                msg.write(payload);
                if (fin) return msg.toByteArray();
            }
        }

        private int readByte() throws IOException {
            int b = in.read();
            if (b < 0) throw new IOException("eof");
            return b;
        }

        private void readFully(byte[] b) throws IOException {
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) throw new IOException("eof");
                off += n;
            }
        }
    }

    static String webSocketAccept(String key) {
        try {
            java.security.MessageDigest sha1 = java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = sha1.digest((key.trim() + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII));
            return Crypto.base64Encode(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Completes a WebSocket handshake. Returns null (after sending 400) when the request
     * is not a valid upgrade. The handler should keep using the socket until done; the
     * connection is closed when the handler returns.
     */
    public static WebSocket upgrade(Request req, Response res) throws IOException {
        String key = req.header("sec-websocket-key");
        String up = req.header("upgrade");
        if (key == null || up == null || !up.equalsIgnoreCase("websocket")) {
            res.error(400, "websocket upgrade required");
            return null;
        }
        res.keepAlive = false;
        res.sent = true;
        res.upgraded = true;
        String head = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
            + "Sec-WebSocket-Accept: " + webSocketAccept(key) + "\r\n\r\n";
        res.out.write(head.getBytes(StandardCharsets.US_ASCII));
        res.out.flush();
        return new WebSocket(req.raw, res.out);
    }

    // ---------------------------------------------------------------- server

    private final Handler handler;
    private final boolean tls;
    private ServerSocket server;
    private SSLContext sslContext;
    private ThreadPoolExecutor pool;
    private volatile boolean running;
    public final AtomicInteger activeConnections = new AtomicInteger();
    /** Shared across the HTTP and HTTPS servers so both count toward the thread cap. */
    private static final AtomicInteger THREAD_NO = new AtomicInteger();

    public HttpServer(Handler handler) {
        this(handler, false);
    }

    public HttpServer(Handler handler, boolean tls) {
        this.handler = handler;
        this.tls = tls;
    }

    public void start(int port) throws IOException {
        start(port, null);
    }

    /**
     * Starts listening; with a non-null context the server speaks HTTPS. The HTTPS port still
     * accepts plain TCP and looks at the first byte: a TLS handshake continues as HTTPS, while
     * someone who typed http:// by mistake gets a redirect instead of a dead connection.
     */
    public void start(int port, SSLContext ssl) throws IOException {
        sslContext = ssl;
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port), 64);
        // Bounded pool: a flood of connections can't exhaust memory with threads.
        pool = new ThreadPoolExecutor(4, MAX_THREADS, 30, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(),
            new ThreadFactory() {
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "http-" + THREAD_NO.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            });
        running = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    try {
                        final Socket s = server.accept();
                        try {
                            pool.execute(new Runnable() {
                                public void run() {
                                    serve(s);
                                }
                            });
                        } catch (RejectedExecutionException busy) {
                            rejectBusy(s);
                        }
                    } catch (IOException e) {
                        if (!running) break;
                    }
                }
            }
        }, tls ? "https-accept" : "http-accept");
        t.setDaemon(true);
        t.start();
    }

    public int port() {
        return server == null ? -1 : server.getLocalPort();
    }

    private void rejectBusy(Socket s) {
        try {
            s.setSoTimeout(2000);
            if (!tls) {
                s.getOutputStream().write(("HTTP/1.1 503 Service Unavailable\r\nRetry-After: 2\r\nContent-Length: 0\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            }
        } catch (IOException ignored) {
        } finally {
            try {
                s.close();
            } catch (IOException ignored) { }
        }
    }

    public void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) { }
        if (pool != null) pool.shutdownNow();
    }

    /** TLS records start with 0x16 (handshake); anything else on the HTTPS port is plain HTTP. */
    private Socket upgradeOrRedirect(Socket s) throws IOException {
        InputStream rawIn = s.getInputStream();
        int first = rawIn.read();
        if (first < 0) return null;
        if (first == 0x16) {
            SSLSocket ssl = (SSLSocket) sslContext.getSocketFactory()
                .createSocket(s, new java.io.ByteArrayInputStream(new byte[]{(byte) first}), true);
            ssl.setUseClientMode(false);
            ssl.startHandshake();
            return ssl;
        }
        // Plain HTTP sent to the HTTPS port: answer with a redirect to the same URL over https.
        InputStream in = new java.io.SequenceInputStream(new java.io.ByteArrayInputStream(new byte[]{(byte) first}), rawIn);
        String line = readLine(in);
        String path = "/";
        if (line != null) {
            String[] parts = line.split(" ");
            if (parts.length >= 2 && parts[1].startsWith("/")) path = parts[1];
        }
        String host = null, h;
        int count = 0;
        while ((h = readLine(in)) != null && !h.isEmpty() && ++count < 100) {
            if (h.regionMatches(true, 0, "host:", 0, 5)) host = h.substring(5).trim();
        }
        if (host == null || host.isEmpty() || !host.matches("[A-Za-z0-9.\\-\\[\\]:]+")) {
            host = s.getLocalAddress().getHostAddress() + ":" + s.getLocalPort();
        }
        String location = "https://" + host + path;
        String body = "<a href=\"" + location.replace("\"", "%22").replace("<", "%3C") + "\">" + "Use HTTPS</a>\n";
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 301 Moved Permanently\r\nLocation: " + location + "\r\nContent-Type: text/html; charset=utf-8\r\n"
            + "Content-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body).getBytes(StandardCharsets.UTF_8));
        out.flush();
        return null;
    }

    void serve(Socket s) {
        activeConnections.incrementAndGet();
        try {
            s.setSoTimeout(60000);
            s.setTcpNoDelay(true);
            String remote = s.getInetAddress().getHostAddress();
            if (tls) {
                Socket ssl = upgradeOrRedirect(s);
                if (ssl == null) return;
                s = ssl;
            }
            InputStream in = new BufferedInputStream(s.getInputStream(), 65536);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 65536);
            while (running) {
                String line = readLine(in);
                if (line == null) break;
                if (line.isEmpty()) continue;
                String[] parts = line.split(" ");
                if (parts.length < 3) break;
                Request req = new Request();
                req.method = parts[0].toUpperCase(Locale.ROOT);
                req.remote = remote;
                req.secure = tls;
                req.raw = in;
                String target = parts[1];
                if (target.startsWith("http://") || target.startsWith("https://")) {
                    int slash = target.indexOf('/', target.indexOf("//") + 2);
                    target = slash < 0 ? "/" : target.substring(slash);
                }
                int q = target.indexOf('?');
                req.rawPath = q >= 0 ? target.substring(0, q) : target;
                req.path = decodePath(req.rawPath);
                if (q >= 0) parseQuery(target.substring(q + 1), req.query);
                String h;
                int count = 0;
                while ((h = readLine(in)) != null && !h.isEmpty()) {
                    if (++count > 100) throw new IOException("too many headers");
                    int c = h.indexOf(':');
                    if (c > 0) req.headers.put(h.substring(0, c).trim().toLowerCase(Locale.ROOT), h.substring(c + 1).trim());
                }
                if (h == null) break;
                boolean chunked = req.header("transfer-encoding") != null
                    && req.header("transfer-encoding").toLowerCase(Locale.ROOT).contains("chunked");
                long cl = 0;
                if (chunked) {
                    cl = -1;
                    req.body = new ChunkedInputStream(in);
                } else {
                    try {
                        String v = req.header("content-length");
                        if (v != null) cl = Long.parseLong(v);
                    } catch (NumberFormatException ignored) { }
                    if (cl < 0) break;
                    req.body = new LimitedInputStream(in, cl);
                }
                req.contentLength = cl;
                String expect = req.header("expect");
                if (expect != null && expect.equalsIgnoreCase("100-continue") && (cl > 0 || chunked)) {
                    out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                }
                boolean keep = parts[2].equals("HTTP/1.1") && !"close".equalsIgnoreCase(req.header("connection"));
                Response res = new Response(out, keep);
                try {
                    handler.handle(req, res);
                } catch (SecurityException e) {
                    if (!res.sent) res.error(403, "forbidden");
                    else res.keepAlive = false;
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    if (!res.sent) res.error(500, String.valueOf(e.getMessage()));
                    else res.keepAlive = false;
                }
                if (res.upgraded) break;
                if (!res.sent) res.error(404, "not found");
                out.flush();
                if (!res.keepAlive) break;
                if (req.body.remaining() > 1024 * 1024) break;
                req.body.drain();
            }
        } catch (IOException ignored) {
        } finally {
            activeConnections.decrementAndGet();
            try {
                s.close();
            } catch (IOException ignored) { }
        }
    }

    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(128);
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') break;
            if (b != '\r') bo.write(b);
            if (bo.size() > 16384) throw new IOException("header too long");
        }
        if (b < 0 && bo.size() == 0) return null;
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Percent-decodes a URL path ('+' stays a plus sign in paths). */
    static String decodePath(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** Decodes a query-string component, where '+' means space. */
    static String decodeQuery(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** Kept for callers that decode path-like values. */
    static String decode(String s) {
        return decodePath(s);
    }

    static void parseQuery(String qs, Map<String, List<String>> into) {
        for (String kv : qs.split("&")) {
            if (kv.isEmpty()) continue;
            int eq = kv.indexOf('=');
            // The web UI encodes with encodeURIComponent (spaces as %20), so '+' is a literal plus.
            String k = decodePath(eq >= 0 ? kv.substring(0, eq) : kv);
            String v = eq >= 0 ? decodePath(kv.substring(eq + 1)) : "";
            List<String> list = into.get(k);
            if (list == null) {
                list = new ArrayList<>();
                into.put(k, list);
            }
            list.add(v);
        }
    }

    /** Parses an application/x-www-form-urlencoded body (where '+' is a space). */
    static Map<String, String> parseForm(String body) {
        Map<String, String> m = new HashMap<>();
        for (String kv : body.split("&")) {
            if (kv.isEmpty()) continue;
            int eq = kv.indexOf('=');
            m.put(decodeQuery(eq >= 0 ? kv.substring(0, eq) : kv), eq >= 0 ? decodeQuery(kv.substring(eq + 1)) : "");
        }
        return m;
    }

    static String httpDate(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        return f.format(new Date(ms));
    }

    static String jsonQuote(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '<': sb.append("\\u003c"); break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    static String statusText(int code) {
        switch (code) {
            case 101: return "Switching Protocols";
            case 200: return "OK";
            case 201: return "Created";
            case 204: return "No Content";
            case 206: return "Partial Content";
            case 207: return "Multi-Status";
            case 301: return "Moved Permanently";
            case 302: return "Found";
            case 304: return "Not Modified";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
            case 410: return "Gone";
            case 412: return "Precondition Failed";
            case 415: return "Unsupported Media Type";
            case 416: return "Range Not Satisfiable";
            case 423: return "Locked";
            case 429: return "Too Many Requests";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            case 507: return "Insufficient Storage";
            default: return code >= 500 ? "Internal Server Error" : "OK";
        }
    }
}
