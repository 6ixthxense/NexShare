package com.nexshare;

/** Test fake: thumbnails need android.graphics, so the JVM tests skip them. */
public final class ThumbCache {
    public ThumbCache(android.content.Context ctx) { }
    public byte[] get(java.io.File f, int size) { return null; }
}
