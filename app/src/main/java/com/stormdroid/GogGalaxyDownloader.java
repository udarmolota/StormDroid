package com.stormdroid;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Inflater;

/**
 * Downloads a game's Windows build from GOG the way the GOG Galaxy client does, through GOG's content
 * system (generation 2), and packs it into a zip in /Download/StormDroid like the Steam download.
 *
 * <p>GOG offers Against the Storm only as Windows installers (Inno Setup executables, no Linux build),
 * which the phone cannot unpack; the content system instead serves the game's files themselves, cut in
 * zlib-compressed chunks. Every request is authorised by the signed-in account's token
 * ({@link GogAuth#bearer}): the CDN links are signed for that account and expire, so nothing can be
 * fetched for a game the account does not own. The DLCs come as depots of their own product; those of
 * a DLC the account does not own are skipped (GOG refuses their link).
 *
 * <p>Flow: builds list → the public build's manifest → its depots (base game and DLCs, language-free
 * and English ones) → each depot's file list → chunks, fetched by 8 workers, inflated, MD5-checked and
 * written at their offsets. Small files live in one container ("small files container") and are cut
 * out of it at the end. A stopped download resumes: finished files are listed in the work dir.
 */
public final class GogGalaxyDownloader implements Runnable, Cancellable {
    private static final String TAG = "StormDroid/GOG";

    /** Against the Storm on GOG. Its DLCs: Keepers of the Stone 1440688316, Nightwatchers 2029824167. */
    public static final long ATS_PRODUCT_ID = 1460447406L;

    private static final String CONTENT = "https://content-system.gog.com";
    private static final String META = "https://gog-cdn-fastly.gog.com/content-system/v2/meta/";
    private static final int WORKERS = 8;
    private static final int TRIES = 6;

    private final long productId;
    private final SteamDownloader.Listener listener;
    private final File workRoot;
    private volatile boolean cancelled;

    /** Secure link per product: its URL template and parameters, renewed when GOG starts refusing it. */
    private final Map<String, JSONObject> links = new HashMap<>();

    public GogGalaxyDownloader(long productId, SteamDownloader.Listener listener) {
        this.productId = productId;
        this.listener = listener;
        this.workRoot = new File(AppStorage.requireSingleton().getHomePath(), "gog_download");
    }

    @Override public void cancel() { cancelled = true; }

    private void progress(String m) {
        Log.i(TAG, m);
        if (listener != null) listener.onProgress(m);
    }

    @Override
    public void run() {
        try {
            download();
        } catch (Throwable t) {
            Log.e(TAG, "GOG download failed", t);
            listener.onDone(cancelled ? "Download cancelled." : "GOG download failed: " + t.getMessage());
        }
    }

    private void download() throws Exception {
        if (!GogAuth.isSignedIn()) {
            listener.onDone("Sign in to GOG first.");
            return;
        }
        progress("GOG: looking up the newest Windows build of product " + productId + "...");
        JSONObject builds = new JSONObject(text(get(CONTENT + "/products/" + productId
                + "/os/windows/builds?generation=2", true)));
        JSONArray items = builds.optJSONArray("items");
        JSONObject build = null;
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject b = items.getJSONObject(i);
            if (b.optInt("generation", 0) == 2 && (b.isNull("branch") || b.optString("branch").isEmpty())) {
                build = b;
                break;
            }
        }
        if (build == null) {
            listener.onDone("GOG has no Windows build of this game for the content system.");
            return;
        }
        String version = build.optString("version_name", build.optString("build_id", "unknown"));
        progress("GOG build " + version + " (" + build.optString("build_id") + ").");
        JSONObject manifest = new JSONObject(text(inflate(get(build.getString("link"), false))));

        // Depots: the game's and its DLCs', the language-free ones and the English ones.
        Map<String, String> productNames = new HashMap<>();
        JSONArray products = manifest.optJSONArray("products");
        for (int i = 0; products != null && i < products.length(); i++) {
            JSONObject p = products.getJSONObject(i);
            productNames.put(p.optString("productId"), p.optString("name"));
        }
        List<JSONObject> depots = new ArrayList<>();
        Set<String> skipped = new HashSet<>();
        JSONArray ds = manifest.getJSONArray("depots");
        for (int i = 0; i < ds.length(); i++) {
            JSONObject d = ds.getJSONObject(i);
            if (!wantedLanguage(d.optJSONArray("languages"))) continue;
            String pid = d.optString("productId");
            if (skipped.contains(pid)) continue;
            if (!pid.equals(String.valueOf(productId)) && link(pid, false) == null) {
                skipped.add(pid);
                progress("Not on this account, skipped: " + productNames.getOrDefault(pid, pid));
                continue;
            }
            depots.add(d);
        }
        if (link(String.valueOf(productId), false) == null) {
            listener.onDone("GOG refused the download: is this game on the signed-in account?");
            return;
        }

