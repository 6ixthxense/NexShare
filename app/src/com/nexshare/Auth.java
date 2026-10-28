package com.nexshare;

import android.content.Context;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authentication and brute-force protection shared by the web UI, WebDAV and share links.
 *
 * - The owner ("admin") logs in with the PIN, or with an admin password once one is set.
 * - Extra users log in with username + password (see {@link Users}).
 * - Optional TOTP 2FA for the owner, required either always or only through the tunnel.
 * - Failed attempts lock the client IP with exponential backoff; through the tunnel the real
 *   IP comes from CF-Connecting-IP, and all tunnel failures also share one global budget
 *   because an attacker on the internet can rotate IPs.
 */
public final class Auth {

    public static final String COOKIE = "ns_token";

    /** Who is making a request. session is null when login is switched off on the LAN. */
    public static final class Who {
        public final Users.User user;
        public final SessionStore.Session session;

        Who(Users.User user, SessionStore.Session session) {
            this.user = user;
            this.session = session;
        }

        public boolean sudo() {
            return session != null && session.sudo();
        }
    }

    public enum Result { OK, BAD, LOCKED, OTP_REQUIRED }

    private static final int MAX_FAILURES = 5;
    private static final int BASE_LOCK_SEC = 60;
    private static final int MAX_LOCK_LEVEL = 6; // 60s * 2^6 = 64 min
    private static final int TUNNEL_MAX_FAILURES = 20;
    private static final int TUNNEL_WINDOW_SEC = 600;

    private static Auth instance;

    private final Context ctx;
    private final SessionStore sessions;
    private final Users users;
    private final Map<String, int[]> failures = new ConcurrentHashMap<>(); // ip -> {count, lockUntilSec, level}
    private int tunnelCount, tunnelWindowStart, tunnelLockUntil;
    /** WebDAV clients send Basic credentials on every request; remember good ones briefly. */
    private final Map<String, Object[]> basicCache = new ConcurrentHashMap<>(); // sha256(header) -> {User, expiresMs}

    public static synchronized Auth get(Context c) {
        if (instance == null) instance = new Auth(c.getApplicationContext());
        return instance;
    }

    private Auth(Context ctx) {
        this.ctx = ctx;
        this.sessions = SessionStore.get(ctx);
        this.users = Users.get(ctx);
    }

    // ---------------------------------------------------------------- identifying requests

    /** The authenticated caller, or null when the request must log in first. */
    public Who who(HttpServer.Request req) {
        SessionStore.Session s = sessions.find(req.cookie(COOKIE));
        if (s != null) {
            Users.User u = users.find(s.user);
            if (u != null) return new Who(u, s);
            sessions.revoke(req.cookie(COOKIE));
        }
        // The owner may switch login off for the LAN; never for the internet.
        if (!req.viaTunnel() && !Settings.loginRequired(ctx)) return new Who(Users.OWNER, null);
        return null;
    }

    /** Whether logging in as the owner from this connection needs a 2FA code. */
    public boolean otpRequired(HttpServer.Request req) {
        String mode = Settings.totpMode(ctx);
        return Settings.TOTP_ALWAYS.equals(mode) || (Settings.TOTP_TUNNEL.equals(mode) && req.viaTunnel());
    }

    /**
     * CSRF defence in depth (the session cookie is already SameSite=Strict): browsers send
     * Origin on cross-site requests, so reject any API call whose Origin/Referer host isn't
     * the host the request was sent to.
     */
    public static boolean sameOrigin(HttpServer.Request req) {
        String origin = req.header("origin");
        if (origin == null) {
            String ref = req.header("referer");
            if (ref == null) return true; // non-browser client; cookies aren't attached cross-site anyway
            origin = ref;
        }
        if (origin.equals("null")) return false;
        String host = hostOf(origin);
        if (host == null) return false;
        String reqHost = req.header("host");
        String fwd = req.header("x-forwarded-host");
        return host.equalsIgnoreCase(reqHost) || (fwd != null && host.equalsIgnoreCase(fwd));
    }

    private static String hostOf(String url) {
        int s = url.indexOf("://");
        if (s < 0) return null;
        int e = url.indexOf('/', s + 3);
        return e < 0 ? url.substring(s + 3) : url.substring(s + 3, e);
    }

    // ---------------------------------------------------------------- credentials

