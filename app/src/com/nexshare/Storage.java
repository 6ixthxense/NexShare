package com.nexshare;

import android.content.Context;
import android.os.Environment;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Storage roots, safe path resolution and file helpers shared by every API. */
public final class Storage {

    private Storage() { }

    /** Folder (inside each storage root) that holds deleted items. */
    public static final String TRASH_DIR = ".nexshare-trash";

    public static List<File> rootDirs(Context ctx) {
        List<File> roots = new ArrayList<>();
        roots.add(Environment.getExternalStorageDirectory());
        File[] ext = ctx.getExternalFilesDirs(null);
        for (int i = 1; ext != null && i < ext.length; i++) {
            if (ext[i] == null) continue;
            String path = ext[i].getAbsolutePath();
            int idx = path.indexOf("/Android/data");
            if (idx > 0) roots.add(new File(path.substring(0, idx)));
        }
        return roots;
    }

    /** Short URL-safe id of a root: "internal", "sd1", "sd2"… (used by WebDAV). */
    public static String rootId(int index) {
        return index == 0 ? "internal" : "sd" + index;
    }

    public static String rootName(int index) {
        return index == 0 ? "Internal storage" : "SD card " + index;
    }

    public static List<String> canonicalRoots(Context ctx) {
        List<String> out = new ArrayList<>();
        for (File r : rootDirs(ctx)) {
            try {
                out.add(r.getCanonicalPath());
            } catch (IOException e) {
                out.add(r.getAbsolutePath());
            }
        }
        return out;
    }

    /**
     * Resolves a client-supplied path to a canonical file inside a storage root.
     * Pure function of its inputs so it can be unit-tested.
     */
    public static File within(String path, List<String> roots) throws IOException {
        if (path == null || path.isEmpty() || path.indexOf('\0') >= 0) throw new SecurityException("no path");
        File f = new File(path).getCanonicalFile();
        String fp = f.getPath();
        for (String rp : roots) {
            if (fp.equals(rp) || fp.startsWith(rp + "/")) return f;
        }
        throw new SecurityException("outside storage");
    }

    /** Resolves a path for a user: inside storage, inside their folders, and never into the trash. */
    public static File resolve(Context ctx, Users.User user, String path) throws IOException {
        File f = within(path, canonicalRoots(ctx));
        if (inTrash(f)) throw new SecurityException("trash is managed by /api/trash");
        if (user != null && !user.allows(f)) throw new SecurityException("not allowed for this user");
        return f;
    }

    /** Like resolve, but also accepts parent folders a restricted user must pass through. */
    public static File resolveBrowse(Context ctx, Users.User user, String path) throws IOException {
        File f = within(path, canonicalRoots(ctx));
        if (inTrash(f)) throw new SecurityException("trash is managed by /api/trash");
        if (user != null && !user.allows(f) && !user.canTraverse(f)) throw new SecurityException("not allowed for this user");
        return f;
    }

    public static boolean inTrash(File canonical) {
        String p = canonical.getPath();
        return p.contains("/" + TRASH_DIR + "/") || p.endsWith("/" + TRASH_DIR);
    }

    public static boolean isRoot(Context ctx, File f) {
        for (String r : canonicalRoots(ctx)) if (r.equals(f.getPath())) return true;
        return false;
    }

    /** The storage root containing this canonical file, or null. */
    public static File rootOf(Context ctx, File f) {
        String fp = f.getPath();
        for (String r : canonicalRoots(ctx)) if (fp.equals(r) || fp.startsWith(r + "/")) return new File(r);
        return null;
    }

    // ---------------------------------------------------------------- helpers

    public static JSONObject entry(File f) {
        JSONObject o = new JSONObject();
        put(o, "name", f.getName());
        put(o, "path", f.getAbsolutePath());
        put(o, "dir", f.isDirectory());
        put(o, "size", f.isDirectory() ? 0 : f.length());
        put(o, "mtime", f.lastModified());
        put(o, "hidden", f.getName().startsWith("."));
        return o;
    }

