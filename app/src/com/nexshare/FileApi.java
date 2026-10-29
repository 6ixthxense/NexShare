package com.nexshare;

import android.content.Context;
import android.os.Environment;
import android.os.StatFs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.ZipOutputStream;

import static com.nexshare.Storage.put;

/** File Station routes: browsing, transfers, file operations, trash and share links. */
final class FileApi {

    private final Context ctx;
    private final ThumbCache thumbs;

    FileApi(Context ctx) {
        this.ctx = ctx;
        this.thumbs = new ThumbCache(ctx);
    }

    /** Returns false when the path isn't one of ours. */
    boolean handle(HttpServer.Request req, HttpServer.Response res, Auth.Who who) throws IOException {
        Users.User u = who.user;
        switch (req.path) {
            case "/api/roots": roots(res, u); return true;
            case "/api/list": list(req, res, u); return true;
            case "/api/search": search(req, res, u); return true;
            case "/api/media": media(req, res, u); return true;
            case "/api/file": {
                File f = Storage.resolve(ctx, u, req.param("path"));
                boolean dl = "1".equals(req.param("dl"));
                String range = req.header("range");
                if (dl && (range == null || range.startsWith("bytes=0-"))) {
                    AuditLog.log(ctx, req, "download", u.name, f.getPath());
                }
                Storage.sendFile(req, res, f, dl);
                return true;
            }
            case "/api/thumb": thumb(req, res, u); return true;
            case "/api/zip": zip(req, res, u); return true;
            case "/api/trash/list": trashList(res, u); return true;
            case "/api/share/list": res.json(200, wrap("shares", Shares.get(ctx).list(u))); return true;
            default: break;
        }
        if (!req.path.startsWith("/api/upload") && !WRITE_ROUTES.contains(req.path)) return false;
        if (!u.canWrite()) {
            res.error(403, "read_only");
            return true;
        }
        switch (req.path) {
            case "/api/upload/status": uploadStatus(req, res, u); return true;
            case "/api/upload": upload(req, res, u); return true;
            case "/api/mkdir": mkdir(req, res, u); return true;
            case "/api/rename": rename(req, res, u); return true;
            case "/api/delete": delete(req, res, u); return true;
            case "/api/move": copyMove(req, res, u, true); return true;
            case "/api/copy": copyMove(req, res, u, false); return true;
            case "/api/exists": exists(req, res, u); return true;
            case "/api/trash/restore": trashRestore(req, res, u); return true;
            case "/api/trash/purge": trashPurge(req, res, u); return true;
            case "/api/trash/empty": {
                int n = Trash.empty(ctx, u);
                if (n > 0) AuditLog.log(ctx, req, "purge", u.name, "empty (" + n + ")");
                res.json(200, "{\"purged\":" + n + "}");
                return true;
            }
            case "/api/share/create": shareCreate(req, res, u); return true;
            case "/api/share/delete": {
                String sid = req.param("id");
                boolean ok = Shares.get(ctx).delete(u, sid);
                if (ok) AuditLog.log(ctx, req, "share_delete", u.name, sid);
                res.json(200, "{\"ok\":" + ok + "}");
                return true;
            }
            default: return false;
        }
    }

    private static final List<String> WRITE_ROUTES = java.util.Arrays.asList(
        "/api/mkdir", "/api/rename", "/api/delete", "/api/move", "/api/copy", "/api/exists",
        "/api/trash/restore", "/api/trash/purge", "/api/trash/empty", "/api/share/create", "/api/share/delete");

    private static String wrap(String key, Object v) {
        JSONObject o = new JSONObject();
        put(o, key, v);
        return o.toString();
    }

    // ---------------------------------------------------------------- browsing

    private void roots(HttpServer.Response res, Users.User u) throws IOException {
        JSONArray arr = new JSONArray();
        List<File> roots = Storage.rootDirs(ctx);
        for (int i = 0; i < roots.size(); i++) {
            File r = roots.get(i);
            String path = r.getCanonicalPath();
            File cr = new File(path);
            if (!u.allows(cr) && !u.canTraverse(cr)) continue;
            JSONObject o = new JSONObject();
            put(o, "name", Storage.rootName(i));
            put(o, "id", Storage.rootId(i));
            put(o, "path", path);
            try {
                StatFs st = new StatFs(path);
                put(o, "total", st.getTotalBytes());
                put(o, "free", st.getAvailableBytes());
            } catch (Exception ignored) { }
            put(o, "readable", r.listFiles() != null);
            arr.put(o);
        }
        JSONObject out = new JSONObject();
        put(out, "roots", arr);
        put(out, "folders", new JSONArray(u.folders));
        put(out, "quota", u.quotaBytes);
        if (u.quotaBytes > 0) put(out, "used", Storage.userUsedBytes(ctx, u));
        res.json(200, out.toString());
    }

