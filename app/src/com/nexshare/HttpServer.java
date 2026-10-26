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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Minimal HTTP/1.1 server with keep-alive, enough for the web UI and file transfers. */
public class HttpServer {

    public interface Handler {
        void handle(Request req, Response res) throws IOException;
    }

    public static class Request {
        public String method;
        public String path;
        public String remote;
        public final Map<String, List<String>> query = new HashMap<>();
        public final Map<String, String> headers = new HashMap<>();
        public LimitedInputStream body;
        public long contentLength;

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
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = body.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > max) throw new IOException("body too large");
            }
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public static class Response {
        final OutputStream out;
        boolean keepAlive;
        boolean sent;
        final Map<String, String> extra = new LinkedHashMap<>();

        Response(OutputStream out, boolean keepAlive) {
            this.out = out;
            this.keepAlive = keepAlive;
        }

        public void header(String k, String v) {
            extra.put(k, v);
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
            json(code, "{\"error\":" + org.json.JSONObject.quote(msg) + "}");
        }
    }

    /** Input stream bounded by Content-Length so keep-alive requests don't bleed into each other. */
    public static class LimitedInputStream extends InputStream {
        private final InputStream in;
        private long remaining;

        LimitedInputStream(InputStream in, long len) {
            this.in = in;
            this.remaining = len;
        }

        public long remaining() {
            return remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = in.read();
            if (b >= 0) remaining--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }

        void drain() throws IOException {
            byte[] buf = new byte[8192];
            while (remaining > 0 && read(buf, 0, buf.length) > 0) { }
        }
    }

    private final Handler handler;
    private ServerSocket server;
    private ExecutorService pool;
    private volatile boolean running;
    public final AtomicInteger activeConnections = new AtomicInteger();

    public HttpServer(Handler handler) {
        this.handler = handler;
    }

    public void start(int port) throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port), 64);
        pool = Executors.newCachedThreadPool();
        running = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    try {
                        final Socket s = server.accept();
                        pool.execute(new Runnable() {
                            public void run() {
                                serve(s);
                            }
                        });
                    } catch (IOException e) {
                        if (!running) break;
                    }
                }
            }
        }, "http-accept");
        t.start();
    }

    public void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) { }
        if (pool != null) pool.shutdownNow();
    }

    private void serve(Socket s) {
        activeConnections.incrementAndGet();
        try {
            s.setSoTimeout(60000);
            s.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(s.getInputStream(), 65536);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 65536);
            String remote = s.getInetAddress().getHostAddress();
            while (running) {
                String line = readLine(in);
                if (line == null) break;
                if (line.isEmpty()) continue;
                String[] parts = line.split(" ");
                if (parts.length < 3) break;
                Request req = new Request();
                req.method = parts[0].toUpperCase(Locale.ROOT);
                req.remote = remote;
                String target = parts[1];
                int q = target.indexOf('?');
                req.path = decode(q >= 0 ? target.substring(0, q) : target);
                if (q >= 0) parseQuery(target.substring(q + 1), req.query);
                String h;
                while ((h = readLine(in)) != null && !h.isEmpty()) {
                    int c = h.indexOf(':');
                    if (c > 0) req.headers.put(h.substring(0, c).trim().toLowerCase(Locale.ROOT), h.substring(c + 1).trim());
                }
                if (h == null) break;
                long cl = 0;
                try {
                    String v = req.header("content-length");
                    if (v != null) cl = Long.parseLong(v);
                } catch (NumberFormatException ignored) { }
                req.contentLength = cl;
                req.body = new LimitedInputStream(in, cl);
                boolean keep = parts[2].equals("HTTP/1.1") && !"close".equalsIgnoreCase(req.header("connection"));
                Response res = new Response(out, keep);
                try {
                    handler.handle(req, res);
                } catch (SecurityException e) {
                    if (!res.sent) res.error(403, "forbidden");
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    if (!res.sent) res.error(500, String.valueOf(e.getMessage()));
                    else res.keepAlive = false;
                }
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

    private static String readLine(InputStream in) throws IOException {
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

    static String decode(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static void parseQuery(String qs, Map<String, List<String>> into) {
        for (String kv : qs.split("&")) {
            if (kv.isEmpty()) continue;
            int eq = kv.indexOf('=');
            String k = decode(eq >= 0 ? kv.substring(0, eq) : kv);
            String v = eq >= 0 ? decode(kv.substring(eq + 1)) : "";
            List<String> list = into.get(k);
            if (list == null) {
                list = new ArrayList<>();
                into.put(k, list);
            }
            list.add(v);
        }
    }

    static String httpDate(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        return f.format(new Date(ms));
    }

    static String statusText(int code) {
        switch (code) {
            case 200: return "OK";
            case 204: return "No Content";
            case 206: return "Partial Content";
            case 304: return "Not Modified";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 409: return "Conflict";
            case 416: return "Range Not Satisfiable";
            case 429: return "Too Many Requests";
            default: return code >= 500 ? "Internal Server Error" : "OK";
        }
    }
}
