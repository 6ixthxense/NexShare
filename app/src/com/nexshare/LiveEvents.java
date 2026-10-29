package com.nexshare;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory ring buffer of recent file/clipboard events so connected web clients
 * can auto-refresh File Station and show instant notifications (e.g. when a guest
 * uploads a file through a File Request link or sends text to the shared clipboard).
 */
public final class LiveEvents {

    private static final int MAX_EVENTS = 64;
    private static long nextSeq = 1;
    private static final List<Event> buffer = new ArrayList<>();

    private static final class Event {
        final long seq;
        final long ts;
        final String type;
        final String path;
        final String detail;

        Event(long seq, long ts, String type, String path, String detail) {
            this.seq = seq;
            this.ts = ts;
            this.type = type == null ? "" : type;
            this.path = path == null ? "" : path;
            this.detail = detail == null ? "" : detail;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            Storage.put(o, "seq", seq);
            Storage.put(o, "ts", ts);
            Storage.put(o, "type", type);
            Storage.put(o, "path", path);
            Storage.put(o, "detail", detail);
            return o;
        }
    }

    private LiveEvents() { }

    public static synchronized long publish(String type, String path, String detail) {
        long seq = nextSeq++;
        buffer.add(new Event(seq, System.currentTimeMillis(), type, path, detail));
        while (buffer.size() > MAX_EVENTS) {
            buffer.remove(0);
        }
        return seq;
    }

    public static synchronized long latestSeq() {
        return nextSeq - 1;
    }

    public static synchronized JSONArray since(long afterSeq) {
        JSONArray arr = new JSONArray();
        for (Event e : buffer) {
            if (e.seq > afterSeq) {
                arr.put(e.toJson());
            }
        }
        return arr;
    }

    public static synchronized void resetForTests() {
        buffer.clear();
        nextSeq = 1;
    }
}