    private void list(HttpServer.Request req, HttpServer.Response res, Users.User u) throws IOException {
        File dir = Storage.resolveBrowse(ctx, u, req.param("path"));
        if (!dir.isDirectory()) {
            res.error(404, "not a folder");
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            res.error(403, "no_permission");
            return;
        }
        JSONArray arr = new JSONArray();
        for (File f : files) {
            if (f.getName().equals(Storage.TRASH_DIR) || f.getName().endsWith(".nexpart")) continue;
            if (!u.folders.isEmpty()) {
                File cf = f.getCanonicalFile();
                if (!u.allows(cf) && !u.canTraverse(cf)) continue;
            }
            arr.put(Storage.entry(f));
        }
        JSONObject o = new JSONObject();
        put(o, "path", dir.getPath());
        boolean top = Storage.isRoot(ctx, dir) || (!u.folders.isEmpty() && !u.allows(dir.getParentFile()) && !u.canTraverse(dir.getParentFile()));
        put(o, "parent", top ? JSONObject.NULL : dir.getParent());
        put(o, "writable", u.canWrite() && u.allows(dir));
        put(o, "entries", arr);
        res.json(200, o.toString());
    }

    private void search(HttpServer.Request req, HttpServer.Response res, Users.User u) throws IOException {
        File dir = Storage.resolveBrowse(ctx, u, req.param("path"));
        String q = req.param("q") == null ? "" : req.param("q").toLowerCase(Locale.ROOT);
        JSONArray arr = new JSONArray();
        if (!q.isEmpty()) walkSearch(dir, q, arr, 0, u);
        JSONObject o = new JSONObject();
        put(o, "entries", arr);
        res.json(200, o.toString());
    }

    private void walkSearch(File dir, String q, JSONArray out, int depth, Users.User u) {
        File[] files = dir.listFiles();
        if (files == null || depth > 12) return;
        for (File f : files) {
            if (out.length() >= 500) return;
            if (f.getName().equals(Storage.TRASH_DIR)) continue;
            if (!u.folders.isEmpty() && !u.allows(f) && !u.canTraverse(f)) continue;
            if (f.getName().toLowerCase(Locale.ROOT).contains(q) && u.allows(f)) out.put(Storage.entry(f));
            if (f.isDirectory() && !f.getName().equals("Android")) walkSearch(f, q, out, depth + 1, u);
        }
    }

