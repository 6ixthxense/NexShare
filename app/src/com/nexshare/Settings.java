package com.nexshare;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.security.SecureRandom;
import java.util.Locale;

/** Small wrapper around SharedPreferences for the access PIN and server name. */
public final class Settings {

    private Settings() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("nexshare", Context.MODE_PRIVATE);
    }

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
        return pin;
    }

    public static boolean pinEnabled(Context c) {
        return prefs(c).getBoolean("pinEnabled", true);
    }

    public static void setPinEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("pinEnabled", on).apply();
    }

    public static boolean keepScreenOn(Context c) {
        return prefs(c).getBoolean("keepScreenOn", true);
    }

    public static void setKeepScreenOn(Context c, boolean on) {
        prefs(c).edit().putBoolean("keepScreenOn", on).apply();
    }

    public static String serverName(Context c) {
        return prefs(c).getString("name", "NexShare-" + Build.MODEL.replace(' ', '-'));
    }

    private static String newPin() {
        return String.format(Locale.ROOT, "%06d", new SecureRandom().nextInt(1000000));
    }
}
