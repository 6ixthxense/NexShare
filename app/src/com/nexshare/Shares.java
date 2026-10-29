package com.nexshare;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.ZipOutputStream;

/**
 * Public share links: /s/<id> serves one file or folder to anyone with the link, with an
 * optional expiry and password. Works on the LAN and through the Cloudflare tunnel.
 */
public final class Shares {

    private static Shares instance;
    private final Context ctx;
    private final SharedPreferences prefs;

    public static synchronized Shares get(Context c) {
        if (instance == null) instance = new Shares(c.getApplicationContext());
        return instance;
    }

    private Shares(Context ctx) {
        this.ctx = ctx;
        this.prefs = ctx.getSharedPreferences("nexshare_shares", Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- store

    public synchronized JSONObject create(File target, String owner, long expiresMs, String password) {
        return create(target, owner, expiresMs, password, "share", "", 0, 0L);
    }

    public synchronized JSONObject create(File target, String owner, long expiresMs, String password, String mode, String label) {
        return create(target, owner, expiresMs, password, mode, label, 0, 0L);
    }

    public synchronized JSONObject create(File target, String owner, long expiresMs, String password, String mode, String label, int maxHits, long maxBytes) {
        String id = Crypto.randomId(10); // 16 chars of base32, ~80 bits
        String m = "request".equalsIgnoreCase(mode) ? "request" : "share";
        JSONObject o = new JSONObject();
        Storage.put(o, "id", id);
        Storage.put(o, "path", target.getPath());
        Storage.put(o, "name", target.getName());
        Storage.put(o, "dir", target.isDirectory());
        Storage.put(o, "owner", owner);
        Storage.put(o, "mode", m);
        Storage.put(o, "label", label == null ? "" : label.trim());
        Storage.put(o, "created", System.currentTimeMillis());
        Storage.put(o, "expires", expiresMs);
        Storage.put(o, "pw", password == null || password.isEmpty() ? "" : Crypto.hashPassword(password));
        Storage.put(o, "hits", 0);
        Storage.put(o, "maxHits", Math.max(0, maxHits));
        Storage.put(o, "maxBytes", Math.max(0L, maxBytes));
        prefs.edit().putString(id, o.toString()).apply();
        return publicView(o);
    }

    private synchronized JSONObject load(String id) {
        if (id == null || !id.matches("[a-z2-7]{8,32}")) return null;
        String s = prefs.getString(id, null);
        if (s == null) return null;
        try {
            return new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    private synchronized void bump(JSONObject o) {
        Storage.put(o, "hits", o.optInt("hits") + 1);
        prefs.edit().putString(o.optString("id"), o.toString()).apply();
    }

    private static JSONObject publicView(JSONObject o) {
        JSONObject v = new JSONObject();
        for (String k : new String[]{"id", "path", "name", "dir", "owner", "created", "expires", "hits"}) Storage.put(v, k, o.opt(k));
        Storage.put(v, "mode", o.optString("mode", "share"));
        Storage.put(v, "label", o.optString("label", ""));
        Storage.put(v, "maxHits", o.optInt("maxHits", 0));
        Storage.put(v, "maxBytes", o.optLong("maxBytes", 0L));
        Storage.put(v, "password", !o.optString("pw").isEmpty());
        return v;
    }

    /** Shares this user may manage: admins see all, others only their own. */
    public synchronized JSONArray list(Users.User user) {
        JSONArray arr = new JSONArray();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            try {
                JSONObject o = new JSONObject((String) e.getValue());
                if (user.isAdmin() || user.name.equals(o.optString("owner"))) arr.put(publicView(o));
            } catch (Exception ignored) { }
        }
        return arr;
    }

    public synchronized boolean delete(Users.User user, String id) {
        JSONObject o = load(id);
        if (o == null || !(user.isAdmin() || user.name.equals(o.optString("owner")))) return false;
        prefs.edit().remove(id).apply();
        return true;
    }

    // ---------------------------------------------------------------- public pages

    /** Handles /s/<id>[/f|/zip|/upload] and /r/<id>[/upload]. */
    public void serve(HttpServer.Request req, HttpServer.Response res) throws IOException {
        String rest = req.path.substring(3); // after "/s/" or "/r/"
        int slash = rest.indexOf('/');
        String id = slash < 0 ? rest : rest.substring(0, slash);
        String action = slash < 0 ? "" : rest.substring(slash + 1);
        res.header("Referrer-Policy", "no-referrer");
        res.header("X-Robots-Tag", "noindex, nofollow");
        JSONObject s = load(id);
        if (s == null) {
            page(res, 404, "ไม่พบลิงก์", "<p>ลิงก์นี้ไม่มีอยู่ หรือถูกยกเลิกแล้ว</p>");
            return;
        }
        long exp = s.optLong("expires");
        if (exp > 0 && exp < System.currentTimeMillis()) {
            page(res, 410, "ลิงก์หมดอายุ", "<p>ลิงก์แชร์นี้หมดอายุแล้ว</p>");
            return;
        }
        int maxHits = s.optInt("maxHits", 0);
        if (maxHits > 0 && s.optInt("hits", 0) >= maxHits) {
            page(res, 410, "ลิงก์หมดโควตา", "<p>ลิงก์นี้ถูกใช้งานครบจำนวนครั้งที่กำหนดแล้ว (" + maxHits + " ครั้ง)</p>");
            return;
        }
        File base = new File(s.optString("path"));
        if (!base.exists() || Storage.inTrash(base)) {
            page(res, 404, "ไม่พบไฟล์", "<p>ไฟล์ที่แชร์ถูกย้ายหรือลบไปแล้ว</p>");
            return;
        }
        // The owner might have lost access to the folder since; re-check against their current rights.
        Users.User owner = Users.get(ctx).find(s.optString("owner"));
        boolean isRequest = "request".equals(s.optString("mode", "share"));
        if (owner == null || !owner.allows(base) || (isRequest && (!owner.canWrite() || !base.isDirectory()))) {
            page(res, 404, "ไม่พบลิงก์", "<p>ลิงก์นี้ไม่สามารถใช้งานได้แล้ว</p>");
            return;
        }
        String pwHash = s.optString("pw");
        if (!pwHash.isEmpty() && !unlocked(req, id, pwHash)) {
            if ("POST".equals(req.method) && action.isEmpty()) {
                unlock(req, res, id, pwHash, s);
                return;
            }
            if ("upload".equals(action)) {
                res.error(401, "password_required");
                return;
            }
            passwordForm(res, s, null);
            return;
        }
        if (isRequest) {
            if ("upload".equals(action) && "POST".equals(req.method)) {
                handleRequestUpload(req, res, s, base, owner);
                return;
            }
            if (action.isEmpty()) {
                requestPage(res, s, base);
                return;
            }
            res.error(404, "not found");
            return;
        }
        File target = base;
        String rel = req.param("p");
        if (rel != null && !rel.isEmpty()) {
            if (!base.isDirectory()) {
                res.error(400, "not a folder");
                return;
            }
            target = new File(base, rel).getCanonicalFile();
            String bp = base.getCanonicalPath();
            if (!target.getPath().equals(bp)
                    && !target.getPath().startsWith(bp + File.separator)
                    && !target.getPath().startsWith(bp + "/")) {
                res.error(403, "forbidden");
                return;
            }
        }
        switch (action) {
            case "f":
                String range = req.header("range");
                if (range == null || range.startsWith("bytes=0-")) {
                    bump(s);
                    AuditLog.log(ctx, req, "download", "share:" + s.optString("owner"), target.getPath());
                }
                Storage.sendFile(req, res, target, "1".equals(req.param("dl")));
                return;
            case "zip":
                if (!target.isDirectory()) {
                    res.error(400, "not a folder");
                    return;
                }
                bump(s);
                AuditLog.log(ctx, req, "download", "share:" + s.optString("owner"), target.getPath() + " (.zip)");
                res.header("Content-Disposition", "attachment; filename*=UTF-8''" + Storage.urlEncode(target.getName() + ".zip"));
                OutputStream out = res.begin(200, "application/zip", -1);
                ZipOutputStream z = new ZipOutputStream(new java.io.BufferedOutputStream(out, 65536));
                z.setLevel(Deflater.BEST_SPEED);
                Zip.add(z, target, target.getName());
                z.finish();
                z.flush();
                return;
            case "":
                if (target.isDirectory()) folderPage(res, s, base, target, rel == null ? "" : rel);
                else filePage(res, s, target);
                return;
            default:
                res.error(404, "not found");
        }
    }

    private void handleRequestUpload(HttpServer.Request req, HttpServer.Response res, JSONObject s, File base, Users.User owner) throws IOException {
        String name = Storage.cleanName(req.param("name"));
        if (name == null) {
            res.error(400, "bad file name");
            return;
        }
        long maxBytes = s.optLong("maxBytes", 0L);
        if (maxBytes > 0 && req.contentLength > maxBytes) {
            res.error(413, "file_too_large");
            return;
        }
        if (Storage.exceedsQuota(ctx, owner, req.contentLength)) {
            res.error(507, "quota_exceeded");
            return;
        }
        File saved = Storage.receive(req, base, name, false);
        if (saved == null) {
            res.error(400, "upload incomplete");
            return;
        }
        if (maxBytes > 0 && saved.length() > maxBytes) {
            saved.delete();
            res.error(413, "file_too_large");
            return;
        }
        String mt = req.param("mtime");
        if (mt != null) {
            try { saved.setLastModified(Long.parseLong(mt)); } catch (NumberFormatException ignored) { }
        }
        bump(s);
        AuditLog.log(ctx, req, "request_upload", "request:" + owner.name, saved.getPath());
        LiveEvents.publish("request_upload", base.getPath(), saved.getName());
        JSONObject out = new JSONObject();
        Storage.put(out, "ok", true);
        Storage.put(out, "name", saved.getName());
        Storage.put(out, "size", saved.length());
        res.json(200, out.toString());
    }

    private String cookieValue(String id, String pwHash) {
        byte[] mac = Crypto.hmac("HmacSHA256", Settings.shareSecret(ctx), (id + "|" + pwHash).getBytes(StandardCharsets.UTF_8));
        return Crypto.hex(mac).substring(0, 40);
    }

    private boolean unlocked(HttpServer.Request req, String id, String pwHash) {
        return Crypto.constantTimeEquals(cookieValue(id, pwHash), req.cookie("nss_" + id));
    }

    private void unlock(HttpServer.Request req, HttpServer.Response res, String id, String pwHash, JSONObject s) throws IOException {
        Auth auth = Auth.get(ctx);
        if (auth.locked(req)) {
            passwordForm(res, s, "ใส่ผิดหลายครั้ง กรุณารอสักครู่แล้วลองใหม่");
            return;
        }
        String pw;
        try {
            pw = HttpServer.parseForm(req.bodyString(4096)).get("password");
        } catch (IOException e) {
            res.error(400, "bad request");
            return;
        }
        if (!Crypto.verifyPassword(pw == null ? "" : pw, pwHash)) {
            auth.fail(req);
            passwordForm(res, s, "รหัสผ่านไม่ถูกต้อง");
            return;
        }
        auth.succeed(req);
        String secure = req.secure || req.viaTunnel() ? "; Secure" : "";
        String prefix = req.path != null && req.path.startsWith("/r/") ? "/r/" : "/s/";
        res.header("Set-Cookie", "nss_" + id + "=" + cookieValue(id, pwHash) + "; Path=" + prefix + id + "; HttpOnly; SameSite=Lax; Max-Age=86400" + secure);
        res.redirect(303, prefix + id);
    }

    // ---------------------------------------------------------------- HTML

    private static String fmtSize(long n) {
        String[] u = {"B", "KB", "MB", "GB", "TB"};
        double v = n;
        int i = 0;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return (i == 0 ? String.valueOf(n) : String.format(Locale.ROOT, "%.1f", v)) + " " + u[i];
    }

    private static String fmtDate(long ms) {
        return new SimpleDateFormat("d MMM yyyy HH:mm", Locale.forLanguageTag("th-TH")).format(new Date(ms));
    }

    private void page(HttpServer.Response res, int code, String title, String body) throws IOException {
        page(res, code, title, body, false);
    }

    private void page(HttpServer.Response res, int code, String title, String body, boolean allowScript) throws IOException {
        String csp = allowScript
            ? "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; img-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'"
            : "default-src 'none'; style-src 'unsafe-inline'; img-src 'self'; media-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'";
        res.header("Content-Security-Policy", csp);
        res.header("Cache-Control", "no-store");
        String html = "<!doctype html><html lang=\"th\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><meta name=\"robots\" content=\"noindex\">"
            + "<title>" + Storage.escapeHtml(title) + " · NexShare</title><style>" + CSS + "</style></head><body><main>"
            + "<div class=\"brand\"><span class=\"logo\"></span>NexShare</div>"
            + "<h1>" + Storage.escapeHtml(title) + "</h1>" + body
            + "</main></body></html>";
        res.text(code, "text/html; charset=utf-8", html);
    }

    private void requestPage(HttpServer.Response res, JSONObject s, File base) throws IOException {
        String id = s.optString("id");
        String label = s.optString("label", "");
        String title = label.isEmpty() ? ("ส่งไฟล์เข้า " + base.getName()) : label;
        String body = "<p class=\"muted\">เลือกไฟล์เพื่อส่งเข้าโฟลเดอร์ <b>" + Storage.escapeHtml(base.getName()) + "</b> โดยไม่ต้องใช้บัญชี</p>"
            + "<div class=\"drop\" id=\"drop\" style=\"border:2px dashed #1e6fd9;border-radius:12px;padding:28px 16px;text-align:center;background:rgba(30,111,217,.06);margin:14px 0\">"
            + "<input type=\"file\" id=\"pick\" multiple style=\"position:fixed;left:-9999px;opacity:0\">"
            + "<button type=\"button\" id=\"btnPick\">เลือกไฟล์ที่จะอัปโหลด</button>"
            + "<div class=\"muted\" style=\"margin-top:8px\">หรือลากไฟล์มาวางที่นี่</div>"
            + "</div>"
            + "<ul class=\"list\" id=\"q\"></ul>"
            + expiresNote(s)
            + "<script>"
            + "const pick=document.getElementById('pick'),q=document.getElementById('q'),drop=document.getElementById('drop');"
            + "document.getElementById('btnPick').onclick=()=>pick.click();"
            + "pick.onchange=()=>{if(pick.files.length)send(pick.files);pick.value='';};"
            + "drop.ondragover=e=>{e.preventDefault();drop.style.opacity='.7';};"
            + "drop.ondragleave=()=>{drop.style.opacity='1';};"
            + "drop.ondrop=e=>{e.preventDefault();drop.style.opacity='1';if(e.dataTransfer.files.length)send(e.dataTransfer.files);};"
            + "async function send(files){for(const f of files){"
            + "const li=document.createElement('li');const st=document.createElement('span');st.className='muted';st.textContent='กำลังส่ง…';"
            + "const nm=document.createElement('span');nm.textContent=f.name;"
            + "li.append(nm,st);q.prepend(li);"
            + "try{const r=await fetch('/s/" + id + "/upload?name='+encodeURIComponent(f.name)+'&mtime='+(f.lastModified||0),{method:'POST',body:f});"
            + "const j=await r.json();if(!r.ok)throw new Error(j.error||r.status);st.textContent='✓ ส่งสำเร็จ ('+j.name+')';st.style.color='#1f8f4e';}"
            + "catch(e){st.textContent='✗ ล้มเหลว: '+e.message;st.style.color='#d13c3c';}}}"
            + "</script>";
        page(res, 200, title, body, true);
    }

    private void passwordForm(HttpServer.Response res, JSONObject s, String error) throws IOException {
        String id = s.optString("id");
        page(res, error == null ? 200 : 401, "ลิงก์นี้มีรหัสผ่าน",
            "<p>ใส่รหัสผ่านเพื่อเปิด <b>" + Storage.escapeHtml(s.optString("name")) + "</b></p>"
                + "<form method=\"post\" action=\"/s/" + id + "\"><input type=\"password\" name=\"password\" autofocus required placeholder=\"รหัสผ่าน\">"
                + "<button>เปิด</button></form>"
                + (error == null ? "" : "<p class=\"err\">" + Storage.escapeHtml(error) + "</p>"));
    }

    private String expiresNote(JSONObject s) {
        long exp = s.optLong("expires");
        return exp > 0 ? "<p class=\"muted\">ลิงก์หมดอายุ " + fmtDate(exp) + "</p>" : "";
    }

    private void filePage(HttpServer.Response res, JSONObject s, File f) throws IOException {
        String id = s.optString("id");
        String m = Storage.mime(f.getName());
        String src = "/s/" + id + "/f";
        String preview = "";
        if (m.startsWith("image/") && !m.startsWith("image/svg")) preview = "<img class=\"pv\" src=\"" + src + "\" alt=\"\">";
        else if (m.startsWith("video/")) preview = "<video class=\"pv\" src=\"" + src + "\" controls preload=\"metadata\"></video>";
        else if (m.startsWith("audio/")) preview = "<audio class=\"pv\" src=\"" + src + "\" controls preload=\"metadata\"></audio>";
        page(res, 200, f.getName(),
            preview + "<p class=\"muted\">" + fmtSize(f.length()) + " · แก้ไขล่าสุด " + fmtDate(f.lastModified()) + "</p>"
                + "<a class=\"btn\" href=\"" + src + "?dl=1\">ดาวน์โหลด</a>" + expiresNote(s));
    }

    private void folderPage(HttpServer.Response res, JSONObject s, File base, File dir, String rel) throws IOException {
        String id = s.optString("id");
        File[] files = dir.listFiles();
        if (files == null) files = new File[0];
        Arrays.sort(files, new Comparator<File>() {
            public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        StringBuilder sb = new StringBuilder();
        // breadcrumb
        sb.append("<nav class=\"crumbs\"><a href=\"/s/").append(id).append("\">").append(Storage.escapeHtml(base.getName())).append("</a>");
        String acc = "";
        for (String part : rel.split("/")) {
            if (part.isEmpty()) continue;
            acc = acc.isEmpty() ? part : acc + "/" + part;
            sb.append(" / <a href=\"/s/").append(id).append("?p=").append(Storage.urlEncode(acc)).append("\">")
                .append(Storage.escapeHtml(part)).append("</a>");
        }
        sb.append("</nav><ul class=\"list\">");
        int shown = 0;
        for (File f : files) {
            if (f.getName().startsWith(".")) continue;
            String r = rel.isEmpty() ? f.getName() : rel + "/" + f.getName();
            String q = "?p=" + Storage.urlEncode(r);
            if (f.isDirectory()) {
                sb.append("<li><a href=\"/s/").append(id).append(q).append("\">📁 ").append(Storage.escapeHtml(f.getName())).append("</a></li>");
            } else {
                sb.append("<li><a href=\"/s/").append(id).append("/f").append(q).append("\">")
                    .append(Storage.escapeHtml(f.getName())).append("</a><span class=\"muted\">").append(fmtSize(f.length()))
                    .append(" · <a href=\"/s/").append(id).append("/f").append(q).append("&dl=1\">ดาวน์โหลด</a></span></li>");
            }
            if (++shown >= 2000) break;
        }
        if (shown == 0) sb.append("<li class=\"muted\">โฟลเดอร์ว่าง</li>");
        sb.append("</ul><a class=\"btn\" href=\"/s/").append(id).append("/zip")
            .append(rel.isEmpty() ? "" : "?p=" + Storage.urlEncode(rel)).append("\">ดาวน์โหลดทั้งหมด (.zip)</a>");
        sb.append(expiresNote(s));
        page(res, 200, dir.getName(), sb.toString());
    }

    private static final String CSS =
        "*{box-sizing:border-box}body{margin:0;font:15px/1.5 system-ui,-apple-system,'Segoe UI','Noto Sans Thai',sans-serif;"
            + "background:#eef2f7;color:#1d2733}main{max-width:760px;margin:0 auto;padding:24px 16px}"
            + ".brand{display:flex;align-items:center;gap:8px;font-weight:600;color:#1e6fd9;margin-bottom:12px}"
            + ".logo{width:22px;height:22px;border-radius:6px;background:#1e6fd9;display:inline-block}"
            + "h1{font-size:20px;margin:0 0 12px;word-break:break-word}.muted{color:#6a7785;font-size:13px}"
            + ".btn,button{display:inline-block;background:#1e6fd9;color:#fff;border:0;border-radius:8px;padding:10px 16px;"
            + "font:inherit;text-decoration:none;cursor:pointer;margin-top:8px}"
            + "input{font:inherit;padding:10px;border:1px solid #c9d3de;border-radius:8px;width:100%;max-width:320px;margin-right:8px}"
            + ".err{color:#d13c3c}.pv{max-width:100%;max-height:70vh;border-radius:10px;background:#000;display:block;margin-bottom:8px}"
            + "audio.pv{background:none;width:100%}.list{list-style:none;padding:0;margin:8px 0;background:#fff;border-radius:10px;overflow:hidden}"
            + ".list li{display:flex;justify-content:space-between;gap:12px;padding:10px 14px;border-bottom:1px solid #eef1f4;word-break:break-all}"
            + ".list li:last-child{border-bottom:0}.list a{color:#1d2733;text-decoration:none}.list .muted a{color:#1e6fd9}"
            + ".crumbs{font-size:14px}.crumbs a{color:#1e6fd9;text-decoration:none}"
            + "@media(prefers-color-scheme:dark){body{background:#121820;color:#dbe3ec}.list{background:#1b2330}"
            + ".list li{border-color:#232d3a}.list a{color:#dbe3ec}input{background:#1b2330;color:#dbe3ec;border-color:#2c3847}}";
}
