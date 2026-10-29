package com.nexshare;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Persistent ring buffer of security and file activity events (login, failed login, logout,
 * upload, download, delete, restore, share creation, file request uploads, user changes).
 */
public final class AuditLog {

    private static final String PREFS = "nexshare_audit";
    private static final String KEY = "entries";
    private static final int MAX_ENTRIES = 500;

    private AuditLog() { }

    public static synchronized void log(Context ctx, HttpServer.Request req, String action, String user, String detail) {
        String ip = req != null ? req.clientIp() : "local";
        log(ctx, action, user, ip, detail);
    }

    public static synchronized void log(Context ctx, String action, String user, String ip, String detail) {
        if (ctx == null || action == null) return;
        try {
            SharedPreferences prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray old = new JSONArray(prefs.getString(KEY, "[]"));
            JSONObject entry = new JSONObject();
            entry.put("ts", System.currentTimeMillis());
            entry.put("action", action);
            entry.put("user", user == null || user.isEmpty() ? "-" : user);
            entry.put("ip", ip == null || ip.isEmpty() ? "-" : ip);
            entry.put("detail", detail == null ? "" : detail);

            JSONArray next = new JSONArray();
            next.put(entry);
            int limit = Math.min(old.length(), MAX_ENTRIES - 1);
            for (int i = 0; i < limit; i++) {
                JSONObject o = old.optJSONObject(i);
                if (o != null) next.put(o);
            }
            prefs.edit().putString(KEY, next.toString()).apply();
        } catch (Exception ignored) { }
    }

    public static synchronized JSONArray list(Context ctx, int limit, String actionFilter, String userFilter) {
        JSONArray out = new JSONArray();
        if (ctx == null) return out;
        int max = limit <= 0 ? 200 : Math.min(limit, MAX_ENTRIES);
        try {
            SharedPreferences prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < arr.length() && out.length() < max; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if (actionFilter != null && !actionFilter.isEmpty() && !actionFilter.equals(o.optString("action"))) continue;
                if (userFilter != null && !userFilter.isEmpty() && !userFilter.equalsIgnoreCase(o.optString("user"))) continue;
                out.put(o);
            }
        } catch (Exception ignored) { }
        return out;
    }

    public static synchronized void clear(Context ctx) {
        if (ctx == null) return;
        ctx.getApplicationContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY)
            .apply();
    }
}

