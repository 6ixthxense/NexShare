package com.nexshare;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.security.SecureRandom;
import java.util.Locale;

/** Small wrapper around SharedPreferences for access control, server options and features. */
public final class Settings {

    private Settings() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("nexshare", Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- PIN / admin password

    public static String pin(Context c) {
        SharedPreferences p = prefs(c);
        String pin = p.getString("pin", null);
        if (pin == null) {
            pin = newPin();
            p.edit().putString("pin", pin).apply();
        }
        return pin;
    }

    public static String regeneratePin(Context c) {
        String pin = newPin();
        prefs(c).edit().putString("pin", pin).apply();
        // Everyone who logged in with the old PIN has to log in again.
        SessionStore.get(c).revokeAll();
        return pin;
    }

    public static boolean pinEnabled(Context c) {
        return prefs(c).getBoolean("pinEnabled", true);
    }

    public static void setPinEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("pinEnabled", on).apply();
    }

    /** PBKDF2 hash of the admin password, or "" when the admin logs in with the PIN. */
    public static String adminPasswordHash(Context c) {
        return prefs(c).getString("adminPw", "");
    }

    public static boolean hasAdminPassword(Context c) {
        return !adminPasswordHash(c).isEmpty();
    }

    /** Sets (or with null/empty clears) the admin password, logging everyone out. */
    public static void setAdminPassword(Context c, String password) {
        prefs(c).edit().putString("adminPw", password == null || password.isEmpty() ? "" : Crypto.hashPassword(password)).apply();
        SessionStore.get(c).revokeAll();
    }

    /**
     * Whether visitors must log in. The phone owner can switch the PIN off for the LAN, but once
     * an admin password or extra users exist, login is always required.
     */
    public static boolean loginRequired(Context c) {
        return pinEnabled(c) || hasAdminPassword(c) || Users.get(c).hasUsers();
    }

    // ---------------------------------------------------------------- two-factor (TOTP)

    public static final String TOTP_OFF = "off", TOTP_TUNNEL = "tunnel", TOTP_ALWAYS = "always";

    public static String totpSecret(Context c) {
        return prefs(c).getString("totpSecret", "");
    }

    public static String totpMode(Context c) {
        return totpSecret(c).isEmpty() ? TOTP_OFF : prefs(c).getString("totpMode", TOTP_TUNNEL);
    }

    public static void setTotp(Context c, String secret, String mode) {
        prefs(c).edit().putString("totpSecret", secret == null ? "" : secret)
            .putString("totpMode", mode == null ? TOTP_OFF : mode).apply();
    }

    /** Last accepted TOTP step, so one code can't be replayed. */
    public static long totpLastStep(Context c) {
        return prefs(c).getLong("totpLastStep", -1);
    }

    public static void setTotpLastStep(Context c, long step) {
        prefs(c).edit().putLong("totpLastStep", step).apply();
    }

    // ---------------------------------------------------------------- HTTPS

    public static boolean httpsEnabled(Context c) {
        return prefs(c).getBoolean("https", true);
    }

    public static void setHttpsEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("https", on).apply();
    }

    /** Redirect plain-HTTP LAN visitors to HTTPS (the tunnel is HTTPS already). */
    public static boolean forceHttps(Context c) {
        return prefs(c).getBoolean("forceHttps", false);
    }

    public static void setForceHttps(Context c, boolean on) {
        prefs(c).edit().putBoolean("forceHttps", on).apply();
    }

    public static String tlsKeystorePassword(Context c) {
        SharedPreferences p = prefs(c);
        String pw = p.getString("tlsPw", null);
        if (pw == null) {
            pw = Crypto.randomToken(16);
            p.edit().putString("tlsPw", pw).apply();
        }
        return pw;
    }

    // ---------------------------------------------------------------- features

    public static boolean trashEnabled(Context c) {
        return prefs(c).getBoolean("trash", true);
    }

    public static void setTrashEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("trash", on).apply();
    }

    public static int trashDays(Context c) {
        return prefs(c).getInt("trashDays", 30);
    }

    public static void setTrashDays(Context c, int days) {
        prefs(c).edit().putInt("trashDays", Math.max(1, Math.min(365, days))).apply();
    }

    public static boolean webdavEnabled(Context c) {
        return prefs(c).getBoolean("webdav", true);
    }

    public static void setWebdavEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("webdav", on).apply();
    }

    /** WebDAV over the internet uses Basic auth with no 2FA, so it's opt-in. */
    public static boolean webdavViaTunnel(Context c) {
        return prefs(c).getBoolean("webdavTunnel", false);
    }

    public static void setWebdavViaTunnel(Context c, boolean on) {
        prefs(c).edit().putBoolean("webdavTunnel", on).apply();
    }

    /** Secret for signing share-link password cookies. */
    public static byte[] shareSecret(Context c) {
        SharedPreferences p = prefs(c);
        String s = p.getString("shareSecret", null);
        if (s == null) {
            s = Crypto.randomToken(32);
            p.edit().putString("shareSecret", s).apply();
        }
        return Crypto.unhex(s);
    }

    // ---------------------------------------------------------------- tunnel / misc

    public static String tunnelMode(Context c) {
        return prefs(c).getString("tunnelMode", "off");
    }

    public static String tunnelToken(Context c) {
        return prefs(c).getString("tunnelToken", "");
    }

    public static void setTunnel(Context c, String mode, String token) {
        prefs(c).edit().putString("tunnelMode", mode == null ? "off" : mode)
            .putString("tunnelToken", token == null ? "" : token).apply();
    }

    public static boolean keepScreenOn(Context c) {
        return prefs(c).getBoolean("keepScreenOn", true);
    }

    public static void setKeepScreenOn(Context c, boolean on) {
        prefs(c).edit().putBoolean("keepScreenOn", on).apply();
    }

    public static boolean startOnBoot(Context c) {
        return prefs(c).getBoolean("startOnBoot", false);
    }

    public static void setStartOnBoot(Context c, boolean on) {
        prefs(c).edit().putBoolean("startOnBoot", on).apply();
    }

    public static String serverName(Context c) {
        return prefs(c).getString("name", "NexShare-" + Build.MODEL.replace(' ', '-'));
    }

    private static String newPin() {
        return String.format(Locale.ROOT, "%06d", new SecureRandom().nextInt(1000000));
    }
}
