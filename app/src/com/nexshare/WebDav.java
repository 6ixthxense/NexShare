package com.nexshare;

import android.content.Context;
import android.os.StatFs;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * WebDAV server at /dav/ so the phone can be mounted as a network drive
 * (Windows "Map network drive", macOS Finder "Connect to Server", Linux file managers,
 * and apps like Infuse, VLC or Documents).
 *
 * Layout: /dav/ lists the storage roots ("internal", "sd1"…); /dav/internal/DCIM/… maps to
 * files. Locks are emulated (always granted) because Windows and macOS refuse to write
 * to servers that don't advertise class 2.
 */
public final class WebDav {

    public static final String PREFIX = "/dav";

    private final Context ctx;
    private final Auth auth;

    public WebDav(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.auth = Auth.get(ctx);
    }

    public static boolean matches(String path) {
        return path.equals(PREFIX) || path.startsWith(PREFIX + "/");
    }

    public void handle(HttpServer.Request req, HttpServer.Response res) throws IOException {
        if (!Settings.webdavEnabled(ctx) || (req.viaTunnel() && !Settings.webdavViaTunnel(ctx))) {
            res.error(404, "WebDAV is turned off");
            return;
        }
        res.header("DAV", "1, 2");
        res.header("MS-Author-Via", "DAV");
        String m = req.method;
        if (m.equals("OPTIONS")) {
            res.header("Allow", "OPTIONS, GET, HEAD, PUT, DELETE, PROPFIND, PROPPATCH, MKCOL, COPY, MOVE, LOCK, UNLOCK");
            res.empty(200);
            return;
        }
        Users.User user = null;
        Auth.Who who = auth.who(req);
        if (who != null && who.session != null) user = who.user;      // browser already logged in
        if (user == null) user = auth.basic(req);
        if (user == null && who != null && who.session == null && req.header("authorization") == null) user = who.user; // login off on LAN
        if (user == null) {
            if (auth.locked(req)) {
                res.header("Retry-After", String.valueOf(auth.lockRemaining(req)));
                res.error(429, "locked");
                return;
            }
            res.header("WWW-Authenticate", "Basic realm=\"NexShare\", charset=\"UTF-8\"");
            res.error(401, "unauthorized");
            return;
        }

        Target t;
        try {
            t = target(req.path, user);
        } catch (SecurityException e) {
            res.error(403, "forbidden");
            return;
        }
        if (t == null) {
            res.error(404, "not found");
            return;
        }
        boolean write = !(m.equals("GET") || m.equals("HEAD") || m.equals("PROPFIND"));
        if (write && (t.file == null || !user.canWrite())) {
            res.error(403, "read only");
            return;
        }
        switch (m) {
            case "PROPFIND": propfind(req, res, t, user); break;
            case "GET":
            case "HEAD": get(req, res, t, user); break;
            case "PUT": put(req, res, t, user); break;
            case "MKCOL": mkcol(req, res, t); break;
            case "DELETE": delete(req, res, t, user); break;
            case "COPY": copyMove(req, res, t, user, false); break;
            case "MOVE": copyMove(req, res, t, user, true); break;
            case "LOCK": lock(req, res, t); break;
            case "UNLOCK": res.empty(204); break;
            case "PROPPATCH": proppatch(req, res, t); break;
            default:
                res.header("Allow", "OPTIONS, GET, HEAD, PUT, DELETE, PROPFIND, PROPPATCH, MKCOL, COPY, MOVE, LOCK, UNLOCK");
                res.error(405, "method not allowed");
        }
    }

    // ---------------------------------------------------------------- path mapping

    /** A DAV resource: the virtual top level (file == null) or a real file. */
    private static final class Target {
        final File file;       // canonical, or null for /dav/
        final String href;     // URL path of this resource
        final File root;       // storage root it lives in

        Target(File file, String href, File root) {
            this.file = file;
            this.href = href;
            this.root = root;
        }
    }

