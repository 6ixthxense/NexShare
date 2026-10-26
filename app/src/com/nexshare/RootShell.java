package com.nexshare;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** A long-lived "su" process that runs commands one at a time and returns their output. */
public class RootShell {

    private static final RootShell MAIN = new RootShell();
    private static Boolean available;

    private Process proc;
    private OutputStream stdin;
    private InputStream stdout;

    public static RootShell main() {
        return MAIN;
    }

    /** True when this is the root edition and su grants us a uid 0 shell. */
    public static synchronized boolean available() {
        if (!Flavor.ROOT) return false;
        if (available == null) {
            try {
                available = MAIN.exec("id -u", 15000).trim().equals("0");
            } catch (Exception e) {
                available = false;
            }
        }
        return available;
    }

    public static synchronized void resetCheck() {
        available = null;
    }

    private void open() throws IOException {
        if (proc != null) {
            try {
                proc.exitValue();
            } catch (IllegalThreadStateException alive) {
                return;
            }
        }
        proc = new ProcessBuilder("su").redirectErrorStream(true).start();
        stdin = proc.getOutputStream();
        stdout = proc.getInputStream();
    }

    /** Runs a command (stderr merged) and returns its output. */
    public synchronized String exec(String cmd, long timeoutMs) throws IOException {
        return new String(execBytes(cmd, timeoutMs), StandardCharsets.UTF_8);
    }

    public synchronized byte[] execBytes(String cmd, long timeoutMs) throws IOException {
        open();
        String marker = "__NEX_" + UUID.randomUUID().toString().replace("-", "") + "__";
        stdin.write(("(" + cmd + ") 2>&1 </dev/null; echo; echo " + marker + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
        byte[] m = ("\n" + marker + "\n").getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + timeoutMs;
        byte[] buf = new byte[65536];
        while (true) {
            if (stdout.available() == 0) {
                if (System.currentTimeMillis() > deadline) {
                    kill();
                    throw new IOException("root command timed out");
                }
                try {
                    proc.exitValue();
                    kill();
                    throw new IOException("root shell closed (su denied?)");
                } catch (IllegalThreadStateException alive) {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        throw new IOException("interrupted");
                    }
                    continue;
                }
            }
            int n = stdout.read(buf, 0, Math.min(buf.length, Math.max(1, stdout.available())));
            if (n < 0) {
                kill();
                throw new IOException("root shell closed");
            }
            out.write(buf, 0, n);
            if (endsWith(out, m)) {
                byte[] all = out.toByteArray();
                byte[] res = new byte[all.length - m.length];
                System.arraycopy(all, 0, res, 0, res.length);
                return res;
            }
        }
    }

    private static boolean endsWith(ByteArrayOutputStream bo, byte[] suffix) {
        if (bo.size() < suffix.length) return false;
        byte[] b = bo.toByteArray();
        for (int i = 0; i < suffix.length; i++) if (b[b.length - suffix.length + i] != suffix[i]) return false;
        return true;
    }

    private void kill() {
        if (proc != null) proc.destroy();
        proc = null;
    }

    /** Single-quotes a value for the shell. */
    public static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** Termux's binaries, if the Termux app is installed, so the terminal can use its packages. */
    public static String termuxPrefix() {
        File usr = new File("/data/data/com.termux/files/usr");
        return usr.getPath();
    }
}
