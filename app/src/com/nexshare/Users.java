package com.nexshare;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Accounts besides the phone owner ("admin"). Each user has a role and, optionally,
 * a list of folders they're limited to.
 *
 * Roles: admin (everything), rw (read + write files, downloads, shares), ro (browse/download only).
 */
public final class Users {

    public static final String ADMIN = "admin";
    public static final String ROLE_ADMIN = "admin", ROLE_RW = "rw", ROLE_RO = "ro";

    public static final class User {
        public final String name;
        public final String role;
        /** Canonical folder paths this user may see; empty means every storage root. */
        public final List<String> folders;
        /** Maximum total bytes allowed in this user's folders (0 = unlimited). */
        public final long quotaBytes;
        final String pwHash;

        User(String name, String role, List<String> folders, String pwHash) {
            this(name, role, folders, pwHash, 0L);
        }

        User(String name, String role, List<String> folders, String pwHash, long quotaBytes) {
            this.name = name;
            this.role = role;
            this.folders = Collections.unmodifiableList(new ArrayList<>(folders));
            this.pwHash = pwHash;
            this.quotaBytes = Math.max(0L, quotaBytes);
        }

        public boolean isAdmin() {
            return ROLE_ADMIN.equals(role);
        }

        public boolean canWrite() {
            return ROLE_ADMIN.equals(role) || ROLE_RW.equals(role);
        }

        /** Whether this canonical path is inside one of the user's folders (or the user is unrestricted). */
        public boolean allows(File canonical) {
            if (folders.isEmpty()) return true;
            String p = canonical.getPath();
            for (String f : folders) {
                if (p.equals(f) || p.startsWith(f + File.separator) || p.startsWith(f + "/")) return true;
            }
            return false;
        }

        /** Whether the path is an ancestor of an allowed folder (visible only to reach it). */
        public boolean canTraverse(File canonical) {
            if (folders.isEmpty()) return true;
            String p = canonical.getPath();
            for (String f : folders) {
                if (f.startsWith(p + File.separator) || f.startsWith(p + "/")) return true;
            }
            return false;
        }

        JSONObject toJson(boolean withHash) {
            JSONObject o = new JSONObject();
            try {
                o.put("name", name);
                o.put("role", role);
                o.put("folders", new JSONArray(folders));
                o.put("quota", quotaBytes);
                if (withHash) o.put("pw", pwHash);
            } catch (Exception ignored) { }
            return o;
        }
    }

    /** The built-in owner account (logs in with the PIN or admin password). */
    public static final User OWNER = new User(ADMIN, ROLE_ADMIN, new ArrayList<String>(), "", 0L);

    private static Users instance;
    private final SharedPreferences prefs;
    private final Map<String, User> users = new LinkedHashMap<>();

    public static synchronized Users get(Context c) {
        if (instance == null) instance = new Users(c.getApplicationContext());
        return instance;
    }

    private Users(Context c) {
        prefs = c.getSharedPreferences("nexshare_users", Context.MODE_PRIVATE);
        try {
            JSONArray arr = new JSONArray(prefs.getString("users", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                List<String> folders = new ArrayList<>();
                JSONArray f = o.optJSONArray("folders");
                for (int j = 0; f != null && j < f.length(); j++) folders.add(f.getString(j));
                User u = new User(o.getString("name"), o.optString("role", ROLE_RO), folders, o.optString("pw"), o.optLong("quota", 0L));
                users.put(key(u.name), u);
            }
        } catch (Exception ignored) { }
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9._-]{2,32}") && !key(name).equals(ADMIN);
    }

    public static boolean validRole(String role) {
        return ROLE_ADMIN.equals(role) || ROLE_RW.equals(role) || ROLE_RO.equals(role);
    }

    public synchronized boolean hasUsers() {
        return !users.isEmpty();
    }

    public synchronized User find(String name) {
        if (name == null) return null;
        if (key(name).equals(ADMIN)) return OWNER;
        return users.get(key(name));
    }

    /** Checks a regular (non-owner) user's password. */
    public User check(String name, String password) {
        User u;
        synchronized (this) {
            u = name == null || key(name).equals(ADMIN) ? null : users.get(key(name));
        }
        if (u == null) {
            // Spend the same time as a real check so usernames can't be probed by timing.
            Crypto.verifyPassword(password == null ? "" : password, dummyHash());
            return null;
        }
        return Crypto.verifyPassword(password, u.pwHash) ? u : null;
    }

    private static String dummyHash;

    /** Computed lazily: PBKDF2 is slow and Users is first touched on the phone's UI thread. */
    private static synchronized String dummyHash() {
        if (dummyHash == null) dummyHash = Crypto.hashPassword("nexshare-dummy");
        return dummyHash;
    }

    public synchronized JSONArray list() {
        JSONArray arr = new JSONArray();
        for (User u : users.values()) arr.put(u.toJson(false));
        return arr;
    }

    /**
     * Creates or updates a user. A null/empty password keeps the existing one (required for new users).
     * Returns an error message or null.
     */
    public synchronized String save(String name, String role, List<String> folders, String password) {
        User old = name == null ? null : users.get(key(name));
        return save(name, role, folders, password, old != null ? old.quotaBytes : 0L);
    }

    public synchronized String save(String name, String role, List<String> folders, String password, long quotaBytes) {
        if (!validName(name)) return "ชื่อผู้ใช้ใช้ได้เฉพาะ a-z 0-9 . _ - (2–32 ตัว) และห้ามใช้ admin";
        if (!validRole(role)) return "บทบาทไม่ถูกต้อง";
        if (quotaBytes < 0) return "โควตาพื้นที่ต้องไม่ติดลบ";
        User old = users.get(key(name));
        String hash;
        if (password != null && !password.isEmpty()) {
            if (password.length() < 8) return "รหัสผ่านต้องยาวอย่างน้อย 8 ตัวอักษร";
            hash = Crypto.hashPassword(password);
        } else if (old != null) {
            hash = old.pwHash;
        } else {
            return "ต้องตั้งรหัสผ่านให้ผู้ใช้ใหม่";
        }
        users.put(key(name), new User(old != null ? old.name : name.trim(), role, folders, hash, quotaBytes));
        persist();
        return null;
    }

    public synchronized boolean delete(String name) {
        boolean removed = users.remove(key(name)) != null;
        if (removed) persist();
        return removed;
    }

    private void persist() {
        JSONArray arr = new JSONArray();
        for (User u : users.values()) arr.put(u.toJson(true));
        prefs.edit().putString("users", arr.toString()).apply();
    }
}
