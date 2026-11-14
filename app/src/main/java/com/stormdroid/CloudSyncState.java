package com.stormdroid;

import android.util.Log;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * What this instance last had in sync with Steam Cloud: one small json per instance, file name to the
 * SHA-1 both sides agreed on (ported from RimDroid). RimDroid decides deletions with it; Against the
 * Storm's saves move as one set (SteamCloudSpike), so here it is only a record of the last sync.
 */
public final class CloudSyncState {

    private static final String TAG = "StormDroid/CloudSync";
    private static final String FILE = "stormdroid_cloud_sync.json";

    /** filename -> SHA-1 (hex) of the copy that both sides agreed on at the last sync. */
    private final Map<String, String> synced;
    private final File file;

    private CloudSyncState(File file, Map<String, String> synced) {
        this.file = file;
        this.synced = synced;
    }

    /** Read the record for an instance (an empty one if there is none yet, or it is unreadable). */
    public static CloudSyncState load(String instanceName) {
        File f = new File(AppStorage.requireSingleton().getInstanceDir(instanceName), FILE);
        Map<String, String> m = new HashMap<>();
        if (f.isFile()) {
            try (java.io.Reader r = new java.io.InputStreamReader(
                    new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8)) {
                java.lang.reflect.Type t =
                        new com.google.gson.reflect.TypeToken<HashMap<String, String>>() {}.getType();
                Map<String, String> parsed = new com.google.gson.Gson().fromJson(r, t);
                if (parsed != null) m.putAll(parsed);
            } catch (Throwable t) {
                // A corrupt record must never block a sync — fall back to "we know nothing",
                // which is the additive-only behaviour, i.e. nothing gets deleted.
                Log.w(TAG, "unreadable sync state, starting fresh: " + t);
            }
        }
        return new CloudSyncState(f, m);
    }

    /** Names this instance is known to have had in sync with the cloud. */
    public Set<String> knownNames() { return synced.keySet(); }

    /** Did we sync this exact content before? */
    public boolean matches(String filename, String sha1Hex) {
        String s = synced.get(filename);
        return s != null && s.equalsIgnoreCase(sha1Hex);
    }

    public void remember(String filename, String sha1Hex) {
        if (filename != null && sha1Hex != null) synced.put(filename, sha1Hex);
    }

    public void forget(String filename) { synced.remove(filename); }

    public void save() {
        try (java.io.Writer w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
            new com.google.gson.Gson().toJson(synced, w);
        } catch (Throwable t) {
            // Best effort: a state we failed to write just means the next sync is additive-only.
            Log.w(TAG, "could not write sync state: " + t);
        }
    }
}
