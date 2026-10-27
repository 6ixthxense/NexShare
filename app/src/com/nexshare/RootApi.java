package com.nexshare;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Routes that only make sense in the root edition: a terminal, live screen capture,
 * and installing / managing apps. Each one checks root first and fails cleanly otherwise.
 */
public class RootApi {

    private final Context ctx;

    RootApi(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    boolean handle(HttpServer.Request req, HttpServer.Response res) throws IOException {
        String p = req.path;
        if (!p.startsWith("/api/root/")) return false;
        switch (p) {
            case "/api/root/status": status(res); return true;
            case "/api/root/exec": exec(req, res); return true;
            case "/api/root/screen": screen(req, res); return true;
            case "/api/root/input": input(req, res); return true;
            case "/api/root/apps": apps(req, res); return true;
            case "/api/root/appicon": appIcon(req, res); return true;
            case "/api/root/install": install(req, res); return true;
            case "/api/root/app": appAction(req, res); return true;
            default: res.error(404, "not found"); return true;
        }
    }

    private void status(HttpServer.Response res) throws IOException {
        JSONObject o = new JSONObject();
        put(o, "flavor", Flavor.ROOT ? "root" : "normal");
        put(o, "root", RootShell.available());
        put(o, "termux", new File("/data/data/com.termux/files/usr/bin").isDirectory());
        put(o, "busybox", ToolBox.hasBusybox(ctx));
        res.json(200, o.toString());
    }

    private void requireRoot() throws IOException {
        if (!Flavor.ROOT) throw new IOException("ต้องใช้ NexShare รุ่น Root");
        if (!RootShell.available()) throw new IOException("ไม่ได้รับสิทธิ์ root (กด Grant ในแอป Magisk/SuperSU)");
    }

    // ---------- terminal ----------

    private void exec(HttpServer.Request req, HttpServer.Response res) throws IOException {
        requireRoot();
        JSONObject body;
        try {
            body = new JSONObject(req.bodyString(1 << 20));
        } catch (Exception e) {
            res.error(400, "bad request");
            return;
        }
        String cmd = body.optString("cmd");
        String cwd = body.optString("cwd", "/data/local/tmp");
        // PATH: our bundled busybox applets, then Termux's tools if that app is present, then system.
        String tx = RootShell.termuxPrefix();
        String bbBin = ToolBox.busyboxBinDir(ctx);
        String path = (bbBin != null ? bbBin + ":" : "") + (new File(tx + "/bin").isDirectory() ? tx + "/bin:" : "") + "/system/bin:/system/xbin:/sbin";
        String wrapped = ToolBox.setupPrelude(ctx)
            + "export PATH=" + path + ":$PATH; export HOME=/data/local/tmp; export TERM=xterm-256color; cd " + RootShell.q(cwd) + " 2>/dev/null; "
            + cmd + "; echo \"\\nNEX_CWD=$(pwd)\"";
        String out;
        try {
            out = RootShell.main().exec(wrapped, 120000);
        } catch (IOException e) {
            res.error(500, e.getMessage());
            return;
        }
        String newCwd = cwd;
        int idx = out.lastIndexOf("NEX_CWD=");
        if (idx >= 0) {
            newCwd = out.substring(idx + 8).trim();
            out = out.substring(0, idx).replaceAll("\\n$", "");
        }
        JSONObject o = new JSONObject();
        put(o, "output", out);
        put(o, "cwd", newCwd);
        res.json(200, o.toString());
    }

    // ---------- live screen ----------

    private void screen(HttpServer.Request req, HttpServer.Response res) throws IOException {
        requireRoot();
        // screencap writes a PNG to stdout; grab it over the root shell.
        byte[] png;
        try {
            png = RootShell.main().execBytes("screencap -p 2>/dev/null", 15000);
        } catch (IOException e) {
            res.error(500, e.getMessage());
            return;
        }
        if (png.length < 8 || (png[0] & 0xff) != 0x89 || png[1] != 'P') {
            res.error(500, "จับภาพหน้าจอไม่ได้");
            return;
        }
        res.header("Cache-Control", "no-store");
        res.bytes(200, "image/png", png);
    }

    private void input(HttpServer.Request req, HttpServer.Response res) throws IOException {
        requireRoot();
        JSONObject b;
        try {
            b = new JSONObject(req.bodyString(8192));
        } catch (Exception e) {
            res.error(400, "bad request");
            return;
        }
        String type = b.optString("type");
        String cmd;
        switch (type) {
            case "tap": cmd = "input tap " + (int) b.optDouble("x") + " " + (int) b.optDouble("y"); break;
            case "swipe": cmd = "input swipe " + (int) b.optDouble("x1") + " " + (int) b.optDouble("y1") + " "
                + (int) b.optDouble("x2") + " " + (int) b.optDouble("y2") + " " + b.optInt("ms", 200); break;
            case "key": cmd = "input keyevent " + Integer.parseInt(b.optString("key").replaceAll("[^0-9]", "")); break;
            case "text": cmd = "input text " + RootShell.q(b.optString("text").replace(" ", "%s")); break;
            default: res.error(400, "bad input"); return;
        }
        try {
            RootShell.main().exec(cmd, 8000);
        } catch (IOException e) {
            res.error(500, e.getMessage());
            return;
        }
        res.json(200, "{\"ok\":true}");
    }

    // ---------- apps / package manager ----------

    private void apps(HttpServer.Request req, HttpServer.Response res) throws IOException {
        boolean system = "1".equals(req.param("system"));
        PackageManager pm = ctx.getPackageManager();
        List<PackageInfo> pkgs = pm.getInstalledPackages(0);
        List<JSONObject> rows = new ArrayList<>();
        for (PackageInfo pi : pkgs) {
            ApplicationInfo ai = pi.applicationInfo;
            if (ai == null) continue;
            boolean isSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (isSystem && !system) continue;
            JSONObject o = new JSONObject();
            put(o, "pkg", pi.packageName);
            put(o, "label", String.valueOf(pm.getApplicationLabel(ai)));
            put(o, "version", pi.versionName);
            put(o, "system", isSystem);
            put(o, "enabled", ai.enabled);
            try {
                File f = new File(ai.sourceDir);
                put(o, "size", f.length());
            } catch (Exception ignored) { }
            rows.add(o);
        }
        Collections.sort(rows, new Comparator<JSONObject>() {
            public int compare(JSONObject a, JSONObject b) {
                return a.optString("label").compareToIgnoreCase(b.optString("label"));
            }
        });
        JSONArray arr = new JSONArray();
        for (JSONObject o : rows) arr.put(o);
        JSONObject out = new JSONObject();
        put(out, "apps", arr);
        put(out, "root", RootShell.available());
        res.json(200, out.toString());
    }

    private void appIcon(HttpServer.Request req, HttpServer.Response res) throws IOException {
        String pkg = req.param("pkg");
        try {
            Drawable d = ctx.getPackageManager().getApplicationIcon(pkg);
            int w = Math.max(1, d.getIntrinsicWidth()), h = Math.max(1, d.getIntrinsicHeight());
            w = Math.min(w, 144);
            h = Math.min(h, 144);
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(bmp);
            d.setBounds(0, 0, w, h);
            d.draw(c);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 90, bo);
            bmp.recycle();
            res.header("Cache-Control", "max-age=86400");
            res.bytes(200, "image/png", bo.toByteArray());
        } catch (Exception e) {
            res.error(404, "no icon");
        }
    }

