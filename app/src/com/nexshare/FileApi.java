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
            case "/api/exif": exif(req, res, u); return true;
            case "/api/events": events(req, res); return true;
            case "/api/clipboard":
            case "/api/clipboard/clear": clipboard(req, res, u); return true;
            case "/api/storage/analyze": storageAnalyze(req, res, u); return true;
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
            case "/api/write": writeFile(req, res, u); return true;
            case "/api/unzip": unzip(req, res, u); return true;
            case "/api/zip/save": zipSave(req, res, u); return true;
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
        "/api/write", "/api/unzip", "/api/zip/save",
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

    /** GET /api/exif?path=... — returns image dimensions and EXIF camera/GPS metadata. */
    private void exif(HttpServer.Request req, HttpServer.Response res, Users.User u) throws IOException {
        File f = Storage.resolve(ctx, u, req.param("path"));
        if (!f.isFile()) {
            res.error(404, "not found");
            return;
        }
        JSONObject o = Storage.entry(f);
        try {
            Class<?> optsCls = Class.forName("android.graphics.BitmapFactory$Options");
            Class<?> bfCls = Class.forName("android.graphics.BitmapFactory");
            Object opts = optsCls.getDeclaredConstructor().newInstance();
            optsCls.getField("inJustDecodeBounds").setBoolean(opts, true);
            bfCls.getMethod("decodeFile", String.class, optsCls).invoke(null, f.getPath(), opts);
            int w = optsCls.getField("outWidth").getInt(opts);
            int h = optsCls.getField("outHeight").getInt(opts);
            if (w > 0 && h > 0) {
                put(o, "width", w);
                put(o, "height", h);
            }
        } catch (Throwable ignored) { }
        try {
            Class<?> exifCls = Class.forName("android.media.ExifInterface");
            Object ex = exifCls.getConstructor(String.class).newInstance(f.getPath());
            java.lang.reflect.Method getAttr = exifCls.getMethod("getAttribute", String.class);
            String[][] tags = new String[][]{
                {"Make", "make"}, {"Model", "model"}, {"DateTimeOriginal", "dateTaken"},
                {"DateTime", "dateTime"}, {"ISOSpeedRatings", "iso"}, {"ExposureTime", "exposure"},
                {"FNumber", "aperture"}, {"FocalLength", "focalLength"}, {"Orientation", "orientation"},
                {"ImageWidth", "exifWidth"}, {"ImageLength", "exifHeight"}
            };
            for (String[] kv : tags) {
                Object val = getAttr.invoke(ex, kv[0]);
                if (val != null && !String.valueOf(val).trim().isEmpty()) {
                    put(o, kv[1], String.valueOf(val).trim());
                }
            }
            if (!o.has("width") && o.optInt("exifWidth") > 0) {
                put(o, "width", o.optInt("exifWidth"));
                put(o, "height", o.optInt("exifHeight"));
            }
            float[] latLong = new float[2];
            java.lang.reflect.Method getLatLong = exifCls.getMethod("getLatLong", float[].class);
            if (Boolean.TRUE.equals(getLatLong.invoke(ex, (Object) latLong))) {
                put(o, "lat", (double) latLong[0]);
                put(o, "lon", (double) latLong[1]);
            }
        } catch (Throwable ignored) { }
        res.json(200, o.toString());
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
            LiveEvents.publish("upload", target.getParent(), target.getName());
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
        LiveEvents.publish("upload", target.getParent(), target.getName());
        res.json(200, Storage.entry(target).toString());
    }

    /**
     * POST /api/write — creates or updates a text/markdown/code file directly from the browser editor.
     * Supports either query params (?path=...&name=...) with raw body or JSON {path, name?, content, create?}.
     */
    private void writeFile(HttpServer.Request req, HttpServer.Response res, Users.User u) throws IOException {
        if (!"POST".equals(req.method) && !"PUT".equals(req.method)) {
            res.error(400, "use POST");
            return;
        }
        String pathParam = req.param("path");
        String nameParam = req.param("name");
        boolean createOnly = "1".equals(req.param("create"));
        byte[] contentBytes;
        if (pathParam == null) {
            try {
                JSONObject b = new JSONObject(req.bodyString(8 << 20));
                pathParam = b.optString("path", null);
                nameParam = b.optString("name", null);
                createOnly = b.optBoolean("create", false);
                contentBytes = b.optString("content", "").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                res.error(400, "bad request");
                return;
            }
        } else {
            contentBytes = req.bodyBytes(8 << 20);
        }
        File base = Storage.resolve(ctx, u, pathParam);
        File target;
        if (nameParam != null && !nameParam.trim().isEmpty()) {
            if (!base.isDirectory()) {
                res.error(400, "not a folder");
                return;
            }
            String clean = Storage.cleanName(nameParam);
            if (clean == null) {
                res.error(400, "bad name");
                return;
            }
            target = new File(base, clean);
            if (createOnly && target.exists()) {
                res.error(409, "exists");
                return;
            }
        } else {
            if (base.isDirectory()) {
                res.error(400, "cannot overwrite folder");
                return;
            }
            target = base;
        }
        if (!u.allows(target) || (target.getParentFile() != null && !u.allows(target.getParentFile()))) {
            res.error(403, "not allowed");
            return;
        }
        long existingLen = target.isFile() ? target.length() : 0L;
        long delta = Math.max(0L, contentBytes.length - existingLen);
        if (Storage.exceedsQuota(ctx, u, delta)) {
            res.error(507, "quota_exceeded");
            return;
        }
        try (java.io.FileOutputStream fout = new java.io.FileOutputStream(target)) {
            fout.write(contentBytes);
        }
        AuditLog.log(ctx, req, "write", u.name, target.getPath());
        LiveEvents.publish("write", target.getParent(), target.getName());
        res.json(200, Storage.entry(target).toString());
    }

    /** POST /api/unzip?path=<zipPath>&dst=<optionalDestDir> — extracts a .zip file in-place on the phone. */
    private void unzip(HttpServer.Request req, HttpServer.Response res, Users.User u) throws IOException {
        String zipPath = req.param("path");
        String dstPath = req.param("dst");
        if (zipPath == null) {
            try {
                JSONObject b = new JSONObject(req.bodyString(65536));
                zipPath = b.optString("path", null);
                dstPath = b.optString("dst", null);
            } catch (Exception ignored) { }
        }
        File zipFile = Storage.resolve(ctx, u, zipPath);
        if (!zipFile.isFile()) {
            res.error(404, "zip file not found");
            return;
        }
        File destDir;
        if (dstPath != null && !dstPath.trim().isEmpty()) {
            destDir = writableDir(u, dstPath);
        } else {
            String baseName = zipFile.getName().replaceAll("(?i)\\.zip$", "");
            if (baseName.isEmpty()) baseName = "extracted";
            destDir = Storage.uniqueFile(zipFile.getParentFile(), baseName);
        }
        if (!u.allows(destDir)) {
            res.error(403, "not allowed");
            return;
        }
        long maxExtractBytes = 2L * 1024 * 1024 * 1024; // 2 GB safety cap
        if (u.quotaBytes > 0) {
            long rem = u.quotaBytes - Storage.userUsedBytes(ctx, u);
            if (rem <= 0) {
                res.error(507, "quota_exceeded");
                return;
            }
            maxExtractBytes = Math.min(maxExtractBytes, rem);
        }
        int count;
        try {
            count = Zip.extract(zipFile, destDir, maxExtractBytes);
        } catch (IOException e) {
            if ("quota_exceeded".equals(e.getMessage())) {
                res.error(507, "quota_exceeded");
                return;
            }
            throw e;
        }
        AuditLog.log(ctx, req, "unzip", u.name, zipFile.getName() + " -> " + destDir.getPath() + " (" + count + ")");
        LiveEvents.publish("unzip", destDir.getParent(), destDir.getName());
        JSONObject out = new JSONObject();
        put(out, "extracted", count);
        put(out, "path", destDir.getPath());
        put(out, "name", destDir.getName());
        res.json(200, out.toString());
    }

    /** POST /api/zip/save — compresses selected files/folders into a .zip archive stored in the destination folder. */
    private void zipSave(HttpServer.Request req, HttpServer.Response res, Users.User u) throws IOException {
        List<String> paths = new ArrayList<>(req.params("p"));
        String dstParam = req.param("dst");
        String nameParam = req.param("name");
        if (paths.isEmpty()) {
            try {
                JSONObject b = new JSONObject(req.bodyString(1 << 16));
                dstParam = b.optString("dst", dstParam);
                nameParam = b.optString("name", nameParam);
                JSONArray arr = b.optJSONArray("p");
                for (int i = 0; arr != null && i < arr.length(); i++) paths.add(arr.optString(i));
            } catch (Exception ignored) { }
        }
        List<File> items = new ArrayList<>();
        long estBytes = 0;
        for (String p : paths) {
            File f = Storage.resolve(ctx, u, p);
            if (f.exists()) {
                items.add(f);
                estBytes += Storage.sizeRec(f, 0);
            }
        }
        if (items.isEmpty()) {
            res.error(400, "nothing selected");
            return;
        }
        File dstDir = dstParam != null && !dstParam.isEmpty() ? writableDir(u, dstParam) : items.get(0).getParentFile();
        if (dstDir == null || !dstDir.isDirectory() || !u.allows(dstDir)) {
            res.error(400, "bad destination");
            return;
        }
        if (Storage.exceedsQuota(ctx, u, Math.max(4096L, estBytes / 2))) {
            res.error(507, "quota_exceeded");
            return;
        }
        String baseName = Storage.cleanName(nameParam);
        if (baseName == null || baseName.isEmpty()) {
            baseName = (items.size() == 1 ? items.get(0).getName() : "Archive") + ".zip";
        } else if (!baseName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            baseName = baseName + ".zip";
        }
        File targetZip = Storage.uniqueFile(dstDir, baseName);
        Zip.compressToFile(items, targetZip);
        AuditLog.log(ctx, req, "zip_save", u.name, targetZip.getPath());
        LiveEvents.publish("zip_save", dstDir.getPath(), targetZip.getName());
        res.json(200, Storage.entry(targetZip).toString());
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
        LiveEvents.publish("mkdir", dir.getPath(), nd.getName());
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
        LiveEvents.publish("rename", nf.getParent(), nf.getName());
        res.json(200, Storage.entry(nf).toString());
    }

    /** Deletes to the trash by default; ?permanent=1 skips it. */
    private void delete(HttpServer.Request req, HttpServer.Response res, Users.User u) throws IOException {
        boolean permanent = "1".equals(req.param("permanent"));
        int n = 0;
        String lastParent = "";
        for (String p : req.params("p")) {
            File f = Storage.resolve(ctx, u, p);
            if (Storage.isRoot(ctx, f) || !u.allows(f.getParentFile())) continue;
            if (Trash.delete(ctx, f, u.name, permanent)) {
                n++;
                lastParent = f.getParent();
                AuditLog.log(ctx, req, "delete", u.name, f.getPath());
            }
        }
        if (n > 0) LiveEvents.publish("delete", lastParent, String.valueOf(n));
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
        if (n > 0) LiveEvents.publish(move ? "move" : "copy", dst.getPath(), String.valueOf(n));
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
        int maxHits = Math.max(0, b.optInt("maxHits", 0));
        long maxSizeMb = Math.max(0L, b.optLong("maxSizeMb", 0L));
        long maxBytes = maxSizeMb > 0 ? maxSizeMb * 1024L * 1024L : b.optLong("maxBytes", 0L);
        JSONObject created = Shares.get(ctx).create(f, u.name, expires, pw, mode, label, maxHits, maxBytes);
        AuditLog.log(ctx, req, "share_create", u.name, mode + ":" + f.getPath() + " (" + created.optString("id") + ")");
        res.json(200, created.toString());
    }

    // ---------------------------------------------------------------- live events & clipboard

    private void events(HttpServer.Request req, HttpServer.Response res) throws IOException {
        long since = 0;
        if (req.param("since") != null) {
            try { since = Long.parseLong(req.param("since")); } catch (NumberFormatException ignored) { }
        }
        JSONObject out = new JSONObject();
        put(out, "seq", LiveEvents.latestSeq());
        put(out, "events", LiveEvents.since(since));
        res.json(200, out.toString());
    }

    private void clipboard(HttpServer.Request req, HttpServer.Response res, Users.User u) throws IOException {
        android.content.SharedPreferences prefs = ctx.getSharedPreferences("nexshare_clip", Context.MODE_PRIVATE);
        if (req.path.equals("/api/clipboard/clear") && "POST".equals(req.method)) {
            if (!u.canWrite()) {
                res.error(403, "read_only");
                return;
            }
            prefs.edit().remove("items").remove("current").apply();
            LiveEvents.publish("clipboard", "", "");
            res.json(200, "{\"ok\":true,\"current\":\"\",\"items\":[]}");
            return;
        }
        if ("POST".equals(req.method)) {
            if (!u.canWrite()) {
                res.error(403, "read_only");
                return;
            }
            JSONObject b;
            try {
                b = new JSONObject(req.bodyString(1 << 20));
            } catch (Exception e) {
                res.error(400, "bad request");
                return;
            }
            String text = b.optString("text", "").trim();
            if (text.isEmpty()) {
                res.error(400, "empty text");
                return;
            }
            if (text.length() > 65536) text = text.substring(0, 65536);
            JSONArray next = new JSONArray();
            JSONObject item = new JSONObject();
            put(item, "text", text);
            put(item, "ts", System.currentTimeMillis());
            put(item, "by", u.name);
            next.put(item);
            try {
                JSONArray old = new JSONArray(prefs.getString("items", "[]"));
                for (int i = 0; i < old.length() && next.length() < 20; i++) {
                    JSONObject prev = old.optJSONObject(i);
                    if (prev != null && !text.equals(prev.optString("text"))) next.put(prev);
                }
            } catch (Exception ignored) { }
            prefs.edit().putString("current", text).putString("items", next.toString()).apply();
            copyToSystemClipboard(text);
            AuditLog.log(ctx, req, "clipboard", u.name, text.length() > 60 ? text.substring(0, 60) + "…" : text);
            LiveEvents.publish("clipboard", "", text.length() > 80 ? text.substring(0, 80) + "…" : text);
        }
        String current = prefs.getString("current", "");
        JSONArray items;
        try {
            items = new JSONArray(prefs.getString("items", "[]"));
        } catch (Exception e) {
            items = new JSONArray();
        }
        JSONObject out = new JSONObject();
        put(out, "current", current);
        put(out, "items", items);
        res.json(200, out.toString());
    }

    private void copyToSystemClipboard(final String text) {
        try {
            Class<?> cmCls = Class.forName("android.content.ClipboardManager");
            Class<?> cdCls = Class.forName("android.content.ClipData");
            Object cm = Context.class.getMethod("getSystemService", String.class).invoke(ctx, "clipboard");
            if (cm != null) {
                Object clip = cdCls.getMethod("newPlainText", CharSequence.class, CharSequence.class).invoke(null, "NexShare", text);
                cmCls.getMethod("setPrimaryClip", cdCls).invoke(cm, clip);
            }
        } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------- storage analyzer

    private void storageAnalyze(HttpServer.Request req, HttpServer.Response res, Users.User u) throws IOException {
        File root;
        String pathParam = req.param("path");
        if (pathParam != null && !pathParam.isEmpty()) {
            root = Storage.resolveBrowse(ctx, u, pathParam);
        } else if (!u.folders.isEmpty()) {
            root = new File(u.folders.get(0)).getCanonicalFile();
        } else {
            root = Environment.getExternalStorageDirectory().getCanonicalFile();
        }
        long[] catBytes = new long[7]; // 0:video, 1:image, 2:audio, 3:archive, 4:doc, 5:apk, 6:other
        int[] catCounts = new int[7];
        List<File> largest = new ArrayList<>();
        File[] topKids = root.listFiles();
        JSONArray foldersArr = new JSONArray();
        List<JSONObject> folderObjs = new ArrayList<>();
        if (topKids != null) {
            for (File k : topKids) {
                if (k.getName().equals(Storage.TRASH_DIR) || k.getName().endsWith(".nexpart")) continue;
                if (!u.folders.isEmpty() && !u.allows(k) && !u.canTraverse(k)) continue;
                if (k.isDirectory()) {
                    long fSize = scanAnalyzer(k, 0, u, catBytes, catCounts, largest);
                    JSONObject fo = new JSONObject();
                    put(fo, "name", k.getName());
                    put(fo, "path", k.getAbsolutePath());
                    put(fo, "size", fSize);
                    folderObjs.add(fo);
                } else if (u.allows(k)) {
                    tallyFile(k, catBytes, catCounts, largest);
                }
            }
        }
        Collections.sort(folderObjs, new Comparator<JSONObject>() {
            public int compare(JSONObject a, JSONObject b) {
                return Long.compare(b.optLong("size"), a.optLong("size"));
            }
        });
        for (int i = 0; i < folderObjs.size() && i < 15; i++) foldersArr.put(folderObjs.get(i));

        String[] keys = {"video", "image", "audio", "archive", "doc", "apk", "other"};
        JSONObject cats = new JSONObject();
        long scannedBytes = 0;
        int scannedFiles = 0;
        for (int i = 0; i < keys.length; i++) {
            JSONObject co = new JSONObject();
            put(co, "bytes", catBytes[i]);
            put(co, "count", catCounts[i]);
            put(cats, keys[i], co);
            scannedBytes += catBytes[i];
            scannedFiles += catCounts[i];
        }
        JSONArray largestArr = new JSONArray();
        for (File lf : largest) largestArr.put(Storage.entry(lf));

        JSONObject out = new JSONObject();
        put(out, "path", root.getPath());
        put(out, "scannedBytes", scannedBytes);
        put(out, "scannedFiles", scannedFiles);
        try {
            StatFs st = new StatFs(root.getPath());
            put(out, "diskTotal", st.getTotalBytes());
            put(out, "diskFree", st.getAvailableBytes());
        } catch (Exception ignored) { }
        put(out, "categories", cats);
        put(out, "folders", foldersArr);
        put(out, "largest", largestArr);
        res.json(200, out.toString());
    }

    private long scanAnalyzer(File dir, int depth, Users.User u, long[] catBytes, int[] catCounts, List<File> largest) {
        if (depth > 8 || dir.getName().equals(Storage.TRASH_DIR)) return 0;
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        long sum = 0;
        for (File k : kids) {
            if (k.getName().equals(Storage.TRASH_DIR) || k.getName().endsWith(".nexpart")) continue;
            if (!u.folders.isEmpty() && !u.allows(k) && !u.canTraverse(k)) continue;
            if (k.isDirectory()) {
                sum += scanAnalyzer(k, depth + 1, u, catBytes, catCounts, largest);
            } else if (u.allows(k)) {
                sum += k.length();
                tallyFile(k, catBytes, catCounts, largest);
            }
        }
        return sum;
    }

    private void tallyFile(File f, long[] catBytes, int[] catCounts, List<File> largest) {
        long len = f.length();
        String name = f.getName().toLowerCase(Locale.ROOT);
        String mime = Storage.mime(name);
        int idx = 6;
        if (mime.startsWith("video/")) idx = 0;
        else if (mime.startsWith("image/")) idx = 1;
        else if (mime.startsWith("audio/")) idx = 2;
        else if (name.endsWith(".zip") || name.endsWith(".rar") || name.endsWith(".7z") || name.endsWith(".tar") || name.endsWith(".gz")) idx = 3;
        else if (name.endsWith(".apk") || name.endsWith(".xapk") || name.endsWith(".apks")) idx = 5;
        else if (mime.startsWith("text/") || name.endsWith(".pdf") || name.endsWith(".doc") || name.endsWith(".docx")
                || name.endsWith(".xls") || name.endsWith(".xlsx") || name.endsWith(".ppt") || name.endsWith(".pptx")
                || name.endsWith(".json") || name.endsWith(".xml")) idx = 4;
        catBytes[idx] += len;
        catCounts[idx]++;
        if (largest.size() < 20 || len > largest.get(largest.size() - 1).length()) {
            largest.add(f);
            Collections.sort(largest, new Comparator<File>() {
                public int compare(File a, File b) {
                    return Long.compare(b.length(), a.length());
                }
            });
            if (largest.size() > 20) largest.remove(largest.size() - 1);
        }
    }
}
