package android.util;

/** JVM stand-in for android.util.Base64 (only the flags NexShare uses). */
public final class Base64 {
    public static final int DEFAULT = 0, NO_WRAP = 2, URL_SAFE = 8;
    public static String encodeToString(byte[] d, int flags) { return java.util.Base64.getEncoder().encodeToString(d); }
    public static byte[] decode(String s, int flags) { return java.util.Base64.getMimeDecoder().decode(s); }
}

