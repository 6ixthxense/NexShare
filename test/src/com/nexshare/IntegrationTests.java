package com.nexshare;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static com.nexshare.Tests.check;
import static com.nexshare.Tests.eq;
import static com.nexshare.Tests.test;

/**
 * End-to-end tests of the server stack on a desktop JVM: the real HttpServer, Auth, Users,
 * SessionStore, FileApi, Trash, Shares and WebDav classes, with tiny fakes for the few
 * Android APIs they touch (test/fakes). Requests are raw HTTP over sockets, like a browser
 * or a WebDAV client would send them.
 */
public final class IntegrationTests {

    static Context ctx;
    static File storage;
    static int port;
    static String pin;

    /** Mirrors WebApi's routing for the parts under test (WebApi itself also pulls in SystemApi/RootApi). */
    static final class Router implements HttpServer.Handler {
        final Auth auth = Auth.get(ctx);
        final FileApi files = new FileApi(ctx);
        final WebDav dav = new WebDav(ctx);

        public void handle(HttpServer.Request req, HttpServer.Response res) throws IOException {
            String p = req.path;
            if (WebDav.matches(p)) { dav.handle(req, res); return; }
            if (p.startsWith("/s/") || p.startsWith("/r/")) { Shares.get(ctx).serve(req, res); return; }
            if (!Auth.sameOrigin(req)) { res.error(403, "cross-origin request blocked"); return; }
            if (p.equals("/api/login")) { auth.login(req, res); return; }
            if (p.equals("/api/session")) { JSONObject o = new JSONObject(); auth.sessionInfo(req, o); res.json(200, o.toString()); return; }
            Auth.Who who = auth.who(req);
            if (who == null) { res.error(401, "unauthorized"); return; }
            if (p.equals("/api/logout")) { auth.logout(req, res); return; }
            if (p.equals("/api/sudo")) { auth.sudo(req, res, who); return; }
            if (p.equals("/api/whoami")) { res.json(200, "{\"user\":\"" + who.user.name + "\",\"sudo\":" + who.sudo() + "}"); return; }
            if (!files.handle(req, res, who)) res.error(404, "not found");
        }
    }

    // ---------------------------------------------------------------- HTTP helper

    static final class Resp {
        int status;
        final Map<String, String> headers = new LinkedHashMap<>();
        byte[] body;

        String text() { return new String(body, StandardCharsets.UTF_8); }
        JSONObject json() { return new JSONObject(text()); }
        String header(String k) { return headers.get(k.toLowerCase(Locale.ROOT)); }
        /** "ns_token=…" from Set-Cookie. */
        String cookie() {
            String c = header("set-cookie");
            return c == null ? null : c.substring(0, c.indexOf(';'));
        }
        public String toString() { return status + " " + text(); }
    }

    static Resp req(String method, String path, String cookie, byte[] body, String... extraHeaders) throws IOException {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(10000);
            StringBuilder h = new StringBuilder(method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nConnection: close\r\n");
            if (cookie != null) h.append("Cookie: ").append(cookie).append("\r\n");
            boolean chunked = false;
            for (int i = 0; i + 1 < extraHeaders.length; i += 2) {
                h.append(extraHeaders[i]).append(": ").append(extraHeaders[i + 1]).append("\r\n");
                if (extraHeaders[i].equalsIgnoreCase("Transfer-Encoding")) chunked = true;
            }
            if (body != null && !chunked) h.append("Content-Length: ").append(body.length).append("\r\n");
            h.append("\r\n");
            OutputStream out = s.getOutputStream();
            out.write(h.toString().getBytes(StandardCharsets.UTF_8));
            if (body != null) {
                if (chunked) {
                    for (int i = 0; i < body.length; i += 5) {
                        int n = Math.min(5, body.length - i);
                        out.write((Integer.toHexString(n) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                        out.write(body, i, n);
                        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    }
                    out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                } else out.write(body);
            }
            out.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) all.write(buf, 0, n);
            byte[] raw = all.toByteArray();
            // Skip interim "100 Continue" responses, as real clients do.
            while (raw.length > 12 && new String(raw, 0, 12, StandardCharsets.US_ASCII).startsWith("HTTP/1.1 100")) {
                int end = indexOf(raw, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                raw = java.util.Arrays.copyOfRange(raw, end + 4, raw.length);
            }
            int split = indexOf(raw, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            String head = new String(raw, 0, split, StandardCharsets.UTF_8);
            Resp r = new Resp();
            String[] lines = head.split("\r\n");
            r.status = Integer.parseInt(lines[0].split(" ")[1]);
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                r.headers.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT), lines[i].substring(c + 1).trim());
            }
            r.body = java.util.Arrays.copyOfRange(raw, split + 4, raw.length);
            return r;
        }
    }

    static Resp req(String method, String path, String cookie) throws IOException {
        return req(method, path, cookie, null);
    }

