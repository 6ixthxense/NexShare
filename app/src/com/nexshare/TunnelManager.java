package com.nexshare;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs Cloudflare's {@code cloudflared} (bundled as a native lib so it is executable) to expose
 * the local web server on the internet — either a throwaway trycloudflare.com URL ("quick")
 * or a user's named tunnel via a token.
 */
public class TunnelManager {

    public enum State { OFF, STARTING, CONNECTED, ERROR }

    private static TunnelManager instance;

    private final Context ctx;
    private Process proc;
    private Thread reader;
    private final List<String> log = new ArrayList<>();
    private volatile State state = State.OFF;
    private volatile String url = "";
    private volatile String error = "";

    private static final Pattern QUICK_URL = Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com");

    public static synchronized TunnelManager get(Context c) {
        if (instance == null) instance = new TunnelManager(c.getApplicationContext());
        return instance;
    }

    private TunnelManager(Context ctx) {
        this.ctx = ctx;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized String url() {
        return url;
    }

    public synchronized String error() {
        return error;
    }

    public synchronized String logTail() {
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, log.size() - 40);
        for (int i = from; i < log.size(); i++) sb.append(log.get(i)).append('\n');
        return sb.toString();
    }

    /** The bundled cloudflared binary, extracted to the app's native lib dir and executable. */
    public File binary() {
        return new File(ctx.getApplicationInfo().nativeLibraryDir, "libcloudflared.so");
    }

    public boolean supported() {
        return binary().exists();
    }

    public synchronized void start(String mode, String token) throws Exception {
        stop();
        if (!supported()) throw new Exception("อุปกรณ์นี้ไม่รองรับ (ไม่มีไฟล์ cloudflared สำหรับ CPU นี้)");
        File cf = binary();
        File home = new File(ctx.getFilesDir(), "cloudflared");
        home.mkdirs();

        List<String> cmd = new ArrayList<>();
        cmd.add(cf.getAbsolutePath());
        cmd.add("tunnel");
        cmd.add("--no-autoupdate");
        cmd.add("--edge-ip-version");
        cmd.add("auto");
        if ("named".equals(mode)) {
            if (token == null || token.trim().isEmpty()) throw new Exception("ต้องใส่ token ของ tunnel");
            cmd.add("run");
            cmd.add("--token");
            cmd.add(token.trim());
        } else {
            cmd.add("--url");
            cmd.add("http://127.0.0.1:" + ServerService.PORT);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.directory(home);
        pb.environment().put("HOME", home.getAbsolutePath());
        pb.environment().put("TMPDIR", ctx.getCacheDir().getAbsolutePath());

        synchronized (this) {
            log.clear();
            url = "";
            error = "";
            state = State.STARTING;
        }
        final boolean named = "named".equals(mode);
        proc = pb.start();
        Settings.setTunnel(ctx, mode, token);

        reader = new Thread(new Runnable() {
            public void run() {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (TunnelManager.this) {
                            log.add(line);
                            if (log.size() > 200) log.remove(0);
                            Matcher m = QUICK_URL.matcher(line);
                            if (m.find()) {
                                url = m.group();
                                state = State.CONNECTED;
                            }
                            if (named && state != State.CONNECTED
                                && (line.contains("Registered tunnel connection") || line.contains("Connection registered"))) {
                                state = State.CONNECTED;
                            }
                            String low = line.toLowerCase();
                            if (low.contains("error") && (low.contains("unauthorized") || low.contains("invalid") || low.contains("failed to"))) {
                                if (state != State.CONNECTED) {
                                    error = line;
                                    state = State.ERROR;
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
                synchronized (TunnelManager.this) {
                    if (state != State.OFF && state != State.ERROR) {
                        if (state != State.CONNECTED) error = "cloudflared หยุดทำงาน";
                        state = state == State.CONNECTED ? State.OFF : State.ERROR;
                        url = "";
                    }
                }
            }
        }, "cloudflared");
        reader.setDaemon(true);
        reader.start();
    }

    public synchronized void stop() {
        if (proc != null) {
            proc.destroy();
            proc = null;
        }
        state = State.OFF;
        url = "";
    }

    /** Re-start the tunnel after a service restart if the user had one enabled. */
    public void restoreIfEnabled() {
        String mode = Settings.tunnelMode(ctx);
        if (mode == null || mode.equals("off")) return;
        try {
            start(mode, Settings.tunnelToken(ctx));
        } catch (Exception e) {
            synchronized (this) {
                error = e.getMessage();
                state = State.ERROR;
            }
        }
    }
}