    /** Maps /dav/<rootId>/rest to a file, enforcing storage and per-user limits. */
    private Target target(String path, Users.User user) throws IOException {
        String rest = path.length() <= PREFIX.length() ? "" : path.substring(PREFIX.length());
        while (rest.startsWith("/")) rest = rest.substring(1);
        if (rest.endsWith("/")) rest = rest.substring(0, rest.length() - 1);
        if (rest.isEmpty()) return new Target(null, PREFIX + "/", null);
        int slash = rest.indexOf('/');
        String id = slash < 0 ? rest : rest.substring(0, slash);
        String sub = slash < 0 ? "" : rest.substring(slash + 1);
        List<File> roots = Storage.rootDirs(ctx);
        for (int i = 0; i < roots.size(); i++) {
            if (!Storage.rootId(i).equals(id)) continue;
            File root = roots.get(i).getCanonicalFile();
            File f = sub.isEmpty() ? root : Storage.within(new File(root, sub).getPath(), Storage.canonicalRoots(ctx));
            if (!f.getPath().equals(root.getPath())
                    && !f.getPath().startsWith(root.getPath() + File.separator)
                    && !f.getPath().startsWith(root.getPath() + "/")) throw new SecurityException("escape");
            if (Storage.inTrash(f)) throw new SecurityException("trash");
            if (!user.allows(f) && !user.canTraverse(f)) throw new SecurityException("not allowed");
            return new Target(f, PREFIX + "/" + id + (sub.isEmpty() ? "" : "/" + sub), root);
        }
        return null;
    }

    private Target child(Target parent, File f) {
        return new Target(f, parent.href.replaceAll("/$", "") + "/" + f.getName(), parent.root);
    }

    // ---------------------------------------------------------------- PROPFIND

