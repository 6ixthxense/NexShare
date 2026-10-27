package com.nexshare;

import android.content.Context;

import java.io.File;

/**
 * Bundled command-line tools. BusyBox (one static binary with ~300 applets: ls, grep, sed,
 * awk, tar, vi, top, wget, nc, find, …) ships as a native lib so the root terminal has a
 * usable toolset without installing Termux. Termux's own packages are still used when present.
 */
public final class ToolBox {

    private ToolBox() { }

    /** The bundled busybox binary (extracted executable), or null if this build/ABI lacks it. */
    public static File busybox(Context ctx) {
        File f = new File(ctx.getApplicationInfo().nativeLibraryDir, "libbusybox.so");
        return f.exists() ? f : null;
    }

    public static boolean hasBusybox(Context ctx) {
        return busybox(ctx) != null;
    }

    /** Directory where busybox applet symlinks are installed, or null if busybox isn't bundled. */
    public static String busyboxBinDir(Context ctx) {
        if (busybox(ctx) == null) return null;
        return new File(ctx.getFilesDir(), "toolbox/bin").getAbsolutePath();
    }

    /**
     * Shell snippet (run before each command) that installs busybox applet symlinks once into
     * the bin dir. Cheap after the first run thanks to the existence guard.
     */
    public static String setupPrelude(Context ctx) {
        File bb = busybox(ctx);
        if (bb == null) return "";
        String bin = busyboxBinDir(ctx);
        return "__BB=" + RootShell.q(bb.getAbsolutePath()) + "; __BBIN=" + RootShell.q(bin) + "; "
            + "if [ ! -e \"$__BBIN/busybox\" ]; then mkdir -p \"$__BBIN\"; ln -sf \"$__BB\" \"$__BBIN/busybox\"; "
            + "\"$__BBIN/busybox\" --install -s \"$__BBIN\" 2>/dev/null; fi; ";
    }
}
