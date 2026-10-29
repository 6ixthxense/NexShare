package com.nexshare;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Streams files and folders into a zip on the fly, and compresses/extracts archives on disk. */
public final class Zip {

    private Zip() { }

    static void add(ZipOutputStream z, File f, String name) throws IOException {
        add(z, f, name, null);
    }

    static void add(ZipOutputStream z, File f, String name, File skipFile) throws IOException {
        if (f.getName().equals(Storage.TRASH_DIR) || f.getName().endsWith(".nexpart")) return;
        if (skipFile != null && f.getCanonicalFile().equals(skipFile.getCanonicalFile())) return;
        if (f.isDirectory()) {
            z.putNextEntry(new ZipEntry(name + "/"));
            z.closeEntry();
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) add(z, k, name + "/" + k.getName(), skipFile);
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

    /** Compresses a list of files/folders into targetZip on disk. */
    public static void compressToFile(List<File> items, File targetZip) throws IOException {
        File tmp = new File(targetZip.getParentFile(), "." + targetZip.getName() + ".nexpart");
        boolean ok = false;
        try (OutputStream fout = new FileOutputStream(tmp);
             ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(fout, 65536))) {
            z.setLevel(Deflater.BEST_SPEED);
            for (File f : items) {
                add(z, f, f.getName(), targetZip);
            }
            z.finish();
            z.flush();
            ok = true;
        } finally {
            if (!ok) tmp.delete();
        }
        if (targetZip.isFile()) targetZip.delete();
        if (!tmp.renameTo(targetZip)) {
            tmp.delete();
            throw new IOException("cannot save zip");
        }
    }

    /**
     * Safely extracts zipFile into destDir, rejecting Zip-Slip path traversal entries
     * and enforcing maxBytes total uncompressed output. Returns number of extracted files.
     */
    public static int extract(File zipFile, File destDir, long maxBytes) throws IOException {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IOException("cannot create destination folder");
        }
        String destCanon = destDir.getCanonicalPath();
        int count = 0;
        long writtenTotal = 0;
        byte[] buf = new byte[65536];
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String rawName = entry.getName().replace('\\', '/');
                if (rawName.isEmpty() || rawName.startsWith("/") || rawName.contains("../") || rawName.equals("..")) {
                    throw new SecurityException("zip_slip_blocked");
                }
                File out = new File(destDir, rawName).getCanonicalFile();
                String outPath = out.getPath();
                if (!outPath.equals(destCanon)
                        && !outPath.startsWith(destCanon + File.separator)
                        && !outPath.startsWith(destCanon + "/")) {
                    throw new SecurityException("zip_slip_blocked");
                }
                if (entry.isDirectory()) {
                    if (!out.isDirectory() && !out.mkdirs()) {
                        throw new IOException("cannot create directory: " + rawName);
                    }
                } else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("cannot create parent directory");
                    }
                    try (OutputStream fout = new FileOutputStream(out)) {
                        int n;
                        while ((n = zin.read(buf)) > 0) {
                            writtenTotal += n;
                            if (maxBytes > 0 && writtenTotal > maxBytes) {
                                fout.close();
                                out.delete();
                                throw new IOException("quota_exceeded");
                            }
                            fout.write(buf, 0, n);
                        }
                    }
                    if (entry.getTime() > 0) out.setLastModified(entry.getTime());
                    count++;
                }
                zin.closeEntry();
            }
        }
        return count;
    }
}