    /** Upload an APK and install it. With root it is silent; otherwise it opens the installer prompt. */
    private void install(HttpServer.Request req, HttpServer.Response res) throws IOException {
        if (req.contentLength <= 0 || req.contentLength > 2L * 1024 * 1024 * 1024) {
            res.error(400, "ไฟล์ APK ไม่ถูกต้อง");
            return;
        }
        File dir = new File(ctx.getCacheDir(), "apk");
        dir.mkdirs();
        File apk = new File(dir, "upload-" + System.currentTimeMillis() + ".apk");
        boolean ok = false;
        try (OutputStream out = new FileOutputStream(apk)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = req.body.read(buf)) > 0) out.write(buf, 0, n);
            ok = req.body.remaining() == 0;
        }
        if (!ok) {
            apk.delete();
            res.error(400, "อัปโหลดไม่ครบ");
            return;
        }
        if (RootShell.available()) {
            String out;
            try {
                out = RootShell.main().exec("pm install -r -g " + RootShell.q(apk.getAbsolutePath()), 180000);
            } catch (IOException e) {
                apk.delete();
                res.error(500, e.getMessage());
                return;
            }
            apk.delete();
            if (out.toLowerCase().contains("success")) res.json(200, "{\"ok\":true,\"mode\":\"root\"}");
            else res.error(500, "ติดตั้งไม่สำเร็จ: " + out.trim());
        } else {
            // Non-root: hand the APK to the system installer via a content URI.
            try {
                Uri uri = NexFileProvider.uriFor(ctx, apk);
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                ctx.startActivity(i);
                res.json(200, "{\"ok\":true,\"mode\":\"prompt\"}");
            } catch (Exception e) {
                res.error(500, "เปิดตัวติดตั้งไม่ได้: " + e.getMessage());
            }
        }
    }

    private void appAction(HttpServer.Request req, HttpServer.Response res) throws IOException {
        String pkg = req.param("pkg");
        String action = req.param("action");
        if (pkg == null || pkg.equals(ctx.getPackageName())) {
            res.error(400, "ไม่อนุญาต");
            return;
        }
        if (action.equals("open")) {
            Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) {
                res.error(404, "เปิดแอปนี้ไม่ได้");
                return;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            res.json(200, "{\"ok\":true}");
            return;
        }
        requireRoot();
        String cmd;
        switch (action) {
            case "uninstall": cmd = "pm uninstall " + RootShell.q(pkg); break;
            case "disable": cmd = "pm disable-user --user 0 " + RootShell.q(pkg); break;
            case "enable": cmd = "pm enable " + RootShell.q(pkg); break;
            case "clear": cmd = "pm clear " + RootShell.q(pkg); break;
            case "force-stop": cmd = "am force-stop " + RootShell.q(pkg); break;
            default: res.error(400, "bad action"); return;
        }
        try {
            String out = RootShell.main().exec(cmd, 60000);
            res.json(200, "{\"ok\":true,\"output\":" + JSONObject.quote(out.trim()) + "}");
        } catch (IOException e) {
            res.error(500, e.getMessage());
        }
    }

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (Exception ignored) { }
    }

    static String osInfo() {
        return Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE;
    }
}
