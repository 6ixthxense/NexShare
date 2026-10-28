package com.nexshare;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.TrafficStats;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.nexshare.Storage.put;

/** System stats, Control Panel settings, users, 2FA, HTTPS, tunnel and Download Station. */
final class SystemApi {

    private final Context ctx;
    private final HttpServer[] serverRef;
    private long[] lastCpu;

    SystemApi(Context ctx, HttpServer[] serverRef) {
        this.ctx = ctx;
        this.serverRef = serverRef;
    }

    boolean handle(HttpServer.Request req, HttpServer.Response res, Auth.Who who) throws IOException {
        Users.User u = who.user;
        String p = req.path;
        if (p.equals("/api/system")) {
            system(res);
            return true;
        }
        if (p.startsWith("/api/dl/")) {
            if (!u.canWrite()) {
                res.error(403, "read_only");
                return true;
            }
            downloads(req, res, u);
            return true;
        }
        boolean adminRoute = p.equals("/api/wallpaper") || p.startsWith("/api/tunnel/") || p.startsWith("/api/settings")
            || p.startsWith("/api/users") || p.startsWith("/api/2fa/") || p.equals("/api/admin-password") || p.startsWith("/api/tls/");
        if (!adminRoute) return false;
        if (!u.isAdmin()) {
            res.error(403, "admin_only");
            return true;
        }
        // Read-only views and the tunnel switch just need an admin; changing security settings needs sudo.
        boolean needsSudo = (p.equals("/api/settings") && "POST".equals(req.method))
            || (p.startsWith("/api/users") && !p.equals("/api/users")) || p.startsWith("/api/2fa/")
            || p.equals("/api/admin-password") || p.equals("/api/tls/regenerate") || p.equals("/api/tunnel/start");
        if (needsSudo && !who.sudo()) {
            res.error(403, "sudo_required");
            return true;
        }
        switch (p) {
            case "/api/wallpaper": setWallpaper(req, res, u); return true;
            case "/api/tunnel/status": tunnelStatus(res); return true;
            case "/api/tunnel/start": tunnelStart(req, res); return true;
            case "/api/tunnel/stop":
                TunnelManager.get(ctx).stop();
                Settings.setTunnel(ctx, "off", Settings.tunnelToken(ctx));
                tunnelStatus(res);
                return true;
            case "/api/settings":
                if ("POST".equals(req.method)) saveSettings(req, res);
                else settings(res);
                return true;
            case "/api/users": res.json(200, "{\"users\":" + Users.get(ctx).list() + "}"); return true;
            case "/api/users/save": saveUser(req, res); return true;
            case "/api/users/delete": deleteUser(req, res); return true;
            case "/api/2fa/setup": totpSetup(res); return true;
            case "/api/2fa/enable": totpEnable(req, res); return true;
            case "/api/2fa/disable": Settings.setTotp(ctx, "", Settings.TOTP_OFF); res.json(200, "{\"ok\":true}"); return true;
            case "/api/admin-password": adminPassword(req, res, who); return true;
            case "/api/tls/cert": tlsCert(res); return true;
            case "/api/tls/regenerate": tlsRegenerate(res); return true;
            default: res.error(404, "not found"); return true;
        }
    }

    private JSONObject body(HttpServer.Request req, HttpServer.Response res) throws IOException {
        try {
            return new JSONObject(req.bodyString(1 << 20));
        } catch (Exception e) {
            res.error(400, "bad request");
            return null;
        }
    }

    // ---------------------------------------------------------------- settings

