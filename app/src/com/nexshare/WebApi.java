package com.nexshare;

import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Top-level router: static web UI, share links (/s/), WebDAV (/dav/) and the JSON API.
 * Applies the cross-cutting rules (HTTPS redirect, same-origin check, login, roles, sudo)
 * and hands requests to FileApi / SystemApi / RootApi.
 */
public class WebApi implements HttpServer.Handler {

    private final Context ctx;
    private final Auth auth;
    private final FileApi files;
    private final SystemApi system;
    private final RootApi rootApi;
    private final WebDav dav;
    private final Map<String, byte[]> assetCache = new ConcurrentHashMap<>();

    /** Root routes that can change or reveal the device beyond files: they need a fresh re-auth. */
    private static final List<String> ROOT_SUDO = Arrays.asList(
        "/api/root/exec", "/api/root/screen", "/api/root/input", "/api/root/install", "/api/root/app", "/api/root/stream");

    public WebApi(Context ctx, HttpServer[] serverRef) {
        this.ctx = ctx.getApplicationContext();
        this.auth = Auth.get(ctx);
        this.files = new FileApi(this.ctx);
        this.system = new SystemApi(this.ctx, serverRef);
        this.rootApi = new RootApi(this.ctx);
        this.dav = new WebDav(this.ctx);
    }

    @Override
    public void handle(HttpServer.Request req, HttpServer.Response res) throws IOException {
        String p = req.path;
        if (redirectToHttps(req, res)) return;
        if (WebDav.matches(p)) {
            dav.handle(req, res);
            return;
        }
        securityHeaders(res);
        if (p.startsWith("/s/")) {
            Shares.get(ctx).serve(req, res);
            return;
        }
        if (!p.startsWith("/api/")) {
            serveAsset(p, res);
            return;
        }
        if (!Auth.sameOrigin(req)) {
            res.error(403, "cross-origin request blocked");
            return;
        }
        if (p.equals("/api/login")) {
            auth.login(req, res);
            return;
        }
        if (p.equals("/api/session")) {
            session(req, res);
            return;
        }
        Auth.Who who = auth.who(req);
        if (who == null) {
            res.error(401, "unauthorized");
            return;
        }
        switch (p) {
            case "/api/logout": auth.logout(req, res); return;
            case "/api/sudo": auth.sudo(req, res, who); return;
            default: break;
        }
        if (p.startsWith("/api/root/")) {
            if (!who.user.isAdmin()) {
                res.error(403, "admin_only");
                return;
            }
            if (ROOT_SUDO.contains(p) && !who.sudo()) {
                res.error(403, "sudo_required");
                return;
            }
            try {
                rootApi.handle(req, res);
            } catch (IOException e) {
                // e.g. "root not granted": report it instead of silently dropping the connection.
                if (res.isSent()) throw e;
                res.error(500, String.valueOf(e.getMessage()));
            }
            return;
        }
        if (files.handle(req, res, who)) return;
        if (system.handle(req, res, who)) return;
        res.error(404, "not found");
    }

    private void session(HttpServer.Request req, HttpServer.Response res) throws IOException {
        JSONObject o = new JSONObject();
        auth.sessionInfo(req, o);
        Storage.put(o, "server", Build.MODEL);
        Storage.put(o, "flavor", Flavor.ROOT ? "root" : "normal");
        Storage.put(o, "version", Flavor.VERSION);
        Storage.put(o, "root", Flavor.ROOT && RootShell.available());
        Storage.put(o, "webdav", Settings.webdavEnabled(ctx));
        Storage.put(o, "trash", Settings.trashEnabled(ctx));
        Storage.put(o, "trashDays", Settings.trashDays(ctx));
        Storage.put(o, "httpsPort", ServerService.httpsRunning ? ServerService.HTTPS_PORT : 0);
        res.json(200, o.toString());
    }

    // ---------------------------------------------------------------- cross-cutting

    private void securityHeaders(HttpServer.Response res) {
        res.header("X-Content-Type-Options", "nosniff");
        res.header("X-Frame-Options", "SAMEORIGIN");
        res.header("Referrer-Policy", "same-origin");
    }

    /** With "force HTTPS" on, plain-HTTP LAN visitors are redirected (the tunnel is already HTTPS). */
    private boolean redirectToHttps(HttpServer.Request req, HttpServer.Response res) throws IOException {
        if (req.secure || req.fromLoopback() || !Settings.forceHttps(ctx) || !ServerService.httpsRunning) return false;
        String host = req.header("host");
        if (host == null) return false;
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(']') < colon) host = host.substring(0, colon);
        String q = "";
        StringBuilder qs = new StringBuilder();
        for (Map.Entry<String, List<String>> e : req.query.entrySet())
            for (String v : e.getValue()) qs.append(qs.length() == 0 ? "?" : "&").append(Storage.urlEncode(e.getKey())).append('=').append(Storage.urlEncode(v));
        q = qs.toString();
        res.redirect(req.method.equals("GET") || req.method.equals("HEAD") ? 301 : 308,
            "https://" + host + ":" + ServerService.HTTPS_PORT + (req.rawPath == null ? "/" : req.rawPath) + q);
        return true;
    }

    // ---------------------------------------------------------------- static web UI

    private void serveAsset(String p, HttpServer.Response res) throws IOException {
        if (p.equals("/") || p.isEmpty()) p = "/index.html";
        if (p.contains("..") || p.contains("\\")) {
            res.error(400, "bad path");
            return;
        }
        String name = "web" + p;
        byte[] data = assetCache.get(name);
        if (data == null) {
            try (InputStream in = ctx.getAssets().open(name)) {
                data = Storage.readAll(in);
                assetCache.put(name, data);
            } catch (IOException e) {
                res.error(404, "not found");
                return;
            }
        }
        res.header("Cache-Control", "no-cache");
        if (p.equals("/index.html")) {
            res.header("Content-Security-Policy", "default-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; "
                + "style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'; worker-src 'self'; "
                + "frame-src 'self' blob:; object-src 'none'; base-uri 'none'; frame-ancestors 'self'");
        }
        if (p.equals("/sw.js")) res.header("Service-Worker-Allowed", "/");
        res.bytes(200, Storage.mime(p.toLowerCase(Locale.ROOT)), data);
    }
}
