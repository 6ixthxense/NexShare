package com.nexshare;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Login sessions that survive an app/server restart.
 *
 * Only a SHA-256 hash of each token is stored (in its own SharedPreferences file),
 * so reading the prefs file does not hand out a usable cookie. Sessions expire after
 * {@link #TTL_MS}; they're revoked when the PIN/password changes or a user is removed.
 *
 * "Sudo" is a short re-authenticated window (memory only) required for dangerous
 * actions such as the root terminal, screen control and app installs.
 */
public final class SessionStore {

    public static final long TTL_MS = 30L * 24 * 60 * 60 * 1000; // matches the cookie Max-Age
    public static final long SUDO_MS = 10L * 60 * 1000;

    public static final class Session {
        public final String hash;
        public final String user;
        public final long expires;
        volatile long sudoUntil;

        Session(String hash, String user, long expires) {
            this.hash = hash;
            this.user = user;
            this.expires = expires;
        }

        public boolean sudo() {
            return sudoUntil > System.currentTimeMillis();
        }

        public long sudoUntil() {
            return sudoUntil;
        }
    }

    private static SessionStore instance;

    private final SharedPreferences prefs;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>(); // sha256(token) -> session

    public static synchronized SessionStore get(Context c) {
        if (instance == null) instance = new SessionStore(c.getApplicationContext());
        return instance;
    }

    private SessionStore(Context c) {
        prefs = c.getSharedPreferences("nexshare_sessions", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        SharedPreferences.Editor ed = prefs.edit();
        boolean dirty = false;
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            Session s = parse(e.getKey(), e.getValue());
            if (s != null && s.expires > now) {
                sessions.put(s.hash, s);
            } else {
                ed.remove(e.getKey());
                dirty = true;
            }
        }
        if (dirty) ed.apply();
    }

    private static Session parse(String hash, Object v) {
        if (v instanceof Long) return new Session(hash, Users.ADMIN, (Long) v); // v1.4 format
        if (!(v instanceof String)) return null;
        String s = (String) v;
        int bar = s.indexOf('|');
        if (bar < 0) return null;
        try {
            return new Session(hash, s.substring(bar + 1), Long.parseLong(s.substring(0, bar)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Creates a new session and returns the raw token to put in the cookie. */
    public String create(String user, boolean sudo) {
        String token = Crypto.randomToken(24);
        long exp = System.currentTimeMillis() + TTL_MS;
        Session s = new Session(hash(token), user, exp);
        if (sudo) s.sudoUntil = System.currentTimeMillis() + SUDO_MS;
        sessions.put(s.hash, s);
        prefs.edit().putString(s.hash, exp + "|" + user).apply();
        return token;
    }

    /** Returns the live session for this token, or null. */
    public Session find(String token) {
        if (token == null || token.isEmpty() || token.length() > 128) return null;
        Session s = sessions.get(hash(token));
        if (s == null) return null;
        if (s.expires <= System.currentTimeMillis()) {
            revokeHash(s.hash);
            return null;
        }
        return s;
    }

    public void grantSudo(Session s) {
        s.sudoUntil = System.currentTimeMillis() + SUDO_MS;
    }

    public void revoke(String token) {
        if (token == null || token.isEmpty()) return;
        revokeHash(hash(token));
    }

    /** Logs every browser out, e.g. after the PIN was regenerated. */
    public void revokeAll() {
        sessions.clear();
        prefs.edit().clear().apply();
    }

    /** Logs out every session of one user (password changed / user deleted). */
    public void revokeUser(String user) {
        SharedPreferences.Editor ed = prefs.edit();
        for (Iterator<Session> it = sessions.values().iterator(); it.hasNext(); ) {
            Session s = it.next();
            if (s.user.equalsIgnoreCase(user)) {
                it.remove();
                ed.remove(s.hash);
            }
        }
        ed.apply();
    }

    public int count() {
        return sessions.size();
    }

    private void revokeHash(String h) {
        sessions.remove(h);
        prefs.edit().remove(h).apply();
    }

    private static String hash(String token) {
        return Crypto.hex(Crypto.sha256(token.getBytes(StandardCharsets.UTF_8)));
    }
}