    private void settings(HttpServer.Response res) throws IOException {
        JSONObject o = new JSONObject();
        put(o, "pinEnabled", Settings.pinEnabled(ctx));
        put(o, "adminPassword", Settings.hasAdminPassword(ctx));
        put(o, "totpMode", Settings.totpMode(ctx));
        put(o, "https", Settings.httpsEnabled(ctx));
        put(o, "httpsPort", ServerService.HTTPS_PORT);
        put(o, "httpsRunning", ServerService.httpsRunning);
        put(o, "forceHttps", Settings.forceHttps(ctx));
        put(o, "tlsFingerprint", Settings.httpsEnabled(ctx) ? TlsManager.get(ctx).fingerprint() : "");
        put(o, "trash", Settings.trashEnabled(ctx));
        put(o, "trashDays", Settings.trashDays(ctx));
        put(o, "webdav", Settings.webdavEnabled(ctx));
        put(o, "webdavTunnel", Settings.webdavViaTunnel(ctx));
        put(o, "sessions", SessionStore.get(ctx).count());
        res.json(200, o.toString());
    }

    private void saveSettings(HttpServer.Request req, HttpServer.Response res) throws IOException {
        JSONObject b = body(req, res);
        if (b == null) return;
        boolean restart = false;
        if (b.has("https") && b.optBoolean("https") != Settings.httpsEnabled(ctx)) {
            Settings.setHttpsEnabled(ctx, b.optBoolean("https"));
            restart = true;
        }
        if (b.has("forceHttps")) Settings.setForceHttps(ctx, b.optBoolean("forceHttps") && Settings.httpsEnabled(ctx));
        if (b.has("trash")) Settings.setTrashEnabled(ctx, b.optBoolean("trash"));
        if (b.has("trashDays")) Settings.setTrashDays(ctx, b.optInt("trashDays", 30));
        if (b.has("webdav")) Settings.setWebdavEnabled(ctx, b.optBoolean("webdav"));
        if (b.has("webdavTunnel")) Settings.setWebdavViaTunnel(ctx, b.optBoolean("webdavTunnel"));
        if (b.optBoolean("logoutAll")) SessionStore.get(ctx).revokeAll();
        if (restart) ServerService.restartHttps(ctx);
        settings(res);
    }

    // ---------------------------------------------------------------- users

    private void saveUser(HttpServer.Request req, HttpServer.Response res) throws IOException {
        JSONObject b = body(req, res);
        if (b == null) return;
        List<String> folders = new ArrayList<>();
        JSONArray f = b.optJSONArray("folders");
        for (int i = 0; f != null && i < f.length(); i++) {
            String path = f.optString(i).trim();
            if (path.isEmpty()) continue;
            try {
                File cf = Storage.resolve(ctx, null, path);
                if (!cf.isDirectory()) throw new SecurityException("not a folder");
                folders.add(cf.getPath());
            } catch (SecurityException e) {
                res.error(400, "โฟลเดอร์ไม่ถูกต้อง: " + path);
                return;
            }
        }
        String name = b.optString("name");
        String password = b.optString("password", "");
        Users.User before = Users.get(ctx).find(name);
        String err = Users.get(ctx).save(name, b.optString("role"), folders, password);
        if (err != null) {
            res.error(400, err);
            return;
        }
        // Role/folder/password changes take effect immediately: end the user's existing sessions.
        if (before != null) SessionStore.get(ctx).revokeUser(name);
        Auth.get(ctx).clearBasicCache();
        res.json(200, "{\"users\":" + Users.get(ctx).list() + "}");
    }

    private void deleteUser(HttpServer.Request req, HttpServer.Response res) throws IOException {
        String name = req.param("name");
        if (name != null && Users.get(ctx).delete(name)) {
            SessionStore.get(ctx).revokeUser(name);
            Auth.get(ctx).clearBasicCache();
        }
        res.json(200, "{\"users\":" + Users.get(ctx).list() + "}");
    }

    private void adminPassword(HttpServer.Request req, HttpServer.Response res, Auth.Who who) throws IOException {
        if (!Users.ADMIN.equals(who.user.name)) {
            res.error(403, "only the phone owner can change this");
            return;
        }
        JSONObject b = body(req, res);
        if (b == null) return;
        String pw = b.optString("password", "");
        if (!pw.isEmpty() && pw.length() < 8) {
            res.error(400, "รหัสผ่านต้องยาวอย่างน้อย 8 ตัวอักษร");
            return;
        }
        Settings.setAdminPassword(ctx, pw); // also logs everyone out
        Auth.get(ctx).clearBasicCache();
        res.json(200, "{\"ok\":true}");
    }

