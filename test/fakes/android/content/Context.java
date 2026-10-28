package android.content;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** JVM test fake of android.content.Context: a storage root, private dirs and in-memory prefs. */
public class Context {
    public static final int MODE_PRIVATE = 0;
    private final File root, priv;
    private final Map<String, SharedPreferences> prefs = new HashMap<>();

    public Context(File storageRoot, File privateDir) {
        this.root = storageRoot;
        this.priv = privateDir;
        priv.mkdirs();
        System.setProperty("nexshare.test.root", storageRoot.getPath());
    }

    public Context getApplicationContext() { return this; }
    public synchronized SharedPreferences getSharedPreferences(String name, int mode) {
        SharedPreferences p = prefs.get(name);
        if (p == null) { p = new SharedPreferences(); prefs.put(name, p); }
        return p;
    }
    public File[] getExternalFilesDirs(String type) { return new File[]{new File(root, "Android/data/com.nexshare/files")}; }
    public File getFilesDir() { File f = new File(priv, "files"); f.mkdirs(); return f; }
    public File getCacheDir() { File f = new File(priv, "cache"); f.mkdirs(); return f; }
}