    static Resp post(String path, String cookie, String json, String... headers) throws IOException {
        String[] h = new String[headers.length + 2];
        System.arraycopy(headers, 0, h, 0, headers.length);
        h[headers.length] = "Content-Type";
        h[headers.length + 1] = "application/json";
        return req("POST", path, cookie, json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8), h);
    }

    static int indexOf(byte[] a, byte[] pat) {
        outer:
        for (int i = 0; i + pat.length <= a.length; i++) {
            for (int j = 0; j < pat.length; j++) if (a[i + j] != pat[j]) continue outer;
            return i;
        }
        return -1;
    }

    static String enc(String s) {
        return Storage.urlEncode(s);
    }

    static String basic(String user, String pw) {
        return "Basic " + Crypto.base64Encode((user + ":" + pw).getBytes(StandardCharsets.UTF_8));
    }

    static void write(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        try (OutputStream o = new FileOutputStream(f)) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    static String login(String user, String pw) throws IOException {
        Resp r = post("/api/login", null, new JSONObject().put("user", user).put("password", pw).toString());
        if (r.status != 200) throw new AssertionError("login failed: " + r);
        return r.cookie();
    }

    // ---------------------------------------------------------------- tests

    public static void run() throws Exception {
        File tmp = Files.createTempDirectory("nexshare-it").toFile().getCanonicalFile();
        storage = new File(tmp, "storage");
        storage.mkdirs();
        ctx = new Context(storage, new File(tmp, "private"));
        write(new File(storage, "DCIM/photo.jpg"), "JPEGDATA");
        write(new File(storage, "Documents/secret.txt"), "top secret");
        write(new File(storage, "Documents/sub/a.txt"), "aaa");
        pin = Settings.pin(ctx);

        HttpServer server = new HttpServer(new Router());
        server.start(0);
        port = server.port();
        try {
            System.out.println("Auth");
            authTests();
            System.out.println("Users & permissions");
            userTests();
            System.out.println("Trash");
            trashTests();
            System.out.println("Share links");
            shareTests();
            System.out.println("WebDAV");
            davTests();
        } finally {
            server.stop();
        }
    }

    static void authTests() throws Exception {
        test("API needs login; session reports PIN mode", () -> {
            eq(401, req("GET", "/api/roots", null).status);
            JSONObject s = req("GET", "/api/session", null).json();
            check(!s.getBoolean("auth") && s.getBoolean("pinRequired") && !s.getBoolean("adminPassword"), s.toString());
        });
        test("login sets an HttpOnly SameSite=Strict cookie that works", () -> {
            Resp r = post("/api/login", null, "{\"pin\":\"" + pin + "\"}");
            eq(200, r.status);
            String sc = r.header("set-cookie");
            check(sc.contains("HttpOnly") && sc.contains("SameSite=Strict") && !sc.contains("Secure"), sc);
            Resp roots = req("GET", "/api/roots", r.cookie());
            eq(200, roots.status);
            eq(storage.getPath(), roots.json().getJSONArray("roots").getJSONObject(0).getString("path"));
        });
        test("only a hash of the session token is stored", () -> {
            String cookie = login("", pin);
            String token = cookie.substring(cookie.indexOf('=') + 1);
            Map<String, ?> all = ctx.getSharedPreferences("nexshare_sessions", 0).getAll();
            check(!all.isEmpty(), "persisted");
            for (Map.Entry<String, ?> e : all.entrySet()) check(!e.getKey().contains(token) && !String.valueOf(e.getValue()).contains(token), "raw token stored");
        });
        test("cross-origin POST is rejected even with a valid cookie", () -> {
            String cookie = login("", pin);
            eq(403, post("/api/mkdir?path=" + enc(storage.getPath()) + "&name=x", cookie, null, "Origin", "http://evil.example").status);
            eq(200, post("/api/mkdir?path=" + enc(storage.getPath()) + "&name=x", cookie, null, "Origin", "http://127.0.0.1:" + port).status);
        });
        test("5 wrong PINs lock the IP with growing backoff; tunnel uses CF-Connecting-IP", () -> {
            for (int i = 0; i < 5; i++) eq(401, post("/api/login", null, "{\"pin\":\"000000\"}", "CF-Connecting-IP", "198.51.100.1").status);
            Resp locked = post("/api/login", null, "{\"pin\":\"" + pin + "\"}", "CF-Connecting-IP", "198.51.100.1");
            eq(429, locked.status);
            int first = Integer.parseInt(locked.header("retry-after"));
            check(first > 50 && first <= 60, "first lock ~60s, got " + first);
            // A different internet client (different CF IP) is not affected by that lock.
            eq(200, post("/api/login", null, "{\"pin\":\"" + pin + "\"}", "CF-Connecting-IP", "198.51.100.2").status);
            // Nor is the LAN (plain loopback without the tunnel header here).
            eq(200, post("/api/login", null, "{\"pin\":\"" + pin + "\"}").status);
        });
        test("tunnel failures across many IPs trip the global tunnel lock", () -> {
            for (int i = 0; i < 20; i++) post("/api/login", null, "{\"pin\":\"000000\"}", "CF-Connecting-IP", "203.0.113." + (10 + i));
            eq(429, post("/api/login", null, "{\"pin\":\"" + pin + "\"}", "CF-Connecting-IP", "203.0.113.200").status);
            eq(200, post("/api/login", null, "{\"pin\":\"" + pin + "\"}").status); // LAN still fine
        });
        test("2FA via tunnel: otp_required, valid code works, replay rejected", () -> {
            Auth.get(ctx).resetLockouts();
            String secret = Crypto.base32Encode(Crypto.random(20));
            Settings.setTotp(ctx, secret, Settings.TOTP_TUNNEL);
            try {
                String ip = "192.0.2.77";
                eq("otp_required", post("/api/login", null, "{\"pin\":\"" + pin + "\"}", "CF-Connecting-IP", ip).json().getString("error"));
                eq(200, post("/api/login", null, "{\"pin\":\"" + pin + "\"}").status); // LAN: no 2FA in "tunnel" mode
                String code = Crypto.totp(Crypto.base32Decode(secret), System.currentTimeMillis() / 30000);
                eq(200, post("/api/login", null, "{\"pin\":\"" + pin + "\",\"otp\":\"" + code + "\"}", "CF-Connecting-IP", ip).status);
                eq(401, post("/api/login", null, "{\"pin\":\"" + pin + "\",\"otp\":\"" + code + "\"}", "CF-Connecting-IP", "192.0.2.78").status);
            } finally {
                Settings.setTotp(ctx, "", Settings.TOTP_OFF);
            }
        });
        test("admin password replaces the PIN and logs everyone out", () -> {
            String old = login("", pin);
            Settings.setAdminPassword(ctx, "correct horse battery");
            try {
                eq(401, req("GET", "/api/roots", old).status);
                eq(401, post("/api/login", null, "{\"pin\":\"" + pin + "\"}").status);
                eq(200, req("GET", "/api/roots", login("admin", "correct horse battery")).status);
            } finally {
                Settings.setAdminPassword(ctx, null);
                Auth.get(ctx).succeed(new HttpServer.Request() {{ remote = "127.0.0.1"; }});
            }
        });
        test("new PIN revokes all sessions", () -> {
            String c = login("", pin);
            pin = Settings.regeneratePin(ctx);
            eq(401, req("GET", "/api/roots", c).status);
        });
        test("sudo window: granted on owner login, required again after expiry", () -> {
            String c = login("", pin);
            check(req("GET", "/api/whoami", c).json().getBoolean("sudo"), "sudo after login");
            eq(401, post("/api/sudo", c, "{\"pin\":\"111111\"}").status);
            eq(200, post("/api/sudo", c, "{\"pin\":\"" + pin + "\"}").status);
        });
    }

    static void userTests() throws Exception {
        Users.get(ctx).save("mom", Users.ROLE_RO, java.util.Collections.singletonList(new File(storage, "DCIM").getPath()), "momsecret1");
        Users.get(ctx).save("kid", Users.ROLE_RW, java.util.Collections.singletonList(new File(storage, "Documents/sub").getPath()), "kidsecret1");
        test("users log in with their own password; wrong password fails", () -> {
            eq(401, post("/api/login", null, "{\"user\":\"mom\",\"password\":\"nope\"}").status);
            JSONObject s = req("GET", "/api/session", login("mom", "momsecret1")).json();
            eq("mom", s.getString("user"));
            check(!s.getBoolean("canWrite") && !s.getBoolean("admin"), s.toString());
        });
        test("restricted user sees only their folder (and the path to it)", () -> {
            String c = login("mom", "momsecret1");
            JSONArray top = req("GET", "/api/list?path=" + enc(storage.getPath()), c).json().getJSONArray("entries");
            eq(1, top.length());
            eq("DCIM", top.getJSONObject(0).getString("name"));
            eq(200, req("GET", "/api/file?path=" + enc(storage + "/DCIM/photo.jpg"), c).status);
            eq(403, req("GET", "/api/file?path=" + enc(storage + "/Documents/secret.txt"), c).status);
            eq(403, req("GET", "/api/file?path=" + enc(storage + "/DCIM/../Documents/secret.txt"), c).status);
            eq(403, req("GET", "/api/zip?p=" + enc(storage + "/Documents"), c).status);
        });
        test("read-only user can't write", () -> {
            String c = login("mom", "momsecret1");
            eq("read_only", post("/api/mkdir?path=" + enc(storage + "/DCIM") + "&name=x", c, null).json().getString("error"));
            eq(403, req("POST", "/api/upload?path=" + enc(storage + "/DCIM") + "&name=y.txt", c, "hi".getBytes()).status);
            check(!new File(storage, "DCIM/y.txt").exists(), "file written");
        });
        test("read-write user writes only inside their folder", () -> {
            String c = login("kid", "kidsecret1");
            eq(200, req("POST", "/api/upload?path=" + enc(storage + "/Documents/sub") + "&name=hw.txt", c, "homework".getBytes()).status);
            eq("homework", read(new File(storage, "Documents/sub/hw.txt")));
            eq(403, req("POST", "/api/upload?path=" + enc(storage + "/Documents") + "&name=evil.txt", c, "x".getBytes()).status);
            // ".." segments in the folder-upload path are dropped, so the file stays inside the folder.
            eq(200, req("POST", "/api/upload?path=" + enc(storage + "/Documents/sub") + "&name=x.txt&rel=" + enc("../.."), c, "x".getBytes()).status);
            check(new File(storage, "Documents/sub/x.txt").exists(), "landed in the folder");
            check(!new File(storage, "x.txt").exists() && !new File(storage, "Documents/x.txt").exists(), "rel escaped the folder");
        });
        test("upload with overwrite=1 replaces instead of renaming (backup)", () -> {
            String c = login("", pin);
            String dir = enc(storage + "/Documents");
            eq(200, req("POST", "/api/upload?path=" + dir + "&name=b.txt", c, "v1".getBytes()).status);
            eq(200, req("POST", "/api/upload?path=" + dir + "&name=b.txt&overwrite=1", c, "v2".getBytes()).status);
            eq("v2", read(new File(storage, "Documents/b.txt")));
            check(!new File(storage, "Documents/b (1).txt").exists(), "made a copy");
        });
        test("backup diff: /api/exists reports only missing or changed files", () -> {
            String c = login("", pin);
            JSONObject b = new JSONObject().put("path", storage + "/Documents").put("items", new JSONArray()
                .put(new JSONObject().put("rel", "b.txt").put("size", 2))
                .put(new JSONObject().put("rel", "secret.txt").put("size", 999))
                .put(new JSONObject().put("rel", "new/file.bin").put("size", 5))
                .put(new JSONObject().put("rel", "../../escape").put("size", 1)));
            JSONArray missing = post("/api/exists", c, b.toString()).json().getJSONArray("missing");
            eq("[\"secret.txt\",\"new/file.bin\"]", missing.toString());
        });
        test("deleting a user ends their sessions", () -> {
            String c = login("kid", "kidsecret1");
            Users.get(ctx).delete("kid");
            SessionStore.get(ctx).revokeUser("kid");
            eq(401, req("GET", "/api/roots", c).status);
        });
        test("resumable upload: chunked/interrupted upload resumes from offset", () -> {
            String c = login("", pin);
            String dir = enc(storage + "/Documents");
            JSONObject st0 = req("GET", "/api/upload/status?path=" + dir + "&name=big.bin&size=11", c).json();
            eq(0L, st0.getLong("offset"));
            // Send first 5 bytes ("hello") of an 11-byte file
            Resp r1 = req("POST", "/api/upload?path=" + dir + "&name=big.bin&offset=0&total=11&resumable=1", c, "hello".getBytes(StandardCharsets.UTF_8));
            eq(200, r1.status);
            check(r1.json().getBoolean("partial") && r1.json().getLong("offset") == 5L, r1.text());
            // Query status: should report offset=5
            JSONObject st1 = req("GET", "/api/upload/status?path=" + dir + "&name=big.bin&size=11", c).json();
            eq(5L, st1.getLong("offset"));
            // Wrong offset returns 409 with current offset
            Resp mismatch = req("POST", "/api/upload?path=" + dir + "&name=big.bin&offset=2&total=11&resumable=1", c, "x".getBytes(StandardCharsets.UTF_8));
            eq(409, mismatch.status);
            eq(5L, mismatch.json().getLong("offset"));
            // Send remaining 6 bytes (" world") at offset=5
            Resp r2 = req("POST", "/api/upload?path=" + dir + "&name=big.bin&offset=5&total=11&resumable=1", c, " world".getBytes(StandardCharsets.UTF_8));
            eq(200, r2.status);
            eq(11L, r2.json().getLong("size"));
            eq("hello world", read(new File(storage, "Documents/big.bin")));
            check(!Storage.partialFile(new File(storage, "Documents"), "big.bin").exists(), ".nexpart cleaned up");
            new File(storage, "Documents/big.bin").delete();
        });
        test("per-user storage quota enforced on upload and copy", () -> {
            File qDir = new File(storage, "QuotaBox");
            qDir.mkdirs();
            Users.get(ctx).save("q_user", Users.ROLE_RW, java.util.Collections.singletonList(qDir.getPath()), "quotapass1", 10L);
            try {
                String qc = login("q_user", "quotapass1");
                eq(200, req("POST", "/api/upload?path=" + enc(qDir.getPath()) + "&name=ok.txt", qc, "123456".getBytes(StandardCharsets.UTF_8)).status);
                Resp over = req("POST", "/api/upload?path=" + enc(qDir.getPath()) + "&name=too_big.txt", qc, "123456".getBytes(StandardCharsets.UTF_8));
                eq(507, over.status);
                eq("quota_exceeded", over.json().getString("error"));
                Resp copyOver = post("/api/copy?dst=" + enc(qDir.getPath()) + "&p=" + enc(new File(qDir, "ok.txt").getPath()), qc, null);
                eq(507, copyOver.status);
            } finally {
                Users.get(ctx).delete("q_user");
                Storage.deleteRec(qDir);
            }
        });
    }

    static void trashTests() throws Exception {
        test("delete moves to trash, restore puts it back", () -> {
            String c = login("", pin);
            File f = new File(storage, "Documents/trashme.txt");
            write(f, "bye");
            JSONObject d = post("/api/delete?p=" + enc(f.getPath()), c, null).json();
            check(d.getInt("deleted") == 1 && d.getBoolean("trash"), d.toString());
            check(!f.exists(), "still there");
            check(new File(storage, Storage.TRASH_DIR + "/.nomedia").exists(), ".nomedia");
            JSONArray items = req("GET", "/api/trash/list", c).json().getJSONArray("items");
            eq(1, items.length());
            eq(f.getPath(), items.getJSONObject(0).getString("orig"));
            String id = items.getJSONObject(0).getString("id");
            JSONArray restored = post("/api/trash/restore?id=" + id, c, null).json().getJSONArray("restored");
            eq(f.getPath(), restored.getString(0));
            eq("bye", read(f));
        });
        test("trash contents are not reachable through the file API", () -> {
            String c = login("", pin);
            File f = new File(storage, "Documents/again.txt");
            write(f, "x");
            post("/api/delete?p=" + enc(f.getPath()), c, null);
            JSONArray top = req("GET", "/api/list?path=" + enc(storage.getPath()), c).json().getJSONArray("entries");
            for (int i = 0; i < top.length(); i++) check(!top.getJSONObject(i).getString("name").equals(Storage.TRASH_DIR), "trash listed");
            eq(403, req("GET", "/api/list?path=" + enc(storage + "/" + Storage.TRASH_DIR), c).status);
        });
        test("permanent delete and purge", () -> {
            String c = login("", pin);
            File f = new File(storage, "Documents/gone.txt");
            write(f, "x");
            check(!post("/api/delete?permanent=1&p=" + enc(f.getPath()), c, null).json().getBoolean("trash"), "trash flag");
            check(!f.exists(), "exists");
            int before = req("GET", "/api/trash/list", c).json().getJSONArray("items").length();
            check(before >= 1, "something in trash");
            check(post("/api/trash/empty", c, null).json().getInt("purged") == before, "purged");
            eq(0, req("GET", "/api/trash/list", c).json().getJSONArray("items").length());
        });
        test("users only see trash from their own folders", () -> {
            String admin = login("", pin);
            post("/api/delete?p=" + enc(storage + "/Documents/b.txt"), admin, null);
            eq(0, req("GET", "/api/trash/list", login("mom", "momsecret1")).json().getJSONArray("items").length());
            eq(1, req("GET", "/api/trash/list", admin).json().getJSONArray("items").length());
        });
    }

    static void shareTests() throws Exception {
        test("file share: public page, download, hit counter", () -> {
            String c = login("", pin);
            JSONObject s = post("/api/share/create", c, new JSONObject().put("path", storage + "/DCIM/photo.jpg").put("hours", 24).toString()).json();
            String id = s.getString("id");
            check(id.matches("[a-z2-7]{16}"), id);
            Resp page = req("GET", "/s/" + id, null);
            eq(200, page.status);
            check(page.text().contains("photo.jpg") && page.header("content-security-policy").contains("default-src 'none'"), page.header("content-security-policy"));
            eq("noindex, nofollow", page.header("x-robots-tag"));
            Resp file = req("GET", "/s/" + id + "/f?dl=1", null);
            eq("JPEGDATA", file.text());
            check(file.header("content-disposition").startsWith("attachment"), file.header("content-disposition"));
            eq(1, post("/api/share/list", c, null).json().getJSONArray("shares").getJSONObject(0).getInt("hits"));
        });
        test("password-protected folder share; traversal blocked; zip works", () -> {
            String c = login("", pin);
            String id = post("/api/share/create", c, new JSONObject().put("path", storage + "/Documents/sub").put("password", "letmein").toString()).json().getString("id");
            Resp form = req("GET", "/s/" + id, null);
            check(form.text().contains("type=\"password\""), "no password form");
            Resp locked = req("GET", "/s/" + id + "/f?p=a.txt", null);
            check(!locked.text().contains("aaa") && locked.text().contains("type=\"password\""), "file served without password");
            Resp bad = req("POST", "/s/" + id, null, "password=nope".getBytes(), "Content-Type", "application/x-www-form-urlencoded");
            eq(401, bad.status);
            Resp good = req("POST", "/s/" + id, null, "password=letmein".getBytes(), "Content-Type", "application/x-www-form-urlencoded");
            eq(303, good.status);
            String cookie = good.cookie();
            check(good.header("set-cookie").contains("Path=/s/" + id), good.header("set-cookie"));
            eq("aaa", req("GET", "/s/" + id + "/f?p=a.txt", cookie).text());
            eq(403, req("GET", "/s/" + id + "/f?p=" + enc("../secret.txt"), cookie).status);
            Resp zip = req("GET", "/s/" + id + "/zip", cookie);
            ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(zip.body));
            StringBuilder names = new StringBuilder();
            for (java.util.zip.ZipEntry e; (e = z.getNextEntry()) != null; ) names.append(e.getName()).append(';');
            check(names.toString().contains("sub/a.txt"), names.toString());
        });
        test("expired, revoked and unknown links", () -> {
            String c = login("", pin);
            JSONObject s = Shares.get(ctx).create(new File(storage, "DCIM/photo.jpg"), "admin", System.currentTimeMillis() - 1000, "");
            eq(410, req("GET", "/s/" + s.getString("id"), null).status);
            String id = post("/api/share/create", c, new JSONObject().put("path", storage + "/DCIM/photo.jpg").toString()).json().getString("id");
            post("/api/share/delete?id=" + id, c, null);
            eq(404, req("GET", "/s/" + id, null).status);
            eq(404, req("GET", "/s/zzzzzzzzzzzzzzzz", null).status);
        });
        test("a user can't share outside their folders; links die with the user's access", () -> {
            String mom = login("mom", "momsecret1");
            eq(403, post("/api/share/create", mom, new JSONObject().put("path", storage + "/DCIM/photo.jpg").toString()).status); // read-only
            Users.get(ctx).save("mom", Users.ROLE_RW, java.util.Collections.singletonList(new File(storage, "DCIM").getPath()), null);
            mom = login("mom", "momsecret1");
            eq(403, post("/api/share/create", mom, new JSONObject().put("path", storage + "/Documents/secret.txt").toString()).status);
            String id = post("/api/share/create", mom, new JSONObject().put("path", storage + "/DCIM/photo.jpg").toString()).json().getString("id");
            eq(200, req("GET", "/s/" + id, null).status);
            Users.get(ctx).delete("mom");
            eq(404, req("GET", "/s/" + id, null).status);
            Users.get(ctx).save("mom", Users.ROLE_RO, java.util.Collections.singletonList(new File(storage, "DCIM").getPath()), "momsecret1");
        });
        test("file request link allows unauthenticated uploads without exposing existing folder files", () -> {
            String c = login("", pin);
            File dropDir = new File(storage, "Documents/sub");
            JSONObject s = post("/api/share/create", c, new JSONObject()
                .put("path", dropDir.getPath())
                .put("mode", "request")
                .put("label", "ส่งการบ้าน")
                .toString()).json();
            String id = s.getString("id");
            eq("request", s.getString("mode"));
            Resp page = req("GET", "/r/" + id, null);
            eq(200, page.status);
            check(page.text().contains("ส่งการบ้าน") && !page.text().contains("a.txt"), "request page should not list existing files");
            // Cannot download existing files from a request link
            eq(404, req("GET", "/s/" + id + "/f?p=a.txt", null).status);
            // Public upload into the folder works without session cookie
            Resp up = req("POST", "/s/" + id + "/upload?name=guest.txt", null, "from guest".getBytes(StandardCharsets.UTF_8));
            eq(200, up.status);
            eq("from guest", read(new File(dropDir, "guest.txt")));
            new File(dropDir, "guest.txt").delete();
        });
        test("audit log records login, upload, delete, share creation, and file request uploads", () -> {
            JSONArray logs = AuditLog.list(ctx, 100, null, null);
            check(logs.length() >= 5, "audit log entries recorded");
            JSONArray reqUploads = AuditLog.list(ctx, 20, "request_upload", null);
            check(reqUploads.length() >= 1, "request_upload logged");
            JSONArray logins = AuditLog.list(ctx, 20, "login", null);
            check(logins.length() >= 1, "login logged");
        });
        test("share maxHits (burn-after-reading) and file request maxBytes limit", () -> {
            String c = login("", pin);
            // Share link with maxHits=1 expires right after 1st download
            JSONObject burn = post("/api/share/create", c, new JSONObject()
                .put("path", storage + "/DCIM/photo.jpg")
                .put("maxHits", 1)
                .toString()).json();
            String burnId = burn.getString("id");
            eq(1, burn.getInt("maxHits"));
            eq(200, req("GET", "/s/" + burnId + "/f?dl=1", null).status);
            eq(410, req("GET", "/s/" + burnId + "/f?dl=1", null).status);
            eq(410, req("GET", "/s/" + burnId, null).status);

            // File request link with maxBytes=5 rejects 10-byte file with 413
            File dropDir = new File(storage, "Documents/sub");
            JSONObject reqLim = post("/api/share/create", c, new JSONObject()
                .put("path", dropDir.getPath())
                .put("mode", "request")
                .put("maxBytes", 5L)
                .toString()).json();
            String reqId = reqLim.getString("id");
            eq(413, req("POST", "/s/" + reqId + "/upload?name=big.bin", null, "0123456789".getBytes(StandardCharsets.UTF_8)).status);
            eq(200, req("POST", "/s/" + reqId + "/upload?name=small.bin", null, "1234".getBytes(StandardCharsets.UTF_8)).status);
            new File(dropDir, "small.bin").delete();
        });
        test("built-in text editor (/api/write), on-phone zip/unzip, clipboard, storage analyzer, and live events", () -> {
            String c = login("", pin);
            String docDir = storage + "/Documents";
            // Create new file via /api/write
            Resp cr = req("POST", "/api/write?path=" + enc(docDir) + "&name=editme.md&create=1", c, "# Initial".getBytes(StandardCharsets.UTF_8));
            eq(200, cr.status);
            File editedFile = new File(storage, "Documents/editme.md");
            eq("# Initial", read(editedFile));
            // Create with same name and create=1 fails with 409
            eq(409, req("POST", "/api/write?path=" + enc(docDir) + "&name=editme.md&create=1", c, "dup".getBytes(StandardCharsets.UTF_8)).status);
            // Update existing file via /api/write
            eq(200, req("POST", "/api/write?path=" + enc(editedFile.getPath()), c, "# Updated Content".getBytes(StandardCharsets.UTF_8)).status);
            eq("# Updated Content", read(editedFile));

            // Compress editme.md into archive.zip on the phone via /api/zip/save
            Resp zs = post("/api/zip/save", c, new JSONObject()
                .put("dst", docDir)
                .put("name", "bundle.zip")
                .put("p", new JSONArray().put(editedFile.getPath()))
                .toString());
            eq(200, zs.status);
            File zipFile = new File(storage, "Documents/bundle.zip");
            check(zipFile.isFile() && zipFile.length() > 0, "bundle.zip created");

            // Extract bundle.zip via /api/unzip
            Resp uz = post("/api/unzip?path=" + enc(zipFile.getPath()), c, null);
            eq(200, uz.status);
            eq(1, uz.json().getInt("extracted"));
            File extracted = new File(storage, "Documents/bundle/editme.md");
            eq("# Updated Content", read(extracted));
            editedFile.delete();
            zipFile.delete();
            Storage.deleteRec(new File(storage, "Documents/bundle"));

            // Shared clipboard (/api/clipboard)
            Resp clipPost = post("/api/clipboard", c, new JSONObject().put("text", "https://nexshare.local:8443").toString());
            eq(200, clipPost.status);
            eq("https://nexshare.local:8443", clipPost.json().getString("current"));
            JSONObject clipGet = req("GET", "/api/clipboard", c).json();
            eq("https://nexshare.local:8443", clipGet.getString("current"));
            check(clipGet.getJSONArray("items").length() >= 1, "clipboard history");

            // Storage analyzer (/api/storage/analyze) & Live events (/api/events)
            JSONObject sa = req("GET", "/api/storage/analyze", c).json();
            check(sa.has("categories") && sa.has("folders") && sa.has("largest"), sa.toString());
            // Fast cache hit on subsequent call
            JSONObject saCached = req("GET", "/api/storage/analyze", c).json();
            eq(sa.getLong("scannedBytes"), saCached.getLong("scannedBytes"));
            JSONObject ev = req("GET", "/api/events?since=0", c).json();
            check(ev.getLong("seq") > 0 && ev.getJSONArray("events").length() > 0, ev.toString());

            // Pagination on /api/list
            JSONObject pagedList = req("GET", "/api/list?path=" + enc(storage.getPath()) + "&offset=0&limit=1", c).json();
            check(pagedList.getInt("total") > 1, "total entries");
            eq(1, pagedList.getJSONArray("entries").length());
            eq(0, pagedList.getInt("offset"));
            eq(1, pagedList.getInt("limit"));
        });
    }

    static void davTests() throws Exception {
        String admin = basic("admin", pin);
        test("OPTIONS advertises DAV class 1,2 without auth", () -> {
            Resp r = req("OPTIONS", "/dav/", null);
            eq(200, r.status);
            eq("1, 2", r.header("dav"));
        });
        test("PROPFIND requires credentials", () -> {
            Resp r = req("PROPFIND", "/dav/", null, null, "Depth", "1");
            eq(401, r.status);
            check(r.header("www-authenticate").startsWith("Basic"), r.header("www-authenticate"));
            eq(401, req("PROPFIND", "/dav/", null, null, "Depth", "1", "Authorization", basic("admin", "000000")).status);
        });
        test("PROPFIND lists roots and folder contents", () -> {
            Resp r = req("PROPFIND", "/dav/", null, null, "Depth", "1", "Authorization", admin);
            eq(207, r.status);
            check(r.text().contains("<D:href>/dav/internal/</D:href>"), r.text());
            Resp d = req("PROPFIND", "/dav/internal/DCIM/", null, null, "Depth", "1", "Authorization", admin);
            eq(207, d.status);
            check(d.text().contains("<D:href>/dav/internal/DCIM/photo.jpg</D:href>") && d.text().contains("<D:getcontentlength>8</D:getcontentlength>"), d.text());
            check(!req("PROPFIND", "/dav/internal/", null, null, "Depth", "1", "Authorization", admin).text().contains(Storage.TRASH_DIR), "trash listed");
        });
        test("PUT (chunked, like Finder), GET with Range, MKCOL, COPY, MOVE", () -> {
            String text = "สวัสดี WebDAV ทดสอบ chunked body";
            Resp put = req("PUT", "/dav/internal/Documents/" + enc("ไฟล์ ใหม่.txt"), null, text.getBytes(StandardCharsets.UTF_8),
                "Authorization", admin, "Transfer-Encoding", "chunked", "Expect", "100-continue");
            eq(201, put.status);
            eq(text, read(new File(storage, "Documents/ไฟล์ ใหม่.txt")));
            eq(204, req("PUT", "/dav/internal/Documents/" + enc("ไฟล์ ใหม่.txt"), null, "v2".getBytes(), "Authorization", admin).status);
            Resp range = req("GET", "/dav/internal/Documents/" + enc("ไฟล์ ใหม่.txt"), null, null, "Authorization", admin, "Range", "bytes=1-");
            eq(206, range.status);
            eq("2", range.text());
            eq(201, req("MKCOL", "/dav/internal/Documents/newdir", null, null, "Authorization", admin).status);
            eq(405, req("MKCOL", "/dav/internal/Documents/newdir", null, null, "Authorization", admin).status);
            eq(409, req("MKCOL", "/dav/internal/nope/deeper", null, null, "Authorization", admin).status);
            String host = "http://127.0.0.1:" + port;
            eq(201, req("COPY", "/dav/internal/Documents/sub/a.txt", null, null, "Authorization", admin, "Destination", host + "/dav/internal/Documents/newdir/a-copy.txt").status);
            eq("aaa", read(new File(storage, "Documents/newdir/a-copy.txt")));
            eq(412, req("MOVE", "/dav/internal/Documents/newdir/a-copy.txt", null, null, "Authorization", admin,
                "Destination", host + "/dav/internal/Documents/sub/a.txt", "Overwrite", "F").status);
            eq(201, req("MOVE", "/dav/internal/Documents/newdir", null, null, "Authorization", admin, "Destination", host + "/dav/internal/Documents/moved%20dir").status);
            check(new File(storage, "Documents/moved dir/a-copy.txt").exists() && !new File(storage, "Documents/newdir").exists(), "move");
        });
        test("LOCK/UNLOCK and PROPPATCH (Windows timestamps) succeed", () -> {
            Resp lock = req("LOCK", "/dav/internal/Documents/locked.docx", null,
                "<?xml version=\"1.0\"?><D:lockinfo xmlns:D=\"DAV:\"><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype></D:lockinfo>".getBytes(),
                "Authorization", admin);
            eq(201, lock.status);
            check(lock.header("lock-token").startsWith("<opaquelocktoken:"), lock.header("lock-token"));
            check(new File(storage, "Documents/locked.docx").exists(), "lock-null resource created");
            eq(204, req("UNLOCK", "/dav/internal/Documents/locked.docx", null, null, "Authorization", admin, "Lock-Token", lock.header("lock-token")).status);
            String patch = "<?xml version=\"1.0\"?><D:propertyupdate xmlns:D=\"DAV:\" xmlns:Z=\"urn:schemas-microsoft-com:\"><D:set><D:prop>"
                + "<Z:Win32LastModifiedTime>Wed, 01 Jan 2020 00:00:00 GMT</Z:Win32LastModifiedTime><Z:Win32FileAttributes>00000020</Z:Win32FileAttributes>"
                + "</D:prop></D:set></D:propertyupdate>";
            Resp pp = req("PROPPATCH", "/dav/internal/Documents/sub/a.txt", null, patch.getBytes(), "Authorization", admin);
            eq(207, pp.status);
            check(pp.text().contains("Win32LastModifiedTime") && pp.text().contains("HTTP/1.1 200 OK"), pp.text());
            eq(1577836800000L, new File(storage, "Documents/sub/a.txt").lastModified());
        });
        test("DELETE goes to the trash; Finder junk is deleted outright", () -> {
            write(new File(storage, "Documents/._junk"), "x");
            eq(204, req("DELETE", "/dav/internal/Documents/locked.docx", null, null, "Authorization", admin).status);
            eq(204, req("DELETE", "/dav/internal/Documents/._junk", null, null, "Authorization", admin).status);
            JSONArray items = req("GET", "/api/trash/list", login("", pin)).json().getJSONArray("items");
            boolean docx = false, junk = false;
            for (int i = 0; i < items.length(); i++) {
                String n = items.getJSONObject(i).getString("name");
                docx |= n.equals("locked.docx");
                junk |= n.equals("._junk");
            }
            check(docx && !junk, items.toString());
            eq(403, req("DELETE", "/dav/internal/", null, null, "Authorization", admin).status);
        });
        test("WebDAV respects user roles and folders", () -> {
            String mom = basic("mom", "momsecret1");
            eq(207, req("PROPFIND", "/dav/internal/DCIM/", null, null, "Depth", "1", "Authorization", mom).status);
            String top = req("PROPFIND", "/dav/internal/", null, null, "Depth", "1", "Authorization", mom).text();
            check(top.contains("/dav/internal/DCIM/") && !top.contains("Documents"), top);
            eq(403, req("GET", "/dav/internal/Documents/secret.txt", null, null, "Authorization", mom).status);
            eq(403, req("PUT", "/dav/internal/DCIM/x.txt", null, "x".getBytes(), "Authorization", mom).status);
        });
        test("WebDAV path traversal is blocked", () -> {
            for (String p : new String[]{"/dav/internal/../../etc/passwd", "/dav/internal/%2e%2e/%2e%2e/etc/passwd", "/dav/internal/Documents/..%2F..%2F..%2Fetc"}) {
                int st = req("GET", p, null, null, "Authorization", admin).status;
                check(st == 403 || st == 404, p + " -> " + st);
            }
        });
        test("WebDAV through the tunnel is off by default; 2FA owner can't use it there", () -> {
            Auth.get(ctx).resetLockouts();
            eq(404, req("PROPFIND", "/dav/", null, null, "Depth", "0", "Authorization", admin, "CF-Connecting-IP", "192.0.2.9").status);
            Settings.setWebdavViaTunnel(ctx, true);
            Settings.setTotp(ctx, Crypto.base32Encode(Crypto.random(20)), Settings.TOTP_TUNNEL);
            try {
                eq(401, req("PROPFIND", "/dav/", null, null, "Depth", "0", "Authorization", admin, "CF-Connecting-IP", "192.0.2.9").status);
                eq(207, req("PROPFIND", "/dav/", null, null, "Depth", "0", "Authorization", basic("mom", "momsecret1"), "CF-Connecting-IP", "192.0.2.9").status);
            } finally {
                Settings.setTotp(ctx, "", Settings.TOTP_OFF);
                Settings.setWebdavViaTunnel(ctx, false);
            }
        });
    }
}