    /** Recent photos and videos from the usual camera / picture folders, newest first. */
    private void media(HttpServer.Request req, HttpServer.Response res, Users.User u) throws IOException {
        String type = req.param("type") == null ? "all" : req.param("type");
        File base = Environment.getExternalStorageDirectory().getCanonicalFile();
        List<File> found = new ArrayList<>();
        for (String d : new String[]{"DCIM", "Pictures", "Movies", "Music", "Download", "Recordings"}) {
            collectMedia(new File(base, d), type, found, 0, u);
        }
        Collections.sort(found, new Comparator<File>() {
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        JSONArray arr = new JSONArray();
        for (int i = 0; i < found.size() && i < 2000; i++) arr.put(Storage.entry(found.get(i)));
        JSONObject o = new JSONObject();
        put(o, "entries", arr);
        res.json(200, o.toString());
    }

    private void collectMedia(File dir, String type, List<File> out, int depth, Users.User u) {
        File[] files = dir.listFiles();
        if (files == null || depth > 6) return;
        for (File f : files) {
            if (f.getName().startsWith(".")) continue;
            if (!u.folders.isEmpty() && !u.allows(f) && !u.canTraverse(f)) continue;
            if (f.isDirectory()) collectMedia(f, type, out, depth + 1, u);
            else if (u.allows(f)) {
                String m = Storage.mime(f.getName());
                boolean img = m.startsWith("image/"), vid = m.startsWith("video/"), aud = m.startsWith("audio/");
                if ((type.equals("image") && img) || (type.equals("video") && vid) || (type.equals("audio") && aud)
                    || (type.equals("all") && (img || vid))) out.add(f);
            }
        }
    }

    private void thumb(HttpServer.Request req, HttpServer.Response res, Users.User u) throws IOException {
        File f = Storage.resolve(ctx, u, req.param("path"));
        int size = 320;
        try {
            if (req.param("s") != null) size = Math.max(64, Math.min(1024, Integer.parseInt(req.param("s"))));
        } catch (NumberFormatException ignored) { }
        byte[] data = f.isFile() ? thumbs.get(f, size) : null;
        if (data == null) {
            res.error(404, "no thumbnail");
            return;
        }
        res.header("Cache-Control", "private, max-age=86400");
        res.bytes(200, "image/jpeg", data);
    }

    private void zip(HttpServer.Request req, HttpServer.Response res, Users.User u) throws IOException {
        List<File> items = new ArrayList<>();
        for (String p : req.params("p")) items.add(Storage.resolve(ctx, u, p));
        if (items.isEmpty()) {
            res.error(400, "nothing selected");
            return;
        }
        String name = items.size() == 1 ? items.get(0).getName() : "NexShare";
        res.header("Content-Disposition", "attachment; filename*=UTF-8''" + Storage.urlEncode(name + ".zip"));
        OutputStream out = res.begin(200, "application/zip", -1);
        ZipOutputStream z = new ZipOutputStream(new java.io.BufferedOutputStream(out, 65536));
        z.setLevel(Deflater.BEST_SPEED);
        for (File f : items) Zip.add(z, f, f.getName());
        z.finish();
        z.flush();
    }

    // ---------------------------------------------------------------- writing

    private File writableDir(Users.User u, String path) throws IOException {
        File dir = Storage.resolve(ctx, u, path);
        if (!u.allows(dir)) throw new SecurityException("not allowed");
        return dir;
    }

    private File resolveUploadDir(Users.User u, String basePath, String rel, boolean createDirs) throws IOException {
        File dir = writableDir(u, basePath);
        if (rel != null && !rel.isEmpty()) {
            for (String part : rel.split("/")) {
                if (part.isEmpty() || part.equals(".") || part.equals("..")) continue;
                dir = new File(dir, Storage.cleanName(part));
            }
            dir = Storage.resolve(ctx, u, dir.getPath());
            if (createDirs && !dir.isDirectory() && !dir.mkdirs()) {
                return null;
            }
        }
        return dir;
    }

    /** GET /api/upload/status?path=...&name=...&rel=...&size=... — returns {offset, exists} for resumable uploads. */
    private void uploadStatus(HttpServer.Request req, HttpServer.Response res, Users.User u) throws IOException {
        File dir = resolveUploadDir(u, req.param("path"), req.param("rel"), false);
        String name = Storage.cleanName(req.param("name"));
        if (name == null || dir == null) {
            res.error(400, "bad target");
            return;
        }
        long expected = -1;
        if (req.param("size") != null) {
            try { expected = Long.parseLong(req.param("size")); } catch (NumberFormatException ignored) { }
        }
        long offset = dir.isDirectory() ? Storage.partialSize(dir, name, expected) : 0;
        File existing = new File(dir, name);
        JSONObject out = new JSONObject();
        put(out, "offset", offset);
        put(out, "exists", existing.isFile());
        res.json(200, out.toString());
    }

    private void upload(HttpServer.Request req, HttpServer.Response res, Users.User u) throws IOException {
        if (!"POST".equals(req.method) && !"PUT".equals(req.method)) {
            res.error(400, "use POST");
            return;
        }
        File dir = resolveUploadDir(u, req.param("path"), req.param("rel"), true);
        if (dir == null) {
            res.error(500, "cannot create folder");
            return;
        }
        String name = Storage.cleanName(req.param("name"));
        if (name == null || !dir.isDirectory()) {
            res.error(400, "bad target");
            return;
        }
        boolean overwrite = "1".equals(req.param("overwrite"));
        if (overwrite && new File(dir, name).isDirectory()) {
            res.error(409, "a folder has that name");
            return;
        }
        long chunkLen = Math.max(0L, req.contentLength);
        if (Storage.exceedsQuota(ctx, u, chunkLen)) {
            res.error(507, "quota_exceeded");
            return;
        }
        boolean resumable = "1".equals(req.param("resumable")) || req.param("offset") != null || req.param("total") != null;
        if (resumable) {
            long offset = 0;
            long total = -1;
            if (req.param("offset") != null) {
                try { offset = Math.max(0L, Long.parseLong(req.param("offset"))); } catch (NumberFormatException ignored) { }
            }
            if (req.param("total") != null) {
                try { total = Long.parseLong(req.param("total")); } catch (NumberFormatException ignored) { }
            } else if (req.contentLength >= 0) {
                total = offset + req.contentLength;
            }
            File target;
            try {
                target = Storage.receiveResumable(req, dir, name, overwrite, offset, total);
            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().startsWith("offset_mismatch:")) {
                    long cur = Storage.partialSize(dir, name, total);
                    res.json(409, "{\"error\":\"offset_mismatch\",\"offset\":" + cur + "}");
                    return;
                }
                throw e;
            }
            if (target == null) {
                long savedOffset = Storage.partialSize(dir, name, total);
                if (req.body.remaining() == 0 && total >= 0 && savedOffset < total) {
                    // Chunk completed; awaiting remaining chunks.
                    JSONObject o = new JSONObject();
                    put(o, "ok", true);
                    put(o, "partial", true);
                    put(o, "offset", savedOffset);
                    res.json(200, o.toString());
                    return;
                }
                res.json(400, "{\"error\":\"upload incomplete\",\"offset\":" + savedOffset + "}");
                return;
            }
            String lm = req.param("mtime");
            if (lm != null) try {
                target.setLastModified(Long.parseLong(lm));
            } catch (NumberFormatException ignored) { }
            AuditLog.log(ctx, req, "upload", u.name, target.getPath());
            JSONObject entry = Storage.entry(target);
            put(entry, "offset", target.length());
            res.json(200, entry.toString());
            return;
        }
        File target = Storage.receive(req, dir, name, overwrite);
        if (target == null) {
            res.error(400, "upload incomplete");
            return;
        }
        String lm = req.param("mtime");
        if (lm != null) try {
            target.setLastModified(Long.parseLong(lm));
        } catch (NumberFormatException ignored) { }
        AuditLog.log(ctx, req, "upload", u.name, target.getPath());
        res.json(200, Storage.entry(target).toString());
    }

    private void mkdir(HttpServer.Request req, HttpServer.Response res, Users.User u) throws IOException {
        File dir = writableDir(u, req.param("path"));
        String name = Storage.cleanName(req.param("name"));
        if (name == null) {
            res.error(400, "bad name");
            return;
        }
        File nd = new File(dir, name);
        if (nd.exists()) {
            res.error(409, "exists");
            return;
        }
        if (!nd.mkdirs()) {
            res.error(500, "cannot create");
            return;
        }
        AuditLog.log(ctx, req, "mkdir", u.name, nd.getPath());
        res.json(200, Storage.entry(nd).toString());
    }

    private void rename(HttpServer.Request req, HttpServer.Response res, Users.User u) throws IOException {
        File f = Storage.resolve(ctx, u, req.param("path"));
        String name = Storage.cleanName(req.param("name"));
        if (name == null || Storage.isRoot(ctx, f) || !u.allows(f.getParentFile())) {
            res.error(400, "bad name");
            return;
        }
        File nf = new File(f.getParentFile(), name);
        if (nf.exists()) {
            res.error(409, "exists");
            return;
        }
        if (!f.renameTo(nf)) {
            res.error(500, "cannot rename");
            return;
        }
        AuditLog.log(ctx, req, "rename", u.name, f.getName() + " -> " + nf.getName());
        res.json(200, Storage.entry(nf).toString());
    }

    /** Deletes to the trash by default; ?permanent=1 skips it. */
    private void delete(HttpServer.Request req, HttpServer.Response res, Users.User u) throws IOException {
        boolean permanent = "1".equals(req.param("permanent"));
        int n = 0;
        for (String p : req.params("p")) {
            File f = Storage.resolve(ctx, u, p);
            if (Storage.isRoot(ctx, f) || !u.allows(f.getParentFile())) continue;
            if (Trash.delete(ctx, f, u.name, permanent)) {
                n++;
                AuditLog.log(ctx, req, "delete", u.name, f.getPath());
            }
        }
        JSONObject o = new JSONObject();
        put(o, "deleted", n);
        put(o, "trash", !permanent && Settings.trashEnabled(ctx));
        res.json(200, o.toString());
    }

    private void copyMove(HttpServer.Request req, HttpServer.Response res, Users.User u, boolean move) throws IOException {
        File dst = writableDir(u, req.param("dst"));
        if (!dst.isDirectory()) {
            res.error(400, "bad destination");
            return;
        }
        int n = 0;
        for (String p : req.params("p")) {
            File src = Storage.resolve(ctx, u, p);
            if (Storage.isRoot(ctx, src)) continue;
            if (move && !u.allows(src.getParentFile())) continue;
            if (dst.getPath().equals(src.getPath())
                    || dst.getPath().startsWith(src.getPath() + File.separator)
                    || dst.getPath().startsWith(src.getPath() + "/")) continue;
            if (!move && Storage.exceedsQuota(ctx, u, Storage.sizeRec(src, 0))) {
                res.error(507, "quota_exceeded");
                return;
            }
            File target = Storage.uniqueFile(dst, src.getName());
            boolean ok = move ? Storage.move(src, target) : Storage.copyRec(src, target);
            if (ok) {
                n++;
                AuditLog.log(ctx, req, move ? "move" : "copy", u.name, src.getName() + " -> " + target.getPath());
            }
        }
        res.json(200, "{\"done\":" + n + "}");
    }

    /**
     * Backup helper: POST {path, items:[{rel, size, mtime}]} returns which items are missing or
     * different on the phone, so the browser only uploads what changed.
     */
    private void exists(HttpServer.Request req, HttpServer.Response res, Users.User u) throws IOException {
        JSONObject b;
        try {
            b = new JSONObject(req.bodyString(8 << 20));
        } catch (Exception e) {
            res.error(400, "bad request");
            return;
        }
        File base = writableDir(u, b.optString("path"));
        JSONArray items = b.optJSONArray("items");
        JSONArray missing = new JSONArray();
        long missingBytes = 0;
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            String rel = it.optString("rel");
            File f = new File(base, rel);
            boolean same;
            try {
                File cf = Storage.resolve(ctx, u, f.getPath());
                same = cf.isFile() && cf.length() == it.optLong("size", -1);
            } catch (SecurityException e) {
                continue; // skip anything that would escape the backup folder
            }
            if (!same) {
                missing.put(rel);
                missingBytes += Math.max(0, it.optLong("size"));
            }
        }
        JSONObject o = new JSONObject();
        put(o, "missing", missing);
        put(o, "missingBytes", missingBytes);
        put(o, "checked", items == null ? 0 : items.length());
        res.json(200, o.toString());
    }