    // ---------------------------------------------------------------- 2FA

    private String pendingTotp;

    private synchronized void totpSetup(HttpServer.Response res) throws IOException {
        pendingTotp = Crypto.base32Encode(Crypto.random(20));
        String label = "NexShare:" + Settings.serverName(ctx);
        String uri = "otpauth://totp/" + Storage.urlEncode(label) + "?secret=" + pendingTotp + "&issuer=NexShare&digits=6&period=30";
        JSONObject o = new JSONObject();
        put(o, "secret", pendingTotp);
        put(o, "uri", uri);
        res.json(200, o.toString());
    }

    /** POST {code, mode: "tunnel"|"always"}: confirms the authenticator works before turning 2FA on. */
    private synchronized void totpEnable(HttpServer.Request req, HttpServer.Response res) throws IOException {
        JSONObject b = body(req, res);
        if (b == null) return;
        String mode = b.optString("mode", Settings.TOTP_TUNNEL);
        if (!Settings.TOTP_TUNNEL.equals(mode) && !Settings.TOTP_ALWAYS.equals(mode)) {
            res.error(400, "bad mode");
            return;
        }
        if (pendingTotp == null) {
            // Changing only the mode of an existing setup.
            if (Settings.totpSecret(ctx).isEmpty()) {
                res.error(400, "start setup first");
                return;
            }
            Settings.setTotp(ctx, Settings.totpSecret(ctx), mode);
            res.json(200, "{\"ok\":true}");
            return;
        }
        if (!Auth.checkNewOtp(pendingTotp, b.optString("code"))) {
            res.error(400, "รหัสจากแอปยืนยันตัวตนไม่ถูกต้อง");
            return;
        }
        Settings.setTotp(ctx, pendingTotp, mode);
        Settings.setTotpLastStep(ctx, -1);
        pendingTotp = null;
        res.json(200, "{\"ok\":true}");
    }

    // ---------------------------------------------------------------- TLS

    private void tlsCert(HttpServer.Response res) throws IOException {
        try {
            res.header("Content-Disposition", "attachment; filename=\"nexshare.crt\"");
            res.text(200, "application/x-x509-ca-cert", TlsManager.get(ctx).pem());
        } catch (Exception e) {
            res.error(500, String.valueOf(e.getMessage()));
        }
    }

    private void tlsRegenerate(HttpServer.Response res) throws IOException {
        try {
            TlsManager.get(ctx).regenerate();
            ServerService.restartHttps(ctx);
            res.json(200, "{\"fingerprint\":" + HttpServer.jsonQuote(TlsManager.get(ctx).fingerprint()) + "}");
        } catch (Exception e) {
            res.error(500, String.valueOf(e.getMessage()));
        }
    }

    // ---------------------------------------------------------------- wallpaper

