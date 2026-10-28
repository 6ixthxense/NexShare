package com.nexshare;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Recycle bin. Deleted items move into "<storage root>/.nexshare-trash/<id>" (a rename on the
 * same volume, so it's instant), with "<id>.json" recording where they came from.
 * Items older than the configured number of days are purged automatically.
 */
public final class Trash {

    private Trash() { }

    private static long lastPurge;

    private static File dirFor(Context ctx, File item) {
        File root = Storage.rootOf(ctx, item);
        if (root == null) return null;
        File d = new File(root, Storage.TRASH_DIR);
        if (!d.isDirectory() && !d.mkdirs()) return null;
        File nomedia = new File(d, ".nomedia"); // keep trashed photos out of the gallery
        if (!nomedia.exists()) try { nomedia.createNewFile(); } catch (IOException ignored) { }
        return d;
    }

    /** Moves an item to the trash. Returns false when that isn't possible (caller may hard-delete). */
    public static boolean put(Context ctx, File item, String user) {
        File dir = dirFor(ctx, item);
        if (dir == null) return false;
        String id = System.currentTimeMillis() + "-" + Crypto.randomToken(4);
        File dst = new File(dir, id);
        long size = Storage.sizeRec(item, 0);
        boolean isDir = item.isDirectory();
        if (!item.renameTo(dst)) return false;
        JSONObject meta = new JSONObject();
        Storage.put(meta, "id", id);
        Storage.put(meta, "name", item.getName());
        Storage.put(meta, "orig", item.getPath());
        Storage.put(meta, "deleted", System.currentTimeMillis());
        Storage.put(meta, "size", size);
        Storage.put(meta, "dir", isDir);
        Storage.put(meta, "by", user);
        try (OutputStream out = new FileOutputStream(new File(dir, id + ".json"))) {
            out.write(meta.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            item.getParentFile().mkdirs();
            dst.renameTo(item);
            return false;
        }
        return true;
    }

    private static JSONObject meta(File json) {
        try (InputStream in = new FileInputStream(json)) {
            return new JSONObject(new String(Storage.readAll(in), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Object[]> all(Context ctx) {
        List<Object[]> out = new ArrayList<>(); // {metaJson, trashDir}
        for (String r : Storage.canonicalRoots(ctx)) {
            File d = new File(r, Storage.TRASH_DIR);
            File[] files = d.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.getName().endsWith(".json")) continue;
                JSONObject m = meta(f);
                if (m == null) continue;
                if (!new File(d, m.optString("id")).exists()) {
                    f.delete(); // payload gone
                    continue;
                }
                out.add(new Object[]{m, d});
            }
        }
        return out;
    }

    /** Items the user may see (their original location is within the user's folders). */
    public static JSONArray list(Context ctx, Users.User user) {
        purgeExpired(ctx);
        JSONArray arr = new JSONArray();
        for (Object[] it : all(ctx)) {
            JSONObject m = (JSONObject) it[0];
            if (user.allows(new File(m.optString("orig")))) arr.put(m);
        }
        return arr;
    }

    private static Object[] find(Context ctx, Users.User user, String id) {
        if (id == null || !id.matches("[0-9a-f-]+")) return null;
        for (Object[] it : all(ctx)) {
            JSONObject m = (JSONObject) it[0];
            if (m.optString("id").equals(id) && user.allows(new File(m.optString("orig")))) return it;
        }
        return null;
    }

    /** Restores to the original folder (recreating it), renaming on conflict. Returns the new path or null. */
    public static String restore(Context ctx, Users.User user, String id) {
        Object[] it = find(ctx, user, id);
        if (it == null) return null;
        JSONObject m = (JSONObject) it[0];
        File dir = (File) it[1];
        File orig = new File(m.optString("orig"));
        File parent = orig.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) return null;
        File dst = Storage.uniqueFile(parent, orig.getName());
        if (!new File(dir, id).renameTo(dst)) return null;
        new File(dir, id + ".json").delete();
        return dst.getPath();
    }

    public static boolean purge(Context ctx, Users.User user, String id) {
        Object[] it = find(ctx, user, id);
        if (it == null) return false;
        File dir = (File) it[1];
        Storage.deleteRec(new File(dir, id));
        new File(dir, id + ".json").delete();
        return true;
    }

    /** Empties everything the user can see. */
    public static int empty(Context ctx, Users.User user) {
        int n = 0;
        for (Object[] it : all(ctx)) {
            JSONObject m = (JSONObject) it[0];
            if (!user.allows(new File(m.optString("orig")))) continue;
            File dir = (File) it[1];
            Storage.deleteRec(new File(dir, m.optString("id")));
            new File(dir, m.optString("id") + ".json").delete();
            n++;
        }
        return n;
    }

    /** Deletes items older than the retention period; cheap to call often. */
    public static synchronized void purgeExpired(Context ctx) {
        long now = System.currentTimeMillis();
        if (now - lastPurge < 60 * 60 * 1000) return;
        lastPurge = now;
        long cutoff = now - Settings.trashDays(ctx) * 24L * 60 * 60 * 1000;
        for (Object[] it : all(ctx)) {
            JSONObject m = (JSONObject) it[0];
            if (m.optLong("deleted") < cutoff) {
                File dir = (File) it[1];
                Storage.deleteRec(new File(dir, m.optString("id")));
                new File(dir, m.optString("id") + ".json").delete();
            }
        }
    }

    /** Deletes via the trash when enabled, otherwise permanently. */
    public static boolean delete(Context ctx, File f, String user, boolean permanent) {
        if (!permanent && Settings.trashEnabled(ctx) && put(ctx, f, user)) return true;
        return Storage.deleteRec(f);
    }
}