        File work = new File(workRoot, String.valueOf(productId));
        if (!work.isDirectory() && !work.mkdirs()) throw new IOException("cannot create " + work);
        File doneList = new File(work, ".stormdroid_gog_complete_" + build.optString("build_id"));
        Set<String> done = readLines(doneList);

        // Every file of every depot; small files point into their depot's container.
        List<FileJob> files = new ArrayList<>();
        List<FileJob> containers = new ArrayList<>();
        long total = 0;
        for (JSONObject d : depots) {
            String pid = d.optString("productId");
            String hash = d.getString("manifest");
            progress("Reading the file list of " + productNames.getOrDefault(pid, pid) + "...");
            JSONObject depot = new JSONObject(text(inflate(get(META + galaxyPath(hash), false)))).getJSONObject("depot");
            JSONArray its = depot.getJSONArray("items");
            FileJob container = null;
            JSONObject sfc = depot.optJSONObject("smallFilesContainer");
            if (sfc != null) {
                container = new FileJob(pid, ".sfc_" + hash, sfc.getJSONArray("chunks"));
                containers.add(container);
                total += container.size;
            }
            for (int i = 0; i < its.length(); i++) {
                JSONObject it = its.getJSONObject(i);
                String path = it.optString("path").replace('\\', '/');
                if (path.isEmpty() || path.contains("../")) continue;
                if ("DepotDirectory".equals(it.optString("type"))) {
                    //noinspection ResultOfMethodCallIgnored
                    new File(work, path).mkdirs();
                    continue;
                }
                if (!"DepotFile".equals(it.optString("type"))) continue;
                FileJob f = new FileJob(pid, path, it.optJSONArray("chunks"));
                JSONObject ref = it.optJSONObject("sfcRef");
                if (ref != null && container != null) {
                    f.container = container;
                    f.offset = ref.getLong("offset");
                    f.size = ref.getLong("size");
                }
                files.add(f);
                if (f.container == null) total += f.size;
            }
        }

        // Only what is not on the phone yet.
        List<FileJob> pending = new ArrayList<>();
        long have = 0;
        for (FileJob f : files) {
            if (f.container == null && done.contains(f.path) && new File(work, f.path).length() == f.size) {
                have += f.size;
                continue;
            }
            if (f.container == null) pending.add(f);
        }
        List<FileJob> neededContainers = new ArrayList<>();
        for (FileJob c : containers) {
            boolean needed = false;
            for (FileJob f : files)
                if (f.container == c && !(done.contains(f.path) && new File(work, f.path).length() == f.size)) needed = true;
            if (needed) pending.add(c);
            if (needed) neededContainers.add(c);
            else have += c.size;
        }
        progress("Downloading " + pending.size() + " files (" + (total >> 20) + " MB in all) with " + WORKERS + " workers...");
        fetch(pending, work, doneList, have, total);
        if (cancelled) {
            listener.onDone("Download cancelled.");
            return;
        }

