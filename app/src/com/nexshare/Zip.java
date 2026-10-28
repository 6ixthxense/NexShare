package com.nexshare;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Streams files and folders into a zip on the fly. */
final class Zip {

    private Zip() { }

    static void add(ZipOutputStream z, File f, String name) throws IOException {
        if (f.getName().equals(Storage.TRASH_DIR)) return;
        if (f.isDirectory()) {
            z.putNextEntry(new ZipEntry(name + "/"));
            z.closeEntry();
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) add(z, k, name + "/" + k.getName());
            return;
        }
        ZipEntry e = new ZipEntry(name);
        e.setTime(f.lastModified());
        z.putNextEntry(e);
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) z.write(buf, 0, n);
        } catch (IOException ignored) { }
        z.closeEntry();
    }
}