    /** Sets the phone wallpaper from either a stored file (?path=) or a POSTed image body. */
    private void setWallpaper(HttpServer.Request req, HttpServer.Response res, Users.User u) throws IOException {
        android.graphics.Bitmap bmp;
        if (req.param("path") != null) {
            File f = Storage.resolve(ctx, u, req.param("path"));
            if (!f.isFile()) { res.error(404, "not found"); return; }
            bmp = android.graphics.BitmapFactory.decodeFile(f.getPath());
        } else {
            if (req.contentLength <= 0 || req.contentLength > 64 * 1024 * 1024) { res.error(400, "no image"); return; }
            byte[] data = req.bodyBytes(64 * 1024 * 1024);
            bmp = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length);
        }
        if (bmp == null) { res.error(400, "ไม่ใช่รูปภาพ"); return; }
        try {
            android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(ctx);
            String which = req.param("which");
            if (Build.VERSION.SDK_INT >= 24 && "lock".equals(which)) wm.setBitmap(bmp, null, true, android.app.WallpaperManager.FLAG_LOCK);
            else if (Build.VERSION.SDK_INT >= 24 && "home".equals(which)) wm.setBitmap(bmp, null, true, android.app.WallpaperManager.FLAG_SYSTEM);
            else wm.setBitmap(bmp);
            bmp.recycle();
            res.json(200, "{\"ok\":true}");
        } catch (Exception e) {
            res.error(500, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- cloudflare tunnel

    private void tunnelStatus(HttpServer.Response res) throws IOException {
        TunnelManager tm = TunnelManager.get(ctx);
        JSONObject o = new JSONObject();
        put(o, "supported", tm.supported());
        put(o, "state", tm.state().name().toLowerCase(Locale.ROOT));
        put(o, "url", tm.url());
        put(o, "error", tm.error());
        put(o, "mode", Settings.tunnelMode(ctx));
        put(o, "hasToken", !Settings.tunnelToken(ctx).isEmpty());
        put(o, "log", tm.logTail());
        put(o, "totpMode", Settings.totpMode(ctx));
        res.json(200, o.toString());
    }

    private void tunnelStart(HttpServer.Request req, HttpServer.Response res) throws IOException {
        JSONObject b = body(req, res);
        if (b == null) return;
        String mode = b.optString("mode", "quick");
        String token = b.optString("token", Settings.tunnelToken(ctx));
        // Exposing the files to the internet without a PIN would be reckless: turn it on.
        if (!Settings.pinEnabled(ctx)) Settings.setPinEnabled(ctx, true);
        try {
            TunnelManager.get(ctx).start(mode, token);
        } catch (Exception e) {
            res.error(500, e.getMessage());
            return;
        }
        tunnelStatus(res);
    }

    // ---------------------------------------------------------------- download station

    private void downloads(HttpServer.Request req, HttpServer.Response res, Users.User u) throws IOException {
        DownloadManager dm = DownloadManager.get(ctx);
        switch (req.path) {
            case "/api/dl/list": dlList(res); return;
            case "/api/dl/add": dlAdd(req, res, u); return;
            case "/api/dl/torrent": dlTorrent(req, res, u); return;
            case "/api/dl/pause": dm.pause(req.param("id")); dlList(res); return;
            case "/api/dl/resume": dm.resume(req.param("id")); dlList(res); return;
            case "/api/dl/remove": dm.remove(req.param("id"), "1".equals(req.param("files"))); dlList(res); return;
            case "/api/dl/clear": dm.clearFinished(); dlList(res); return;
            default: res.error(404, "not found");
        }
    }

    private void dlList(HttpServer.Response res) throws IOException {
        DownloadManager dm = DownloadManager.get(ctx);
        JSONObject o = new JSONObject();
        put(o, "tasks", dm.list());
        put(o, "defaultDir", DownloadManager.defaultDir().getAbsolutePath());
        put(o, "torrent", dm.torrentSupported());
        res.json(200, o.toString());
    }

    private File dlDir(Users.User u, String p) throws IOException {
        File d = p == null || p.isEmpty() ? DownloadManager.defaultDir() : Storage.resolve(ctx, u, p);
        if (!u.allows(d.getCanonicalFile())) throw new SecurityException("choose a folder you can write to");
        return d;
    }

    private void dlAdd(HttpServer.Request req, HttpServer.Response res, Users.User u) throws IOException {
        JSONObject body = body(req, res);
        if (body == null) return;
        File dir = dlDir(u, body.optString("dest", null));
        String urls = body.optString("urls", "");
        int added = 0;
        StringBuilder errors = new StringBuilder();
        for (String line : urls.split("\\s*\\n\\s*")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            try {
                DownloadManager.get(ctx).addUrl(line, dir);
                added++;
            } catch (IOException e) {
                errors.append(e.getMessage()).append('\n');
            }
        }
        if (added == 0) {
            res.error(400, errors.length() > 0 ? errors.toString().trim() : "no links");
            return;
        }
        res.json(200, "{\"added\":" + added + "}");
    }

    private void dlTorrent(HttpServer.Request req, HttpServer.Response res, Users.User u) throws IOException {
        if (req.contentLength <= 0 || req.contentLength > 20 * 1024 * 1024) {
            res.error(400, "bad torrent file");
            return;
        }
        byte[] data = req.bodyBytes(20 * 1024 * 1024);
        try {
            DownloadManager.get(ctx).addTorrent(null, data, dlDir(u, req.param("dest")));
        } catch (IOException e) {
            res.error(400, e.getMessage());
            return;
        }
        res.json(200, "{\"added\":1}");
    }

    // ---------------------------------------------------------------- system

    private void system(HttpServer.Response res) throws IOException {
        JSONObject o = new JSONObject();
        put(o, "name", Settings.serverName(ctx));
        put(o, "model", Build.MANUFACTURER + " " + Build.MODEL);
        put(o, "android", Build.VERSION.RELEASE);
        put(o, "sdk", Build.VERSION.SDK_INT);
        put(o, "uptime", SystemClock.elapsedRealtime());
        put(o, "cores", Runtime.getRuntime().availableProcessors());
        Inet4Address ip = MdnsResponder.lanAddress();
        put(o, "ip", ip == null ? "" : ip.getHostAddress());
        put(o, "host", MdnsResponder.HOST);
        put(o, "port", ServerService.PORT);
        put(o, "httpsPort", ServerService.httpsRunning ? ServerService.HTTPS_PORT : 0);
        HttpServer s = serverRef[0];
        put(o, "connections", s == null ? 0 : s.activeConnections.get());

        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        put(o, "memTotal", mi.totalMem);
        put(o, "memAvail", mi.availMem);

        put(o, "cpu", cpuPercent());

        StatFs st = new StatFs(Environment.getExternalStorageDirectory().getPath());
        put(o, "diskTotal", st.getTotalBytes());
        put(o, "diskFree", st.getAvailableBytes());

        Intent bat = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat != null) {
            int level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            put(o, "battery", level < 0 ? -1 : Math.round(level * 100f / scale));
            put(o, "charging", bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0);
            put(o, "batteryTemp", bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0);
        }
        put(o, "rx", TrafficStats.getTotalRxBytes());
        put(o, "tx", TrafficStats.getTotalTxBytes());
        put(o, "time", System.currentTimeMillis());
        res.json(200, o.toString());
    }

    /** /proc/stat when readable; otherwise average current/max CPU frequency as a load estimate. */
    private synchronized double cpuPercent() {
        try (RandomAccessFile r = new RandomAccessFile("/proc/stat", "r")) {
            String[] t = r.readLine().trim().split("\\s+");
            long idle = Long.parseLong(t[4]) + Long.parseLong(t[5]);
            long total = 0;
            for (int i = 1; i < t.length; i++) total += Long.parseLong(t[i]);
            long[] cur = {total, idle};
            double pct = -1;
            if (lastCpu != null && cur[0] > lastCpu[0])
                pct = 100.0 * (1 - (double) (cur[1] - lastCpu[1]) / (cur[0] - lastCpu[0]));
            lastCpu = cur;
            if (pct >= 0) return Math.round(pct * 10) / 10.0;
        } catch (Exception ignored) { }
        double sum = 0;
        int n = 0;
        for (int i = 0; i < 16; i++) {
            long c = readLong("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq");
            long m = readLong("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq");
            if (c > 0 && m > 0) {
                sum += (double) c / m;
                n++;
            }
        }
        return n == 0 ? -1 : Math.round(sum / n * 1000) / 10.0;
    }

    private static long readLong(String path) {
        try (RandomAccessFile r = new RandomAccessFile(path, "r")) {
            return Long.parseLong(r.readLine().trim());
        } catch (Exception e) {
            return -1;
        }
    }
}