        // Small files out of their containers.
        for (FileJob f : files) {
            if (f.container == null || !neededContainers.contains(f.container)) continue;
            File out = new File(work, f.path);
            File parent = out.getParentFile();
            if (parent != null) //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            try (RandomAccessFile src = new RandomAccessFile(new File(work, f.container.path), "r");
                 FileOutputStream dst = new FileOutputStream(out)) {
                byte[] buf = new byte[(int) Math.min(f.size, 1 << 20)];
                src.seek(f.offset);
                long left = f.size;
                while (left > 0) {
                    int n = src.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n <= 0) throw new IOException("container too short for " + f.path);
                    dst.write(buf, 0, n);
                    left -= n;
                }
            }
            appendLine(doneList, f.path);
        }
        for (FileJob c : containers) //noinspection ResultOfMethodCallIgnored
            new File(work, c.path).delete();

        //noinspection ResultOfMethodCallIgnored
        doneList.delete();   // the zip carries the game only
        String name = productNames.getOrDefault(String.valueOf(productId), "AgainstTheStorm");
        pack(work, name, version);
    }

    /** Language-free depots and the English ones: the game's own language list covers the rest. */
    private static boolean wantedLanguage(JSONArray langs) {
        if (langs == null || langs.length() == 0) return true;
        for (int i = 0; i < langs.length(); i++) {
            String l = langs.optString(i);
            if (l.equals("*") || l.startsWith("en")) return true;
        }
        return false;
    }

    // ---- chunks -------------------------------------------------------------------------------

    private static final class FileJob {
        final String product, path;
        final JSONArray chunks;
        long size, offset;
        FileJob container;   // set for a small file: its bytes are in the container at offset
        final AtomicInteger chunksLeft = new AtomicInteger();

        FileJob(String product, String path, JSONArray chunks) {
            this.product = product;
            this.path = path;
            this.chunks = chunks != null ? chunks : new JSONArray();
            long s = 0;
            for (int i = 0; i < this.chunks.length(); i++) s += this.chunks.optJSONObject(i).optLong("size");
            this.size = s;
        }
    }

    private static final class ChunkJob {
        final FileJob file;
        final JSONObject chunk;
        final long offset;
        ChunkJob(FileJob file, JSONObject chunk, long offset) { this.file = file; this.chunk = chunk; this.offset = offset; }
    }

    private void fetch(List<FileJob> pending, File work, File doneList, long have, long total) throws Exception {
        ConcurrentLinkedQueue<ChunkJob> queue = new ConcurrentLinkedQueue<>();
        Map<FileJob, RandomAccessFile> open = new HashMap<>();
        for (FileJob f : pending) {
            File out = new File(work, f.path);
            File parent = out.getParentFile();
            if (parent != null) //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            RandomAccessFile raf = new RandomAccessFile(out, "rw");
            raf.setLength(f.size);
            if (f.chunks.length() == 0) {
                raf.close();
                if (f.container == null) appendLine(doneList, f.path);
                continue;
            }
            open.put(f, raf);
            f.chunksLeft.set(f.chunks.length());
            long off = 0;
            for (int i = 0; i < f.chunks.length(); i++) {
                JSONObject c = f.chunks.getJSONObject(i);
                queue.add(new ChunkJob(f, c, off));
                off += c.getLong("size");
            }
        }
        AtomicLong doneBytes = new AtomicLong(have);
        AtomicInteger lastPct = new AtomicInteger(-1);
        AtomicLong lastEmit = new AtomicLong();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(WORKERS);
        for (int w = 0; w < WORKERS; w++) {
            new Thread(() -> {
                try {
                    ChunkJob job;
                    while (!cancelled && error.get() == null && (job = queue.poll()) != null) {
                        byte[] data = chunk(job.file.product, job.chunk);
                        RandomAccessFile raf = open.get(job.file);
                        synchronized (raf) {
                            raf.seek(job.offset);
                            raf.write(data);
                        }
                        long sum = doneBytes.addAndGet(data.length);
                        int pct = total > 0 ? (int) (sum * 100 / total) : 0;
                        long now = System.currentTimeMillis();
                        long prev = lastEmit.get();
                        if (pct != lastPct.get() && now - prev > 500 && lastEmit.compareAndSet(prev, now)) {
                            lastPct.set(pct);
                            progress(pct + "%  (" + (sum >> 20) + " / " + (total >> 20) + " MB)");
                            listener.onPercent(pct);
                        }
                        if (job.file.chunksLeft.decrementAndGet() == 0) {
                            synchronized (raf) { raf.close(); }
                            if (job.file.container == null) appendLine(doneList, job.file.path);
                        }
                    }
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                } finally {
                    latch.countDown();
                }
            }, "sd-gog-dl").start();
        }
        latch.await();
        for (RandomAccessFile raf : open.values()) try { raf.close(); } catch (IOException ignored) { }
        if (error.get() != null) throw new IOException(String.valueOf(error.get().getMessage()), error.get());
    }

    /** One chunk: fetched through the product's secure link, inflated and checked against its MD5. */
    private byte[] chunk(String product, JSONObject c) throws Exception {
        String compressedMd5 = c.getString("compressedMd5");
        Exception last = null;
        for (int t = 0; t < TRIES && !cancelled; t++) {
            try {
                JSONObject link = link(product, t > 0);
                if (link == null) throw new IOException("no download link for product " + product);
                byte[] raw = inflate(get(chunkUrl(link, galaxyPath(compressedMd5)), false));
                if (!hex(MessageDigest.getInstance("MD5").digest(raw)).equals(c.getString("md5")))
                    throw new IOException("chunk " + compressedMd5 + " failed its MD5 check");
                return raw;
            } catch (Exception e) {
                last = e;
                Log.w(TAG, "chunk " + compressedMd5 + " try " + (t + 1) + ": " + e);
                Thread.sleep(Math.min(8000L, 500L << t));
            }
        }
        throw last != null ? last : new IOException("cancelled");
    }

    /**
     * The product's secure link (URL template + parameters), fetched once and renewed on request.
     * Null when GOG refuses it, which is what it does for a product the account does not own.
     */
    private JSONObject link(String product, boolean renew) {
        synchronized (links) {
            if (!renew && links.containsKey(product)) return links.get(product);
            JSONObject best = null;
            try {
                JSONObject j = new JSONObject(text(get(CONTENT + "/products/" + product
                        + "/secure_link?generation=2&_version=2&path=/", true)));
                JSONArray urls = j.getJSONArray("urls");
                for (int i = 0; i < urls.length(); i++) {
                    JSONObject u = urls.getJSONObject(i);
                    if (best == null || u.optInt("priority") > best.optInt("priority")) best = u;
                }
            } catch (Exception e) {
                Log.i(TAG, "no secure link for " + product + ": " + e);
            }
            links.put(product, best);
            return best;
        }
    }

    /** Fills the link's url_format: {base_url}, {path} (with the chunk's path appended) and the rest. */
    private static String chunkUrl(JSONObject link, String chunkPath) throws Exception {
        String url = link.getString("url_format");
        JSONObject params = link.getJSONObject("parameters");
        java.util.Iterator<String> keys = params.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            String v = params.get(k).toString();
            if (k.equals("path")) v = v + "/" + chunkPath;
            url = url.replace("{" + k + "}", v);
        }
        return url;
    }

    /** "abcdef..." -> "ab/cd/abcdef...": how GOG lays out manifests and chunks on its CDN. */
    private static String galaxyPath(String hash) {
        return hash.substring(0, 2) + "/" + hash.substring(2, 4) + "/" + hash;
    }

    // ---- packing ------------------------------------------------------------------------------

    private void pack(File work, String name, String version) {
        File zip = new File(AppStorage.requireSingleton().getDownloadsDir(),
                (name.replaceAll("[^A-Za-z0-9]", "") + "_gog_" + version).replaceAll("[^A-Za-z0-9._-]", "_") + ".zip");
        File part = new File(zip.getPath() + ".part");
        try {
            progress("Download finished. Packing it into " + zip.getAbsolutePath() + " (several GB, a few minutes)...");
            ZipUtil.zipDir(work, part, new ZipUtil.Progress() {
                private int lastPct = -1;
                @Override public void onBytes(long d, long t) {
                    int pct = t > 0 ? (int) (d * 100 / t) : 0;
                    if (pct == lastPct) return;
                    lastPct = pct;
                    progress("Packing: " + pct + "%");
                    listener.onPercent(pct);
                }
                @Override public boolean isCancelled() { return cancelled; }
            });
            if (zip.exists() && !zip.delete()) throw new IOException("cannot replace " + zip.getName());
            if (!part.renameTo(zip)) throw new IOException("cannot rename " + part.getName());
        } catch (Throwable t) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            listener.onDone(t instanceof ZipUtil.CancelledException ? "Download cancelled."
                    : "Packing the game failed: " + t.getMessage());
            return;
        }
        AppStorage.requireSingleton().publishToMediaIndex(zip, "application/zip");
        deleteRecursive(work);
        listener.onGameZip(zip);
        listener.onDone("Downloaded: " + zip.getAbsolutePath() + " (" + (zip.length() >> 20) + " MB)");
    }

    // ---- plumbing -----------------------------------------------------------------------------

    private byte[] get(String url, boolean bearer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", "StormDroid/" + BuildConfig.VERSION_NAME);
            if (bearer) c.setRequestProperty("Authorization", "Bearer " + GogAuth.bearer());
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " from " + url.replaceAll("\\?.*", ""));
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled) throw new IOException("cancelled");
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    /** zlib (what the manifests and chunks are); data that is not zlib comes back as it is. */
    private static byte[] inflate(byte[] data) throws Exception {
        if (data.length < 2 || (data[0] & 0xff) != 0x78) return data;
        Inflater inf = new Inflater();
        try {
            inf.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 4);
            byte[] buf = new byte[1 << 16];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            inf.end();
        }
    }

    private static String text(byte[] b) { return new String(b, StandardCharsets.UTF_8); }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(java.util.Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    private static Set<String> readLines(File f) {
        Set<String> s = new HashSet<>();
        if (!f.isFile()) return s;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            String l;
            while ((l = r.readLine()) != null) if (!l.trim().isEmpty()) s.add(l.trim());
        } catch (IOException ignored) { }
        return s;
    }

    private static synchronized void appendLine(File f, String line) {
        try (java.io.FileWriter w = new java.io.FileWriter(f, true)) {
            w.write(line);
            w.write('\n');
        } catch (IOException ignored) { }
    }

    private static void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
