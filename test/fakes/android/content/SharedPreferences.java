package android.content;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory SharedPreferences fake (a class here; an interface on Android — same call sites). */
public class SharedPreferences {
    final Map<String, Object> map = new ConcurrentHashMap<>();

    public Map<String, ?> getAll() { return new HashMap<>(map); }
    public String getString(String k, String d) { Object v = map.get(k); return v instanceof String ? (String) v : d; }
    public boolean getBoolean(String k, boolean d) { Object v = map.get(k); return v instanceof Boolean ? (Boolean) v : d; }
    public int getInt(String k, int d) { Object v = map.get(k); return v instanceof Integer ? (Integer) v : d; }
    public long getLong(String k, long d) { Object v = map.get(k); return v instanceof Long ? (Long) v : d; }
    public Editor edit() { return new Editor(); }

    public class Editor {
        private final Map<String, Object> puts = new HashMap<>();
        private final java.util.Set<String> removes = new java.util.HashSet<>();
        private boolean clear;
        public Editor putString(String k, String v) { puts.put(k, v); return this; }
        public Editor putBoolean(String k, boolean v) { puts.put(k, v); return this; }
        public Editor putInt(String k, int v) { puts.put(k, v); return this; }
        public Editor putLong(String k, long v) { puts.put(k, v); return this; }
        public Editor remove(String k) { removes.add(k); return this; }
        public Editor clear() { clear = true; return this; }
        public void apply() { commit(); }
        public boolean commit() {
            if (clear) map.clear();
            for (String k : removes) map.remove(k);
            for (Map.Entry<String, Object> e : puts.entrySet()) {
                if (e.getValue() == null) map.remove(e.getKey()); else map.put(e.getKey(), e.getValue());
            }
            return true;
        }
    }
}