    /**
     * Verifies credentials with rate limiting. user empty/"admin" means the owner, whose
     * secret is the admin password if set, otherwise the PIN.
     */
    public Result verify(HttpServer.Request req, String user, String secret, String otp, Users.User[] out) {
        if (locked(req)) return Result.LOCKED;
        boolean owner = user == null || user.trim().isEmpty() || user.trim().equalsIgnoreCase(Users.ADMIN);
        Users.User u = null;
        if (owner) {
            boolean ok = Settings.hasAdminPassword(ctx)
                ? Crypto.verifyPassword(secret == null ? "" : secret, Settings.adminPasswordHash(ctx))
                : Crypto.constantTimeEquals(Settings.pin(ctx), secret == null ? "" : secret.trim());
            if (ok) u = Users.OWNER;
        } else {
            u = users.check(user.trim(), secret);
        }
        if (u == null) {
            fail(req);
            return Result.BAD;
        }
        if (owner && otpRequired(req)) {
            if (otp == null || otp.trim().isEmpty()) return Result.OTP_REQUIRED;
            if (!checkOtp(otp)) {
                fail(req);
                return Result.BAD;
            }
        }
        succeed(req);
        out[0] = u;
        return Result.OK;
    }

    private synchronized boolean checkOtp(String code) {
        long step = Crypto.verifyTotp(Settings.totpSecret(ctx), code, System.currentTimeMillis());
        if (step < 0 || step <= Settings.totpLastStep(ctx)) return false;
        Settings.setTotpLastStep(ctx, step);
        return true;
    }

    /** Checks a TOTP code against a not-yet-saved secret (2FA setup). */
    public static boolean checkNewOtp(String secret, String code) {
        return Crypto.verifyTotp(secret, code, System.currentTimeMillis()) >= 0;
    }

    // ---------------------------------------------------------------- endpoints

    /** POST /api/login {user?, password? | pin?, otp?} */
    public void login(HttpServer.Request req, HttpServer.Response res) throws IOException {
        JSONObject b;
        try {
            b = new JSONObject(req.bodyString(4096));
        } catch (Exception e) {
            res.error(400, "bad request");
            return;
        }
        String secret = b.has("password") ? b.optString("password") : b.optString("pin");
        Users.User[] out = new Users.User[1];
        Result r = verify(req, b.optString("user", ""), secret, b.optString("otp", ""), out);
        switch (r) {
            case LOCKED:
                res.header("Retry-After", String.valueOf(lockRemaining(req)));
                res.error(429, "locked");
                return;
            case OTP_REQUIRED:
                res.error(401, "otp_required");
                return;
            case BAD:
                res.error(401, "wrong_credentials");
                return;
            default:
                break;
        }
        // Logging in as owner with the PIN/password counts as a fresh re-authentication.
        String token = sessions.create(out[0].name, out[0].isAdmin());
        setCookie(req, res, token, SessionStore.TTL_MS / 1000);
        res.json(200, "{\"ok\":true}");
    }

    public void logout(HttpServer.Request req, HttpServer.Response res) throws IOException {
        sessions.revoke(req.cookie(COOKIE));
        setCookie(req, res, "", 0);
        res.json(200, "{}");
    }

    /** POST /api/sudo {password|pin, otp?}: re-authenticate for root/admin actions. */
    public void sudo(HttpServer.Request req, HttpServer.Response res, Who who) throws IOException {
        if (!who.user.isAdmin()) {
            res.error(403, "admin only");
            return;
        }
        JSONObject b;
        try {
            b = new JSONObject(req.bodyString(4096));
        } catch (Exception e) {
            res.error(400, "bad request");
            return;
        }
        String secret = b.has("password") ? b.optString("password") : b.optString("pin");
        Users.User[] out = new Users.User[1];
        Result r = verify(req, who.user.name, secret, b.optString("otp", ""), out);
        if (r == Result.LOCKED) {
            res.header("Retry-After", String.valueOf(lockRemaining(req)));
            res.error(429, "locked");
            return;
        }
        if (r == Result.OTP_REQUIRED) {
            res.error(401, "otp_required");
            return;
        }
        if (r != Result.OK) {
            res.error(401, "wrong_credentials");
            return;
        }
        if (who.session != null) {
            sessions.grantSudo(who.session);
        } else {
            // Login is off on the LAN: give the browser a real session to hold the sudo window.
            setCookie(req, res, sessions.create(out[0].name, true), SessionStore.TTL_MS / 1000);
        }
        res.json(200, "{\"ok\":true,\"sudoMs\":" + SessionStore.SUDO_MS + "}");
    }

    public void sessionInfo(HttpServer.Request req, JSONObject o) {
        Who w = who(req);
        try {
            o.put("auth", w != null);
            o.put("pinRequired", Settings.loginRequired(ctx) || req.viaTunnel());
            o.put("adminPassword", Settings.hasAdminPassword(ctx));
            o.put("otp", otpRequired(req));
            o.put("multiUser", users.hasUsers());
            o.put("secure", req.secure || req.viaTunnel());
            if (w != null) {
                o.put("user", w.user.name);
                o.put("role", w.user.role);
                o.put("admin", w.user.isAdmin());
                o.put("canWrite", w.user.canWrite());
                o.put("sudo", w.sudo());
                o.put("loggedIn", w.session != null);
            }
        } catch (Exception ignored) { }
    }