    public static String cleanName(String n) {
        if (n == null) return null;
        n = n.replace("/", "_").replace("\\", "_").replace("\0", "").trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) return null;
        if (n.length() > 255) n = n.substring(0, 255);
        return n;
    }

    public static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
    }

    public static boolean deleteRec(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRec(k);
        }
        return f.delete();
    }

    public static boolean copyRec(File src, File dst) {
        if (src.isDirectory()) {
            if (!dst.mkdirs()) return false;
            File[] kids = src.listFiles();
            boolean ok = true;
            if (kids != null) for (File k : kids) ok &= copyRec(k, new File(dst, k.getName()));
            return ok;
        }
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            dst.setLastModified(src.lastModified());
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Moves by rename when possible, otherwise copy + delete (across storage volumes). */
    public static boolean move(File src, File dst) {
        if (src.renameTo(dst)) return true;
        if (copyRec(src, dst)) {
            deleteRec(src);
            return true;
        }
        deleteRec(dst);
        return false;
    }

    public static long sizeRec(File f, int depth) {
        if (!f.isDirectory()) return f.length();
        if (depth > 32) return 0;
        long n = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) n += sizeRec(k, depth + 1);
        return n;
    }

    /**
     * Streams a file with HTTP Range support (video seeking, resumable downloads).
     * downloadName forces "attachment" when non-null.
     */
    public static void sendFile(HttpServer.Request req, HttpServer.Response res, File f, boolean attachment) throws IOException {
        if (!f.isFile()) {
            res.error(404, "not found");
            return;
        }
        long len = f.length();
        long start = 0, end = len - 1;
        boolean partial = false;
        String etag = "\"" + Long.toHexString(f.lastModified()) + "-" + Long.toHexString(len) + "\"";
        String range = req.header("range");
        String ifRange = req.header("if-range");
        if (ifRange != null && !ifRange.equals(etag) && !ifRange.equals(HttpServer.httpDate(f.lastModified()))) range = null;
        if (range != null && range.startsWith("bytes=") && !range.contains(",")) {
            String[] r = range.substring(6).split("-", -1);
            try {
                if (r[0].isEmpty()) {
                    start = Math.max(0, len - Long.parseLong(r[1]));
                } else {
                    start = Long.parseLong(r[0]);
                    if (r.length > 1 && !r[1].isEmpty()) end = Math.min(Long.parseLong(r[1]), len - 1);
                }
                partial = true;
            } catch (NumberFormatException ignored) { }
            if (partial && (start >= len || start > end)) {
                res.header("Content-Range", "bytes */" + len);
                res.error(416, "bad range");
                return;
            }
        }
        String inm = req.header("if-none-match");
        if (!partial && inm != null && inm.equals(etag)) {
            res.header("ETag", etag);
            res.empty(304);
            return;
        }
        res.header("Accept-Ranges", "bytes");
        res.header("ETag", etag);
        res.header("Last-Modified", HttpServer.httpDate(f.lastModified()));
        String disp = attachment ? "attachment" : "inline";
        res.header("Content-Disposition", disp + "; filename*=UTF-8''" + urlEncode(f.getName()));
        res.header("X-Content-Type-Options", "nosniff");
        String type = mime(f.getName());
        // HTML/SVG from the phone must not run as our origin (it could call the API as the viewer).
        if (type.startsWith("text/html") || type.startsWith("image/svg") || type.startsWith("text/xml")) {
            res.header("Content-Security-Policy", "sandbox");
        }
        if (partial) res.header("Content-Range", "bytes " + start + "-" + end + "/" + len);
        long count = len == 0 ? 0 : end - start + 1;
        OutputStream out = res.begin(partial ? 206 : 200, type, Math.max(count, 0));
        if ("HEAD".equals(req.method) || count <= 0) return;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            raf.seek(start);
            byte[] buf = new byte[65536];
            long left = count;
            while (left > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                out.write(buf, 0, n);
                left -= n;
            }
        }
    }

    /**
     * Writes a request body to dir/name via a temp file. overwrite=false picks a unique name.
     * Returns the saved file, or null if the upload was cut short.
     */
    public static File receive(HttpServer.Request req, File dir, String name, boolean overwrite) throws IOException {
        File target = overwrite ? new File(dir, name) : uniqueFile(dir, name);
        File tmp = new File(dir, "." + target.getName() + "." + Crypto.randomToken(4) + ".nexpart");
        boolean ok = false;
        try (OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = req.body.read(buf)) > 0) out.write(buf, 0, n);
            ok = req.body.remaining() == 0;
        } finally {
            if (!ok) tmp.delete();
        }
        if (!ok) return null;
        if (overwrite && target.isFile()) target.delete();
        if (!tmp.renameTo(target)) {
            tmp.delete();
            throw new IOException("cannot save");
        }
        return target;
    }

    public static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    /** Encodes each path segment, keeping the slashes. */
    public static String urlEncodePath(String p) {
        StringBuilder sb = new StringBuilder();
        for (String seg : p.split("/", -1)) {
            if (sb.length() > 0 || p.startsWith("/")) sb.append('/');
            sb.append(urlEncode(seg));
        }
        String s = sb.toString();
        return s.startsWith("//") ? s.substring(1) : s;
    }

    public static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (Exception ignored) { }
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    public static String escapeHtml(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '&': sb.append("&amp;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private static final Map<String, String> MIME = new HashMap<>();

    static {
        String[] m = {
            "html", "text/html; charset=utf-8", "htm", "text/html; charset=utf-8", "css", "text/css; charset=utf-8",
            "js", "application/javascript; charset=utf-8", "mjs", "application/javascript; charset=utf-8",
            "json", "application/json", "webmanifest", "application/manifest+json", "svg", "image/svg+xml",
            "txt", "text/plain; charset=utf-8", "log", "text/plain; charset=utf-8", "md", "text/plain; charset=utf-8",
            "csv", "text/plain; charset=utf-8", "xml", "text/xml; charset=utf-8", "ico", "image/x-icon",
            "jpg", "image/jpeg", "jpeg", "image/jpeg", "png", "image/png", "gif", "image/gif", "webp", "image/webp",
            "bmp", "image/bmp", "heic", "image/heic", "heif", "image/heif", "avif", "image/avif",
            "mp4", "video/mp4", "m4v", "video/mp4", "webm", "video/webm", "mkv", "video/x-matroska", "3gp", "video/3gpp",
            "mov", "video/quicktime", "avi", "video/x-msvideo",
            "mp3", "audio/mpeg", "m4a", "audio/mp4", "aac", "audio/aac", "ogg", "audio/ogg", "opus", "audio/ogg",
            "wav", "audio/wav", "flac", "audio/flac", "amr", "audio/amr",
            "pdf", "application/pdf", "zip", "application/zip", "apk", "application/vnd.android.package-archive",
            "srt", "text/plain; charset=utf-8", "vtt", "text/vtt; charset=utf-8", "woff2", "font/woff2",
        };
        for (int i = 0; i < m.length; i += 2) MIME.put(m[i], m[i + 1]);
    }

    public static String mime(String name) {
        int dot = name.lastIndexOf('.');
        String t = dot >= 0 ? MIME.get(name.substring(dot + 1).toLowerCase(Locale.ROOT)) : null;
        return t == null ? "application/octet-stream" : t;
    }
}
