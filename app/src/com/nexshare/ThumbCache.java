package com.nexshare;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ThumbnailUtils;
import android.provider.MediaStore;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Two-level thumbnail cache: a 16 MB in-memory LRU in front of a disk cache in the app's
 * cache dir (trimmed to {@link #DISK_LIMIT}), so thumbnails survive restarts and big photo
 * folders don't get re-decoded every time.
 */
public final class ThumbCache {

    static final long DISK_LIMIT = 256L * 1024 * 1024;

    private final File dir;
    private final LruCache<String, byte[]> mem = new LruCache<String, byte[]>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, byte[] v) {
            return v.length;
        }
    };
    private int writes;

    public ThumbCache(Context ctx) {
        dir = new File(ctx.getCacheDir(), "thumbs");
        dir.mkdirs();
    }

    /** Returns JPEG bytes, or null when the file has no thumbnail. */
    public byte[] get(File f, int size) {
        String key = f.getPath() + "|" + f.lastModified() + "|" + f.length() + "|" + size;
        byte[] data = mem.get(key);
        if (data != null) return data;
        File disk = new File(dir, Crypto.hex(Crypto.sha256(key.getBytes(StandardCharsets.UTF_8))).substring(0, 40) + ".jpg");
        if (disk.isFile()) {
            try (InputStream in = new FileInputStream(disk)) {
                data = Storage.readAll(in);
                disk.setLastModified(System.currentTimeMillis()); // LRU on disk
                mem.put(key, data);
                return data;
            } catch (IOException ignored) { }
        }
        data = render(f, size);
        if (data == null) return null;
        mem.put(key, data);
        try (OutputStream out = new FileOutputStream(disk)) {
            out.write(data);
        } catch (IOException ignored) { }
        if (++writes % 64 == 0) trim();
        return data;
    }

    private static byte[] render(File f, int size) {
        Bitmap bmp = null;
        String m = Storage.mime(f.getName());
        try {
            if (m.startsWith("video/")) {
                bmp = ThumbnailUtils.createVideoThumbnail(f.getPath(), MediaStore.Images.Thumbnails.MINI_KIND);
            } else if (m.startsWith("image/")) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), o);
                int sample = 1;
                while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= size) sample *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                bmp = BitmapFactory.decodeFile(f.getPath(), o);
            }
        } catch (Throwable ignored) { }
        if (bmp == null) return null;
        float scale = Math.min(1f, (float) size / Math.max(bmp.getWidth(), bmp.getHeight()));
        if (scale < 1f) {
            Bitmap s = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * scale), Math.round(bmp.getHeight() * scale), true);
            if (s != bmp) bmp.recycle();
            bmp = s;
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 78, bo);
        bmp.recycle();
        return bo.toByteArray();
    }

    /** Deletes least-recently-used files until the cache is under the limit. */
    synchronized void trim() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= DISK_LIMIT) return;
        Arrays.sort(files, new Comparator<File>() {
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        for (File f : files) {
            if (total <= DISK_LIMIT * 8 / 10) break;
            total -= f.length();
            f.delete();
        }
    }

    public void clear() {
        mem.evictAll();
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
    }
}
