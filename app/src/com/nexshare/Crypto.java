package com.nexshare;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Small crypto toolbox: password hashing, TOTP (RFC 6238), base32/base64/hex.
 * Base64 uses android.util.Base64 (java.util.Base64 needs API 26); the JVM tests use a fake.
 */
public final class Crypto {

    private Crypto() { }

    private static final SecureRandom RND = new SecureRandom();
    private static final int PBKDF2_ITERATIONS = 60000;

    // ---------------------------------------------------------------- random

    public static byte[] random(int n) {
        byte[] b = new byte[n];
        RND.nextBytes(b);
        return b;
    }

    public static String randomToken(int bytes) {
        return hex(random(bytes));
    }

    /** URL-safe random id (base32, lowercase), e.g. for share links. */
    public static String randomId(int bytes) {
        return base32Encode(random(bytes)).toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- hashing

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Hex(String s) {
        return hex(sha256(s.getBytes(StandardCharsets.UTF_8)));
    }

    public static byte[] hmac(String algo, byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(algo);
            mac.init(new SecretKeySpec(key, algo));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** Returns "pbkdf2$iterations$saltHex$hashHex". */
    public static String hashPassword(String password) {
        byte[] salt = random(16);
        return "pbkdf2$" + PBKDF2_ITERATIONS + "$" + hex(salt) + "$" + hex(pbkdf2(password, salt, PBKDF2_ITERATIONS));
    }

    public static boolean verifyPassword(String password, String stored) {
        if (password == null || stored == null) return false;
        String[] p = stored.split("\\$");
        if (p.length != 4 || !p[0].equals("pbkdf2")) return false;
        try {
            int it = Integer.parseInt(p[1]);
            byte[] want = unhex(p[3]);
            byte[] got = pbkdf2(password, unhex(p[2]), it);
            return MessageDigest.isEqual(want, got);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            // PBKDF2WithHmacSHA1 is available on every Android version we support (SHA256 needs API 26).
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            return f.generateSecret(new PBEKeySpec(password.toCharArray(), salt, iterations, 256)).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- TOTP

    /** 6-digit TOTP for the given 30-second step. */
    public static String totp(byte[] secret, long step) {
        byte[] msg = new byte[8];
        for (int i = 7; i >= 0; i--) {
            msg[i] = (byte) step;
            step >>>= 8;
        }
        byte[] h = hmac("HmacSHA1", secret, msg);
        int o = h[h.length - 1] & 0x0f;
        int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
        return String.format(Locale.ROOT, "%06d", bin % 1000000);
    }

    /**
     * Checks a code against the current step +/- one (clock drift). Returns the matched
     * step, or -1. Callers reject steps they've already accepted to stop replays.
     */
    public static long verifyTotp(String base32Secret, String code, long nowMs) {
        if (base32Secret == null || code == null) return -1;
        code = code.replaceAll("\\s", "");
        if (!code.matches("\\d{6}")) return -1;
        byte[] secret = base32Decode(base32Secret);
        long step = nowMs / 30000;
        for (long s = step - 1; s <= step + 1; s++) {
            if (constantTimeEquals(totp(secret, s), code)) return s;
        }
        return -1;
    }

    // ---------------------------------------------------------------- encodings

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        }
        return sb.toString();
    }

    public static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    public static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buf = 0, bits = 0;
        for (byte b : data) {
            buf = (buf << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(B32.charAt((buf >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(B32.charAt((buf << (5 - bits)) & 31));
        return sb.toString();
    }

    public static byte[] base32Decode(String s) {
        s = s.replace("=", "").replace(" ", "").toUpperCase(Locale.ROOT);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buf = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            int v = B32.indexOf(s.charAt(i));
            if (v < 0) throw new IllegalArgumentException("bad base32");
            buf = (buf << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.write((buf >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    public static String base64Encode(byte[] d) {
        return android.util.Base64.encodeToString(d, android.util.Base64.NO_WRAP);
    }

    /** Standard base64; throws IllegalArgumentException on malformed input. */
    public static byte[] base64Decode(String s) {
        return android.util.Base64.decode(s, android.util.Base64.DEFAULT);
    }
}
