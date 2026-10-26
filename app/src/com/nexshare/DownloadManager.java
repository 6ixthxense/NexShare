package com.nexshare;

import android.content.Context;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Download Station: HTTP(S) downloads with pause/resume, plus BitTorrent via TorrentEngine. */
public class DownloadManager {

    public static final int MAX_ACTIVE = 3;

    public static class Task {
        public String id, type, url, name, dest, status, error, hash;
        public long total = -1, done, speed, upSpeed, created;
        public int peers, seeds;
        volatile boolean stop;
        Thread thread;

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id).put("type", type).put("url", url).put("name", name).put("dest", dest)
                    .put("status", status).put("error", error == null ? JSONObject.NULL : error)
                    .put("total", total).put("done", done).put("speed", speed).put("upSpeed", upSpeed)
                    .put("peers", peers).put("seeds", seeds).put("created", created);
                if (hash != null) o.put("hash", hash);
            } catch (Exception ignored) { }
            return o;
        }
    }

    private static DownloadManager instance;
    private final Context ctx;
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private final File stateFile;
    private TorrentEngine torrents;

    public static synchronized DownloadManager get(Context c) {
        if (instance == null) instance = new DownloadManager(c.getApplicationContext());
        return instance;
    }

    private DownloadManager(Context ctx) {
        this.ctx = ctx;
        stateFile = new File(ctx.getFilesDir(), "downloads.json");
        load();
    }

    public static File defaultDir() {
        return new File(Environment.getExternalStorageDirectory(), "Download/NexShare");
    }

    // ---------- public API used by WebApi ----------

    public synchronized JSONArray list() {
        JSONArray arr = new JSONArray();
        if (torrents != null) torrents.refresh(tasks.values());
        List<Task> all = new ArrayList<>(tasks.values());
        for (int i = all.size() - 1; i >= 0; i--) arr.put(all.get(i).toJson());
        return arr;
    }

    public synchronized Task addUrl(String url, File dir) throws IOException {
        url = url.trim();
        if (url.startsWith("magnet:")) return addTorrent(url, null, dir);
        if (!url.startsWith("http://") && !url.startsWith("https://")) throw new IOException("unsupported link");
        Task t = newTask("http", url, dir);
        t.name = guessName(url);
        tasks.put(t.id, t);
        schedule();
        save();
        return t;
    }

    public synchronized Task addTorrent(String magnet, byte[] torrentFile, File dir) throws IOException {
        TorrentEngine te = torrents();
        Task t = newTask("torrent", magnet != null ? magnet : "(torrent file)", dir);
        t.name = magnet != null ? magnetName(magnet) : "torrent";
        t.status = "downloading";
        te.add(t, magnet, torrentFile, dir);
        for (Task other : tasks.values()) {
            if (t.hash != null && t.hash.equals(other.hash)) return other;
        }
        tasks.put(t.id, t);
        save();
        return t;
    }

    public synchronized void pause(String id) {
        Task t = tasks.get(id);
        if (t == null) return;
        if (t.type.equals("torrent")) {
            if (torrents != null) torrents.pause(t);
            t.status = "paused";
        } else if (t.status.equals("downloading") || t.status.equals("queued")) {
            t.stop = true;
            t.status = "paused";
            t.speed = 0;
        }
        save();
    }

    public synchronized void resume(String id) {
        Task t = tasks.get(id);
        if (t == null) return;
        if (t.type.equals("torrent")) {
            try {
                torrents().resume(t);
                t.status = "downloading";
            } catch (IOException e) {
                t.status = "error";
                t.error = e.getMessage();
            }
        } else if (!t.status.equals("downloading") && !t.status.equals("done")) {
            t.status = "queued";
            t.error = null;
            schedule();
        }
        save();
    }

    public synchronized void remove(String id, boolean deleteFiles) {
        Task t = tasks.remove(id);
        if (t == null) return;
        t.stop = true;
        if (t.type.equals("torrent")) {
            if (torrents != null) torrents.remove(t, deleteFiles);
        } else {
            new File(t.dest, t.name + ".part").delete();
            if (deleteFiles && t.status.equals("done")) new File(t.dest, t.name).delete();
        }
        save();
    }

    public synchronized void clearFinished() {
        List<String> ids = new ArrayList<>();
        for (Task t : tasks.values()) if (t.status.equals("done") && t.type.equals("http")) ids.add(t.id);
        for (String id : ids) tasks.remove(id);
        save();
    }

    public synchronized boolean torrentSupported() {
        try {
            torrents();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    public void shutdown() {
        synchronized (this) {
            for (Task t : tasks.values()) t.stop = true;
            save();
        }
        if (torrents != null) torrents.stop();
    }

    // ---------- internals ----------

    private TorrentEngine torrents() throws IOException {
        if (torrents == null) {
            try {
                torrents = new TorrentEngine(ctx);
                torrents.restore(tasks.values());
            } catch (Throwable e) {
                throw new IOException("BitTorrent unavailable: " + e.getMessage());
            }
        }
        return torrents;
    }

    private Task newTask(String type, String url, File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create folder");
        Task t = new Task();
        t.id = UUID.randomUUID().toString().substring(0, 8);
        t.type = type;
        t.url = url;
        t.dest = dir.getAbsolutePath();
        t.status = "queued";
        t.created = System.currentTimeMillis();
        return t;
    }

    private void schedule() {
        int active = 0;
        for (Task t : tasks.values()) if (t.type.equals("http") && t.status.equals("downloading")) active++;
        for (Task t : tasks.values()) {
            if (active >= MAX_ACTIVE) break;
            if (t.type.equals("http") && t.status.equals("queued")) {
                start(t);
                active++;
            }
        }
    }

    private void start(final Task t) {
        t.status = "downloading";
        t.stop = false;
        t.thread = new Thread(new Runnable() {
            public void run() {
                String result;
                String err = null;
                try {
                    result = fetch(t) ? "done" : "paused";
                } catch (Exception e) {
                    result = "error";
                    err = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                synchronized (DownloadManager.this) {
                    t.speed = 0;
                    if (!result.equals("paused")) { // a pause already set its own status
                        t.status = result;
                        t.error = err;
                    }
                    if (tasks.containsKey(t.id)) {
                        schedule();
                        save();
                    }
                }
            }
        }, "dl-" + t.id);
        t.thread.start();
    }

    /** Returns true when finished, false when stopped. Resumes from NAME.part with a Range request. */
    private boolean fetch(Task t) throws IOException {
        URL url = new URL(t.url);
        HttpURLConnection c = null;
        for (int redirects = 0; ; redirects++) {
            File part = new File(t.dest, t.name + ".part");
            long have = part.exists() ? part.length() : 0;
            c = (HttpURLConnection) url.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) NexShare/1.1");
            if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null && redirects < 10) {
                url = new URL(url, c.getHeaderField("Location"));
                c.disconnect();
                continue;
            }
            if (code == 416 && have > 0) { // already complete
                finish(t, part);
                return true;
            }
            if (code != 200 && code != 206) throw new IOException("HTTP " + code);
            if (have == 0 && t.done == 0) {
                String n = nameFromDisposition(c.getHeaderField("Content-Disposition"));
                if (n == null) n = guessName(url.toString());
                synchronized (this) {
                    t.name = uniqueName(new File(t.dest), n);
                }
                part = new File(t.dest, t.name + ".part");
            }
            boolean append = code == 206 && have > 0;
            long len = c.getContentLengthLong();
            t.total = len < 0 ? -1 : (append ? have + len : len);
            t.done = append ? have : 0;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part, append)) {
                byte[] buf = new byte[65536];
                long windowStart = System.currentTimeMillis(), windowBytes = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (t.stop) return false;
                    out.write(buf, 0, n);
                    t.done += n;
                    windowBytes += n;
                    long now = System.currentTimeMillis();
                    if (now - windowStart >= 1000) {
                        t.speed = windowBytes * 1000 / (now - windowStart);
                        windowStart = now;
                        windowBytes = 0;
                    }
                }
            } finally {
                c.disconnect();
            }
            if (t.stop) return false;
            if (t.total > 0 && t.done < t.total) throw new IOException("connection closed early");
            finish(t, part);
            return true;
        }
    }

    private void finish(Task t, File part) throws IOException {
        File target = new File(t.dest, t.name);
        if (target.exists()) {
            t.name = uniqueName(new File(t.dest), t.name);
            target = new File(t.dest, t.name);
        }
        if (!part.renameTo(target)) throw new IOException("cannot save file");
        t.total = target.length();
        t.done = t.total;
    }

    static String guessName(String url) {
        String p = url;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        int h = p.indexOf('#');
        if (h >= 0) p = p.substring(0, h);
        String n = p.substring(p.lastIndexOf('/') + 1);
        try {
            n = URLDecoder.decode(n, "UTF-8");
        } catch (Exception ignored) { }
        n = sanitize(n);
        return n.isEmpty() ? "download" : n;
    }

    static String nameFromDisposition(String cd) {
        if (cd == null) return null;
        String lower = cd.toLowerCase(Locale.ROOT);
        int i = lower.indexOf("filename*=");
        try {
            if (i >= 0) {
                String v = cd.substring(i + 10).split(";")[0].trim().replace("\"", "");
                int tick = v.indexOf("''");
                if (tick >= 0) v = URLDecoder.decode(v.substring(tick + 2), "UTF-8");
                return sanitize(v);
            }
            i = lower.indexOf("filename=");
            if (i >= 0) return sanitize(cd.substring(i + 9).split(";")[0].trim().replace("\"", ""));
        } catch (Exception ignored) { }
        return null;
    }

    static String magnetName(String magnet) {
        for (String kv : magnet.substring(magnet.indexOf('?') + 1).split("&")) {
            if (kv.startsWith("dn=")) {
                try {
                    return URLDecoder.decode(kv.substring(3), "UTF-8");
                } catch (Exception ignored) { }
            }
        }
        return "magnet";
    }

    private static String sanitize(String n) {
        return n.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").trim();
    }

    private static String uniqueName(File dir, String name) {
        if (!new File(dir, name).exists() && !new File(dir, name + ".part").exists()) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            String n = base + " (" + i + ")" + ext;
            if (!new File(dir, n).exists() && !new File(dir, n + ".part").exists()) return n;
        }
    }

    // ---------- persistence ----------

    private void load() {
        if (!stateFile.exists()) return;
        try (InputStream in = new FileInputStream(stateFile)) {
            byte[] b = new byte[(int) stateFile.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            JSONArray arr = new JSONArray(new String(b, StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Task t = new Task();
                t.id = o.getString("id");
                t.type = o.getString("type");
                t.url = o.getString("url");
                t.name = o.getString("name");
                t.dest = o.getString("dest");
                t.status = o.getString("status");
                t.total = o.optLong("total", -1);
                t.done = o.optLong("done");
                t.created = o.optLong("created");
                t.hash = o.has("hash") ? o.getString("hash") : null;
                if (t.type.equals("http") && (t.status.equals("downloading") || t.status.equals("queued"))) t.status = "paused";
                tasks.put(t.id, t);
            }
        } catch (Exception ignored) { }
    }

    synchronized void save() {
        JSONArray arr = new JSONArray();
        for (Task t : tasks.values()) arr.put(t.toJson());
        File tmp = new File(stateFile.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return;
        }
        tmp.renameTo(stateFile);
    }

    /** Resume torrents and keep unfinished HTTP tasks paused after a restart. */
    public synchronized void startup() {
        boolean anyTorrent = false;
        for (Task t : tasks.values()) if (t.type.equals("torrent")) anyTorrent = true;
        if (anyTorrent) {
            try {
                torrents();
            } catch (IOException ignored) { }
        }
    }

}