    // ---------------------------------------------------------------- trash

    private void trashList(HttpServer.Response res, Users.User u) throws IOException {
        JSONObject o = new JSONObject();
        put(o, "items", Trash.list(ctx, u));
        put(o, "enabled", Settings.trashEnabled(ctx));
        put(o, "days", Settings.trashDays(ctx));
        res.json(200, o.toString());
    }

    private void trashRestore(HttpServer.Request req, HttpServer.Response res, Users.User u) throws IOException {
        JSONArray restored = new JSONArray();
        for (String id : req.params("id")) {
            String p = Trash.restore(ctx, u, id);
            if (p != null) {
                restored.put(p);
                AuditLog.log(ctx, req, "restore", u.name, p);
            }
        }
        res.json(200, wrap("restored", restored));
    }

    private void trashPurge(HttpServer.Request req, HttpServer.Response res, Users.User u) throws IOException {
        int n = 0;
        for (String id : req.params("id")) {
            if (Trash.purge(ctx, u, id)) {
                n++;
                AuditLog.log(ctx, req, "purge", u.name, id);
            }
        }
        res.json(200, "{\"purged\":" + n + "}");
    }

    // ---------------------------------------------------------------- share links

    /** POST {path, hours (0 = never), password?, mode? ("share"|"request"), label?} */
    private void shareCreate(HttpServer.Request req, HttpServer.Response res, Users.User u) throws IOException {
        JSONObject b;
        try {
            b = new JSONObject(req.bodyString(1 << 16));
        } catch (Exception e) {
            res.error(400, "bad request");
            return;
        }
        File f = Storage.resolve(ctx, u, b.optString("path"));
        if (!f.exists()) {
            res.error(404, "not found");
            return;
        }
        String mode = "request".equalsIgnoreCase(b.optString("mode", "share")) ? "request" : "share";
        if ("request".equals(mode) && !f.isDirectory()) {
            res.error(400, "file request target must be a folder");
            return;
        }
        long hours = b.optLong("hours", 0);
        long expires = hours > 0 ? System.currentTimeMillis() + Math.min(hours, 24L * 365) * 3600 * 1000 : 0;
        String pw = b.optString("password", "");
        if (!pw.isEmpty() && pw.length() < 4) {
            res.error(400, "password too short");
            return;
        }
        String label = b.optString("label", "");
        JSONObject created = Shares.get(ctx).create(f, u.name, expires, pw, mode, label);
        AuditLog.log(ctx, req, "share_create", u.name, mode + ":" + f.getPath() + " (" + created.optString("id") + ")");
        res.json(200, created.toString());
    }
}