    private void setCookie(HttpServer.Request req, HttpServer.Response res, String token, long maxAge) {
        String secure = req.secure || req.viaTunnel() ? "; Secure" : "";
        res.header("Set-Cookie", COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + maxAge + secure);
    }

    // ---------------------------------------------------------------- WebDAV Basic auth

    /** Authenticates "Authorization: Basic …"; returns null when missing/wrong/locked. */
    public Users.User basic(HttpServer.Request req) {
        String h = req.header("authorization");
        if (h == null || !h.regionMatches(true, 0, "Basic ", 0, 6)) return null;
        String key = Crypto.sha256Hex(h + "|" + req.viaTunnel());
        Object[] c = basicCache.get(key);
        long now = System.currentTimeMillis();
        if (c != null && (Long) c[1] > now) {
            Users.User u = users.find(((Users.User) c[0]).name);
            if (u != null) return u;
        }
        String dec;
        try {
            dec = new String(Crypto.base64Decode(h.substring(6).trim()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
        int colon = dec.indexOf(':');
        if (colon < 0) return null;
        String name = dec.substring(0, colon), pw = dec.substring(colon + 1);
        // 2FA can't be typed into a WebDAV client, so the owner can't use it where 2FA is required.
        boolean owner = name.trim().isEmpty() || name.trim().equalsIgnoreCase(Users.ADMIN);
        if (owner && otpRequired(req)) return null;
        Users.User[] out = new Users.User[1];
        if (verify(req, name, pw, null, out) != Result.OK) return null;
        if (basicCache.size() > 256) basicCache.clear();
        basicCache.put(key, new Object[]{out[0], now + 5 * 60 * 1000});
        return out[0];
    }

    /** Forget cached WebDAV credentials (after password/user changes). */
    public void clearBasicCache() {
        basicCache.clear();
    }

    // ---------------------------------------------------------------- rate limiting

    private static int nowSec() {
        return (int) (System.currentTimeMillis() / 1000);
    }

    public boolean locked(HttpServer.Request req) {
        int now = nowSec();
        if (req.viaTunnel()) {
            synchronized (this) {
                if (tunnelLockUntil > now) return true;
            }
        }
        int[] f = failures.get(req.clientIp());
        return f != null && f[1] > now;
    }

    public int lockRemaining(HttpServer.Request req) {
        int now = nowSec();
        int until = 0;
        int[] f = failures.get(req.clientIp());
        if (f != null) until = f[1];
        if (req.viaTunnel()) synchronized (this) { until = Math.max(until, tunnelLockUntil); }
        return Math.max(1, until - now);
    }

    /** Records a failed attempt (also used by share-link passwords). */
    public void fail(HttpServer.Request req) {
        int now = nowSec();
        String ip = req.clientIp();
        int[] f = failures.get(ip);
        if (f == null) f = new int[3];
        synchronized (f) {
            f[0]++;
            if (f[0] >= MAX_FAILURES) {
                f[0] = 0;
                f[1] = now + (BASE_LOCK_SEC << Math.min(f[2], MAX_LOCK_LEVEL));
                f[2]++;
            }
        }
        failures.put(ip, f);
        if (req.viaTunnel()) {
            synchronized (this) {
                if (now - tunnelWindowStart > TUNNEL_WINDOW_SEC) {
                    tunnelWindowStart = now;
                    tunnelCount = 0;
                }
                if (++tunnelCount >= TUNNEL_MAX_FAILURES) {
                    tunnelLockUntil = now + TUNNEL_WINDOW_SEC;
                    tunnelCount = 0;
                }
            }
        }
        prune(now);
    }

    public void succeed(HttpServer.Request req) {
        failures.remove(req.clientIp());
    }

    /** Clears every lockout (tests; also lets the phone owner unlock after a false alarm). */
    public synchronized void resetLockouts() {
        failures.clear();
        tunnelCount = 0;
        tunnelLockUntil = 0;
        tunnelWindowStart = 0;
    }

    private void prune(int now) {
        if (failures.size() < 2000) return;
        for (Iterator<Map.Entry<String, int[]>> it = failures.entrySet().iterator(); it.hasNext(); ) {
            int[] f = it.next().getValue();
            if (f[1] < now - 3600) it.remove();
        }
    }
}