    private void propfind(HttpServer.Request req, HttpServer.Response res, Target t, Users.User user) throws IOException {
        req.bodyBytes(1 << 16); // we always answer with all properties
        String depth = req.header("depth");
        boolean children = depth == null || !depth.trim().equals("0");
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:multistatus xmlns:D=\"DAV:\">\n");
        if (t.file == null) {
            collectionResponse(sb, PREFIX + "/", "NexShare", System.currentTimeMillis(), null);
            if (children) {
                List<File> roots = Storage.rootDirs(ctx);
                for (int i = 0; i < roots.size(); i++) {
                    File r = roots.get(i).getCanonicalFile();
                    if (!user.allows(r) && !user.canTraverse(r)) continue;
                    collectionResponse(sb, PREFIX + "/" + Storage.rootId(i) + "/", Storage.rootName(i), r.lastModified(), r);
                }
            }
        } else {
            if (!t.file.exists()) {
                res.error(404, "not found");
                return;
            }
            fileResponse(sb, t, t.file.equals(t.root));
            if (children && t.file.isDirectory()) {
                File[] kids = t.file.listFiles();
                if (kids != null) for (File k : kids) {
                    if (k.getName().equals(Storage.TRASH_DIR) || k.getName().endsWith(".nexpart")) continue;
                    File ck = k.getCanonicalFile();
                    if (!user.allows(ck) && !user.canTraverse(ck)) continue;
                    if (!ck.getPath().startsWith(t.root.getPath())) continue; // symlink out of storage
                    fileResponse(sb, child(t, k), false);
                }
            }
        }
        sb.append("</D:multistatus>\n");
        res.text(207, "application/xml; charset=utf-8", sb.toString());
    }

    private void fileResponse(StringBuilder sb, Target t, boolean isRoot) {
        File f = t.file;
        if (f.isDirectory()) {
            collectionResponse(sb, t.href + "/", f.getName(), f.lastModified(), isRoot ? f : null);
            return;
        }
        sb.append("<D:response><D:href>").append(xml(Storage.urlEncodePath(t.href))).append("</D:href><D:propstat><D:prop>");
        sb.append("<D:displayname>").append(xml(f.getName())).append("</D:displayname>");
        sb.append("<D:resourcetype/>");
        sb.append("<D:getcontentlength>").append(f.length()).append("</D:getcontentlength>");
        sb.append("<D:getcontenttype>").append(xml(Storage.mime(f.getName()).replace("; charset=utf-8", ""))).append("</D:getcontenttype>");
        sb.append("<D:getetag>\"").append(Long.toHexString(f.lastModified())).append('-').append(Long.toHexString(f.length())).append("\"</D:getetag>");
        times(sb, f.lastModified());
        sb.append(SUPPORTED_LOCK);
        sb.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>\n");
    }

    private void collectionResponse(StringBuilder sb, String href, String name, long mtime, File quotaRoot) {
        sb.append("<D:response><D:href>").append(xml(Storage.urlEncodePath(href))).append("</D:href><D:propstat><D:prop>");
        sb.append("<D:displayname>").append(xml(name)).append("</D:displayname>");
        sb.append("<D:resourcetype><D:collection/></D:resourcetype>");
        times(sb, mtime);
        if (quotaRoot != null) {
            try {
                StatFs st = new StatFs(quotaRoot.getPath());
                sb.append("<D:quota-available-bytes>").append(st.getAvailableBytes()).append("</D:quota-available-bytes>");
                sb.append("<D:quota-used-bytes>").append(st.getTotalBytes() - st.getAvailableBytes()).append("</D:quota-used-bytes>");
            } catch (Exception ignored) { }
        }
        sb.append(SUPPORTED_LOCK);
        sb.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>\n");
    }

    private static void times(StringBuilder sb, long mtime) {
        sb.append("<D:getlastmodified>").append(HttpServer.httpDate(mtime)).append("</D:getlastmodified>");
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        sb.append("<D:creationdate>").append(iso.format(new Date(mtime))).append("</D:creationdate>");
    }

    private static final String SUPPORTED_LOCK = "<D:supportedlock><D:lockentry><D:lockscope><D:exclusive/></D:lockscope>"
        + "<D:locktype><D:write/></D:locktype></D:lockentry></D:supportedlock>";

    // ---------------------------------------------------------------- GET / PUT / MKCOL / DELETE

    private void get(HttpServer.Request req, HttpServer.Response res, Target t, Users.User user) throws IOException {
        if (t.file != null && t.file.isFile()) {
            Storage.sendFile(req, res, t.file, false);
            return;
        }
        if (t.file != null && !t.file.exists()) {
            res.error(404, "not found");
            return;
        }
        // A plain HTML listing for browsers that open the DAV URL directly.
        StringBuilder sb = new StringBuilder("<!doctype html><meta charset=\"utf-8\"><title>NexShare WebDAV</title>"
            + "<style>body{font:15px system-ui;margin:24px}li{margin:4px 0}</style><h1>NexShare WebDAV</h1><ul>");
        if (t.file == null) {
            List<File> roots = Storage.rootDirs(ctx);
            for (int i = 0; i < roots.size(); i++) {
                sb.append("<li><a href=\"").append(PREFIX).append('/').append(Storage.rootId(i)).append("/\">")
                    .append(Storage.escapeHtml(Storage.rootName(i))).append("</a></li>");
            }
        } else {
            File[] kids = t.file.listFiles();
            if (kids != null) for (File k : kids) {
                if (k.getName().equals(Storage.TRASH_DIR)) continue;
                if (!user.allows(k.getCanonicalFile()) && !user.canTraverse(k.getCanonicalFile())) continue;
                String h = Storage.urlEncodePath(child(t, k).href) + (k.isDirectory() ? "/" : "");
                sb.append("<li><a href=\"").append(Storage.escapeHtml(h)).append("\">").append(Storage.escapeHtml(k.getName()))
                    .append(k.isDirectory() ? "/" : "").append("</a></li>");
            }
        }
        sb.append("</ul>");
        res.header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'");
        if ("HEAD".equals(req.method)) {
            res.begin(200, "text/html; charset=utf-8", sb.toString().getBytes("UTF-8").length);
            return;
        }
        res.text(200, "text/html; charset=utf-8", sb.toString());
    }

    private void put(HttpServer.Request req, HttpServer.Response res, Target t, Users.User user) throws IOException {
        if (t.file.isDirectory()) {
            res.error(405, "is a folder");
            return;
        }
        File parent = t.file.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            res.error(409, "parent folder missing");
            return;
        }
        if (!user.allows(parent)) {
            res.error(403, "forbidden");
            return;
        }
        String name = Storage.cleanName(t.file.getName());
        if (name == null) {
            res.error(400, "bad name");
            return;
        }
        if (Storage.exceedsQuota(ctx, user, Math.max(0L, req.contentLength))) {
            res.error(507, "quota_exceeded");
            return;
        }
        boolean existed = t.file.exists();
        File saved = Storage.receive(req, parent, name, true);
        if (saved == null) {
            res.error(400, "upload incomplete");
            return;
        }
        if (user.quotaBytes > 0 && Storage.userUsedBytes(ctx, user) > user.quotaBytes) {
            saved.delete();
            res.error(507, "quota_exceeded");
            return;
        }
        AuditLog.log(ctx, req, "upload", user.name, "dav:" + saved.getPath());
        res.empty(existed ? 204 : 201);
    }

    private void mkcol(HttpServer.Request req, HttpServer.Response res, Target t) throws IOException {
        if (req.contentLength != 0) {
            res.error(415, "MKCOL body not supported");
            return;
        }
        if (t.file.exists()) {
            res.error(405, "already exists");
            return;
        }
        File parent = t.file.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            res.error(409, "parent folder missing");
            return;
        }
        if (!t.file.mkdir()) {
            res.error(500, "cannot create folder");
            return;
        }
        res.empty(201);
    }

    /** Scratch files that Finder/Office create constantly; no point keeping them in the trash. */
    private static boolean junk(String name) {
        return name.startsWith("._") || name.equals(".DS_Store") || name.startsWith("~$") || name.startsWith(".~lock");
    }

    private void delete(HttpServer.Request req, HttpServer.Response res, Target t, Users.User user) throws IOException {
        if (!t.file.exists()) {
            res.error(404, "not found");
            return;
        }
        if (t.file.equals(t.root) || !user.allows(t.file)) {
            res.error(403, "forbidden");
            return;
        }
        if (!Trash.delete(ctx, t.file, user.name, junk(t.file.getName()))) {
            res.error(500, "cannot delete");
            return;
        }
        AuditLog.log(ctx, req, "delete", user.name, "dav:" + t.file.getPath());
        res.empty(204);
    }

    // ---------------------------------------------------------------- COPY / MOVE

    private void copyMove(HttpServer.Request req, HttpServer.Response res, Target src, Users.User user, boolean move) throws IOException {
        String dest = req.header("destination");
        if (dest == null) {
            res.error(400, "Destination header required");
            return;
        }
        int scheme = dest.indexOf("://");
        if (scheme >= 0) {
            int slash = dest.indexOf('/', scheme + 3);
            dest = slash < 0 ? "/" : dest.substring(slash);
        }
        int q = dest.indexOf('?');
        if (q >= 0) dest = dest.substring(0, q);
        dest = HttpServer.decodePath(dest);
        if (!matches(dest)) {
            res.error(502, "destination must be on this server");
            return;
        }
        Target dst;
        try {
            dst = target(dest, user);
        } catch (SecurityException e) {
            res.error(403, "forbidden");
            return;
        }
        if (dst == null || dst.file == null) {
            res.error(409, "bad destination");
            return;
        }
        if (!src.file.exists()) {
            res.error(404, "not found");
            return;
        }
        if (src.file.equals(src.root) || !user.allows(src.file) || !user.allows(dst.file)) {
            res.error(403, "forbidden");
            return;
        }
        if (dst.file.equals(src.file)) {
            res.error(403, "source and destination are the same");
            return;
        }
        if (dst.file.getPath().startsWith(src.file.getPath() + File.separator)
                || dst.file.getPath().startsWith(src.file.getPath() + "/")) {
            res.error(409, "cannot copy a folder into itself");
            return;
        }
        if (!move && Storage.exceedsQuota(ctx, user, Storage.sizeRec(src.file, 0, false))) {
            res.error(507, "quota_exceeded");
            return;
        }
        File parent = dst.file.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            res.error(409, "parent folder missing");
            return;
        }
        boolean existed = dst.file.exists();
        if (existed) {
            String ow = req.header("overwrite");
            if (ow != null && ow.trim().equalsIgnoreCase("F")) {
                res.error(412, "destination exists");
                return;
            }
            if (!Trash.delete(ctx, dst.file, user.name, junk(dst.file.getName()))) {
                res.error(500, "cannot replace destination");
                return;
            }
        }
        boolean ok = move ? Storage.move(src.file, dst.file) : Storage.copyRec(src.file, dst.file);
        if (!ok) {
            res.error(500, move ? "cannot move" : "cannot copy");
            return;
        }
        res.empty(existed ? 204 : 201);
    }

    // ---------------------------------------------------------------- LOCK / PROPPATCH

    private void lock(HttpServer.Request req, HttpServer.Response res, Target t) throws IOException {
        req.bodyBytes(1 << 16); // lock requests are always granted, details unused
        boolean created = false;
        if (!t.file.exists()) {
            // "Lock-null" resource: create an empty file, as clients expect before PUT.
            File parent = t.file.getParentFile();
            if (parent == null || !parent.isDirectory()) {
                res.error(409, "parent folder missing");
                return;
            }
            created = t.file.createNewFile();
        }
        String token = "opaquelocktoken:" + java.util.UUID.randomUUID();
        res.header("Lock-Token", "<" + token + ">");
        String xmlBody = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:prop xmlns:D=\"DAV:\"><D:lockdiscovery><D:activelock>"
            + "<D:locktype><D:write/></D:locktype><D:lockscope><D:exclusive/></D:lockscope><D:depth>infinity</D:depth>"
            + "<D:timeout>Second-3600</D:timeout><D:locktoken><D:href>" + token + "</D:href></D:locktoken>"
            + "<D:lockroot><D:href>" + xml(Storage.urlEncodePath(t.href)) + "</D:href></D:lockroot>"
            + "</D:activelock></D:lockdiscovery></D:prop>\n";
        res.text(created ? 201 : 200, "application/xml; charset=utf-8", xmlBody);
    }

    /**
     * Accepts property updates. We honour Windows' Win32LastModifiedTime and DAV getlastmodified
     * (so copied files keep their dates) and report success for everything else, which is what
     * Explorer and Finder need to finish a copy.
     */
    private void proppatch(HttpServer.Request req, HttpServer.Response res, Target t) throws IOException {
        String body = req.bodyString(1 << 16);
        if (!t.file.exists()) {
            res.error(404, "not found");
            return;
        }
        List<String[]> props = new ArrayList<>(); // {namespace, name}
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
            p.setInput(new StringReader(body));
            int depthOfProp = -1;
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    if ("prop".equals(p.getName()) && "DAV:".equals(p.getNamespace())) depthOfProp = p.getDepth();
                    else if (depthOfProp > 0 && p.getDepth() == depthOfProp + 1) {
                        String ns = p.getNamespace(), name = p.getName();
                        props.add(new String[]{ns, name});
                        if (name.equals("Win32LastModifiedTime") || name.equals("getlastmodified")) {
                            String v = p.nextText();
                            try {
                                SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
                                t.file.setLastModified(f.parse(v.trim()).getTime());
                            } catch (Exception ignored) { }
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG && "prop".equals(p.getName()) && p.getDepth() == depthOfProp) {
                    depthOfProp = -1;
                }
            }
        } catch (Exception ignored) { }
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>")
            .append(xml(Storage.urlEncodePath(t.href))).append("</D:href><D:propstat><D:prop>");
        int n = 0;
        for (String[] pr : props) {
            String prefix = "DAV:".equals(pr[0]) ? "D" : "x" + (n++);
            sb.append('<').append(prefix).append(':').append(pr[1]);
            if (!prefix.equals("D")) sb.append(" xmlns:").append(prefix).append("=\"").append(xml(pr[0] == null ? "" : pr[0])).append('"');
            sb.append("/>");
        }
        sb.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>\n");
        res.text(207, "application/xml; charset=utf-8", sb.toString());
    }

    private static String xml(String s) {
        return Storage.escapeHtml(s);
    }
}
