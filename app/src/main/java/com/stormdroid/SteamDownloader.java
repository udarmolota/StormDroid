package com.stormdroid;

import android.util.Log;

import androidx.annotation.NonNull;

import in.dragonbra.javasteam.enums.EDepotFileFlag;
import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.steam.authentication.AuthPollResult;
import in.dragonbra.javasteam.steam.authentication.AuthSessionDetails;
import in.dragonbra.javasteam.steam.authentication.CredentialsAuthSession;
import in.dragonbra.javasteam.steam.authentication.IAuthenticator;
import in.dragonbra.javasteam.steam.authentication.SteamAuthentication;
import in.dragonbra.javasteam.steam.cdn.Client;
import in.dragonbra.javasteam.steam.cdn.Server;
import in.dragonbra.javasteam.steam.handlers.steamapps.License;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSProductInfo;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest;
import in.dragonbra.javasteam.steam.handlers.steamapps.SteamApps;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.LicenseListCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.PICSProductInfoCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.DepotKeyCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.PICSTokensCallback;
import in.dragonbra.javasteam.steam.handlers.steamcontent.CDNAuthToken;
import in.dragonbra.javasteam.steam.handlers.steamcontent.SteamContent;
import in.dragonbra.javasteam.types.AsyncJobMultiple;
import in.dragonbra.javasteam.types.ChunkData;
import in.dragonbra.javasteam.types.DepotManifest;
import in.dragonbra.javasteam.types.FileData;
import in.dragonbra.javasteam.types.KeyValue;
import in.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails;
import in.dragonbra.javasteam.steam.handlers.steamuser.SteamUser;
import in.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback;
import in.dragonbra.javasteam.steam.steamclient.SteamClient;
import in.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager;
import in.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback;
import in.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback;
import in.dragonbra.javasteam.util.log.DefaultLogListener;
import in.dragonbra.javasteam.util.log.LogManager;


import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import kotlinx.coroutines.Deferred;
import kotlinx.coroutines.GlobalScope;

/**
 * In-app Steam download of Against the Storm (ported from ValDroid's SteamDownloadSpike, itself from
 * RimDroid): sign in (credentials, then the Steam Mobile approval push or a Steam Guard code), take the
 * account's licenses, resolve the public build's manifest of the Windows depot, and download every file
 * into a work dir in the app's own storage through a small worker pool over Steam's CDN, resuming a
 * stopped download. The finished game is packed into /Download/StormDroid/AgainstTheStorm_build<id>.zip,
 * the archive the player then creates an instance from (Add instance, preselected); nothing else runs.
 *
 * The login and token are never stored: the token lives in memory for this run only, so a dropped
 * connection can log on again without another approval.
 *
 * Runs on a background thread (blocks in a callback loop).
 */
public class SteamDownloader implements Runnable, Cancellable {

    private static final String TAG = "StormDroid/SteamDL";

    /** Against the Storm on Steam: `DepotDownloader -app 1336490 -depot 1336491` (Windows, the only
     *  content depot; the DLCs unlock content inside it), newest public build. */
    public static final int APP_ID = 1336490;
    private static final int GAME_DEPOT = 1336491;

    private static final int MAX_DOWNLOAD_ATTEMPTS = 3;
    /** Re-tries of the INITIAL sign-in if the CM connection drops mid-approval (Steam-Mobile excursion). */
    private static final int MAX_AUTH_ATTEMPTS = 3;

    public interface Listener {
        /** The finished game archive, to create an instance from. */
        default void onGameZip(File zip) {}
        /** Steam Guard code prompt (only if the account is not approved via the Steam Mobile push). */
        CompletableFuture<String> requestSteamGuardCode(boolean previousWrong, String email);
        /** Human-readable progress line for an on-screen status view (NOT a toast). */
        void onProgress(String message);
        /** Overall depot progress 0..100 (for a determinate progress bar). Default no-op so older
         *  listeners (e.g. the debug button) need not implement it. */
        default void onPercent(int percent) {}
        /** Terminal message (success or error). */
        void onDone(String message);
    }

    // Chunks in flight. The wall clock of a depot download is CDN round-trips, not disk or CPU, so a
    // dozen requests at once multiplies throughput; memory stays at one chunk buffer per worker
    // (about 1 MB). JavaSteam's own DepotDownloader pre-allocates every file and buffers chunks in
    // parallel, and on a 256 MB app heap a multi-GB game ran out of memory (ValDroid, 2026-07-25).
    private static final int MAX_WORKERS = 12;
    /** Steam's CDN edge nodes 503 under load; retry patiently on another server rather than fail. */
    private static final int MAX_CHUNK_TRIES = 30;
    /**
     * One download, packing included, per process: two packs of one build write the same .part file,
     * and the first to finish deletes the work dir under the other (a tester's report, 2026-10-28).
     */
    private static final java.util.concurrent.atomic.AtomicBoolean BUSY =
            new java.util.concurrent.atomic.AtomicBoolean();

    private final String username, password;
    private final String installDir;      // ABSOLUTE work root; each build downloads into <root>/<manifest id>
    private final boolean manifestOnly;
    private final long manifestId;        // 0 = the newest public build; >0 = pin this build
    private final Listener listener;

    private SteamClient steamClient;
    private CallbackManager manager;
    private SteamUser steamUser;
    private List<License> licenseList;

    // In-memory session (never persisted) — reused to re-logon after a reconnect without re-approval.
    private volatile String accountName;
    private volatile String refreshToken;

    private volatile boolean running;
    private volatile Thread workerThread;          // the depot-download thread (interrupted on cancel)
    private volatile boolean cancelled;            // user requested cancel
    private volatile boolean doneEmitted;          // ensure exactly one terminal onDone
    private volatile boolean downloadStarted;     // a download attempt is queued/running this connection
    private volatile boolean downloadInProgress;   // a DepotDownloader is actively running
    private volatile boolean downloadCompleted;    // succeeded — stop everything
    private volatile Throwable lastError;          // set by onDownloadFailed; checked after awaitCompletion
    /** Steam build id of the manifest being downloaded (0 when a manifest id was pinned by hand). */
    private volatile long buildId;
    private int downloadAttempts;
    private int authAttempts;                      // initial-sign-in attempts (drops during approval)

    /** Download Against the Storm and pack it into a zip in /Download/StormDroid. */
    public SteamDownloader(String username, String password, boolean manifestOnly, long manifestId, Listener listener) {
        this.username = username;
        this.password = password;
        // The work dir: the app's own storage (no permission, fast writes; the player never sees a
        // half-downloaded game). The same dir for the same build, so a stopped download resumes.
        this.installDir = new File(AppStorage.requireSingleton().getHomePath(), "game_download").getAbsolutePath();
        this.manifestOnly = manifestOnly;
        this.manifestId = manifestId;
        this.listener = listener;
    }

    private void progress(String m) {
        Log.i(TAG, m);
        if (listener != null) listener.onProgress(m);
    }

    private void done(String m) {
        if (doneEmitted) return;     // exactly one terminal message (cancel + a late retry-finish can race)
        doneEmitted = true;
        Log.i(TAG, "DONE: " + m);
        if (listener != null) listener.onDone(m);
    }

    /** {@link Cancellable}: stop the running download — break the blocking await + the CM loop. */
    @Override
    public void cancel() {
        cancelled = true;
        running = false;
        downloadCompleted = true;            // prevent the disconnect handler from reconnecting/retrying
        Thread w = workerThread;
        if (w != null) w.interrupt();        // break DepotDownloader's awaitCompletion()
        try { if (steamClient != null) steamClient.disconnect(); } catch (Throwable ignored) {}
        // A live worker reports the cancel itself once it has stopped (packing deletes its .part
        // first); until then the screen stays busy, so Download can't start a second one over it.
        if (w == null || !w.isAlive()) done("Download cancelled.");
    }

    @Override
    public void run() {
        try {
            LogManager.addListener(new DefaultLogListener());

            // Default client (same as the proven Milestone-0 auth spike). Depot CDN chunks are
            // downloaded by DepotDownloader's own ktor client, so no custom HTTP config is needed.
            steamClient = new SteamClient();
            manager = new CallbackManager(steamClient);
            steamUser = steamClient.getHandler(SteamUser.class);

            manager.subscribe(ConnectedCallback.class, this::onConnected);
            manager.subscribe(DisconnectedCallback.class, this::onDisconnected);
            manager.subscribe(LoggedOnCallback.class, this::onLoggedOn);
            manager.subscribe(LicenseListCallback.class, this::onLicenseList);

            running = true;
            progress("Tip: stay on ONE network (Wi-Fi or mobile) until the download finishes — "
                    + "switching mid-download can drop the sign-in.");
            progress("Connecting to Steam...");
            steamClient.connect();
            while (running) {
                manager.runWaitCallbacks(1000L);
            }
            Log.i(TAG, "Spike loop ended");
            // The download thread can outlive the loop: a connection dropped while packing ends the
            // loop, but the zip is still being written. The screen stays busy until that thread is done.
            Thread w = workerThread;
            if (w != null) {
                try { w.join(); } catch (InterruptedException ignored) { }
            }
            // Safety net: never leave the UI stuck in "downloading" if the loop ended without a
            // terminal result (e.g. a disconnect path that set running=false but did not call done()).
            if (!doneEmitted) done(cancelled ? "Download cancelled." : "Stopped before finishing — please try again.");
        } catch (Throwable t) {
            Log.e(TAG, "SteamDownloadSpike crashed", t);
            done("crash: " + t);
        } finally {
            // ALWAYS tear the connection down on the way out. Otherwise a failed/finished session
            // (e.g. auth cancelled when the device switched Wi-Fi↔mobile) leaves an ORPHANED
            // SteamClient whose background ktor/reader thread keeps running; when the network then
            // changes its socket dies and the coroutine throws an UNCAUGHT exception on its own
            // thread — which hard-crashes the whole app (not caught by any try/catch here, never
            // logged). Disconnecting here cancels those coroutines cleanly so nothing is left to die.
            try { if (steamClient != null) steamClient.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private void onConnected(ConnectedCallback cb) {
        try {
            // Reconnect path: reuse the in-memory token, no second Steam Mobile approval.
            if (refreshToken != null) {
                progress("Reconnected. Logging on with cached session...");
                logOnWithToken();
                return;
            }

            progress("Connected. Authenticating '" + username + "'...");
            authAttempts++;
            AuthSessionDetails details = new AuthSessionDetails();
            details.username = username;
            details.password = password;
            details.persistentSession = false;          // do NOT persist — ephemeral token, no storage
            details.deviceFriendlyName = "StormDroid";
            details.authenticator = new PushAuthenticator();

            CredentialsAuthSession session =
                    new SteamAuthentication(steamClient).beginAuthSessionViaCredentials(details).get();
            progress("Approve the sign-in in your Steam Mobile app...");

            AuthPollResult poll = session.pollingWaitForResult().get();
            accountName = poll.getAccountName();
            refreshToken = poll.getRefreshToken();       // kept in memory only
            Log.i(TAG, "AUTH OK: account=" + accountName
                    + " tokenLen=" + (refreshToken == null ? 0 : refreshToken.length()));
            logOnWithToken();
        } catch (Throwable t) {
            Log.e(TAG, "Auth failed", t);
            // A CancellationException here = the CM connection DROPPED while we waited for the Steam
            // Mobile approval (e.g. the user briefly switched to the Steam app to approve), NOT a real
            // auth failure (wrong password / declined). Treat it as a transient drop: keep `running`
            // true so onDisconnected reconnects and re-runs the sign-in (a fresh approval push), up to
            // MAX_AUTH_ATTEMPTS. Only give up on a genuine failure or once attempts are exhausted.
            boolean connectionDropped =
                    (t instanceof java.util.concurrent.CancellationException)
                            || (t.getCause() instanceof java.util.concurrent.CancellationException);
            if (connectionDropped && !cancelled && authAttempts < MAX_AUTH_ATTEMPTS) {
                progress("Sign-in interrupted by a connection drop — reconnecting and asking you to "
                        + "approve again (attempt " + authAttempts + "/" + MAX_AUTH_ATTEMPTS + ")…");
                // running stays true → onDisconnected reconnects → onConnected re-runs the sign-in.
            } else {
                done("auth error: " + describe(t));
                running = false;
            }
        }
    }

    private void logOnWithToken() {
        LogOnDetails lod = new LogOnDetails();
        lod.setUsername(accountName);
        lod.setAccessToken(refreshToken);
        lod.setLoginID(149);
        progress("Logging in...");
        steamUser.logOn(lod);
    }

    private void onDisconnected(DisconnectedCallback cb) {
        Log.i(TAG, "Disconnected (userInitiated=" + cb.isUserInitiated() + ")");
        // Stop for good on a user-initiated disconnect, on success, or once retries are exhausted.
        if (cb.isUserInitiated() || downloadCompleted || downloadAttempts >= MAX_DOWNLOAD_ATTEMPTS
                || (refreshToken == null && authAttempts >= MAX_AUTH_ATTEMPTS)) {
            running = false;
            if (!downloadCompleted && !cb.isUserInitiated()) {
                done("connection lost; gave up after " + downloadAttempts + " attempt(s). "
                        + (lastError != null ? describe(lastError) : ""));
            }
            return;
        }
        // Transient drop mid-download — reconnect and let onLicenseList re-trigger the attempt.
        progress("Connection lost — reconnecting (attempt " + (downloadAttempts + 1) + ")...");
        downloadStarted = false;
        try { Thread.sleep(2000L); } catch (InterruptedException ignored) {}
        if (!running) return;   // cancelled while waiting to reconnect
        steamClient.connect();   // → onConnected reuses the cached token (no re-approval)
    }

    private void onLoggedOn(LoggedOnCallback cb) {
        if (cb.getResult() != EResult.OK) {
            done("logOn failed: " + cb.getResult());
            running = false;
            return;
        }
        progress("Logged on. Waiting for license list...");
        // The download starts in onLicenseList (DepotDownloader needs the account's licenses).
    }

    private void onLicenseList(LicenseListCallback cb) {
        if (cb.getResult() != EResult.OK) {
            done("license list failed: " + cb.getResult());
            running = false;
            return;
        }
        licenseList = cb.getLicenseList();
        progress("Got " + (licenseList == null ? 0 : licenseList.size()) + " licenses.");

        // Start exactly one download attempt per (re)connect; never while one is in flight or done.
        if (downloadStarted || downloadInProgress || downloadCompleted) return;
        downloadStarted = true;
        Thread t = new Thread(this::download, "sd-depot-dl");
        workerThread = t;            // tracked so cancel() can interrupt the blocking download
        t.start();
    }

    private void download() {
        if (!BUSY.compareAndSet(false, true)) {
            done("The previous download is still being packed into a zip. Wait for it to finish, then try again.");
            running = false;
            return;
        }
        try {
            downloadOnce();
        } finally {
            BUSY.set(false);
        }
    }

    private void downloadOnce() {
        downloadInProgress = true;
        downloadAttempts++;
        lastError = null;
        // RESUME-FRIENDLY: the work dir is never wiped before a download. runPipeline skips the files a
        // stopped download of the same build already wrote in full (its .stormdroid_complete_ list), so
        // tapping Download again CONTINUES instead of restarting from zero - essential for a multi-GB
        // game (start at home, finish later on another network).
        if (downloadAttempts == 1) {
            String[] partial = new File(installDir).list();
            if (partial != null && partial.length > 0) progress("Found a partial download — resuming where it stopped…");
        }
        // Manifest: an explicit user build wins, otherwise the newest build of the public branch.
        String verLabel = (manifestId > 0) ? ("manifest " + manifestId) : "newest public build";
        progress((manifestOnly ? "[manifest-only] " : "")
                + "[attempt " + downloadAttempts + "] " + verLabel + " → " + installDir);
        try {
            runPipeline();
        } catch (Throwable t) {
            if (!running) {
                done("Download cancelled.");
            } else {
                Log.e(TAG, "download crashed", t);
                lastError = t;
                progress("attempt " + downloadAttempts + " failed: " + describe(t));
                if (downloadAttempts >= MAX_DOWNLOAD_ATTEMPTS) {
                    done("download FAILED after " + downloadAttempts + " attempts: " + describe(t));
                    running = false;
                    try { steamUser.logOff(); } catch (Throwable ignored) {}
                }
            }
        } finally {
            downloadInProgress = false;
        }
    }

    /**
     * Manifest, then every file through a small worker pool.
     *
     * One worker takes one FILE (not one chunk): the game is tens of thousands of files and most are
     * a single chunk, so splitting inside a file would leave the small ones serial anyway, and
     * per-file keeps resume honest — a name is recorded as done only once the whole file is written.
     * Chunk requests round-robin over Steam's CDN servers on every attempt, so the workers spread
     * over the edge nodes instead of hammering one (which is what makes a node start refusing).
     */
    private void runPipeline() throws Exception {
        SteamApps apps = steamClient.getHandler(SteamApps.class);
        SteamContent content = steamClient.getHandler(SteamContent.class);

        long gid = (manifestId > 0) ? manifestId : resolveManifestGid();
        if (gid == 0L) { done("Could not resolve a build to download."); running = false; return; }
        progress("Depot " + GAME_DEPOT + ", manifest " + Long.toUnsignedString(gid)
                + (buildId > 0 ? (", build " + buildId) : ""));

        byte[] depotKey = getDepotKey(apps, GAME_DEPOT, APP_ID);
        if (depotKey == null) { done("No depot key — is Against the Storm owned on this account?"); running = false; return; }

        File zip = new File(AppStorage.requireSingleton().getDownloadsDir(), "AgainstTheStorm_" + readVersionTag(gid) + ".zip");
        if (zip.isFile()) {
            finish(zip, "This build is already downloaded: " + zip.getAbsolutePath());
            return;
        }
        File outDir = new File(installDir, Long.toUnsignedString(gid));
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            done("Cannot create the download folder: " + outDir);
            running = false; return;
        }

        Client cdn = new Client(steamClient);
        List<Server> servers = awaitDeferred(
                content.getServersForSteamPipe(null, null, GlobalScope.INSTANCE), 30000);
        if (servers == null || servers.isEmpty()) { done("No CDN servers available."); running = false; return; }

        long requestCode = awaitDeferred(
                content.getManifestRequestCode(GAME_DEPOT, APP_ID, gid, "public", null, GlobalScope.INSTANCE), 30000);

        Map<String, String> tokenCache = new HashMap<>();
        DepotManifest manifest = null;
        Exception lastErr = null;
        int serverIdx = 0;
        for (int i = 0; i < servers.size(); i++) {
            Server srv = servers.get(i);
            try {
                manifest = cdn.downloadManifestFuture(GAME_DEPOT, gid, requestCode, srv, depotKey, null,
                        cdnTokenFor(content, GAME_DEPOT, srv, tokenCache)).get(120, TimeUnit.SECONDS);
                serverIdx = i;
                break;
            } catch (Exception e) {
                lastErr = e;
                Log.w(TAG, "manifest via " + srv.getHost() + " failed: " + describe(e));
            }
        }
        if (manifest == null) { done("Manifest download failed: " + describe(lastErr)); running = false; return; }

        List<FileData> files = manifest.getFiles();
        long totalBytes = 0;
        for (FileData f : files)
            if (!f.getFlags().contains(EDepotFileFlag.Directory)) totalBytes += f.getTotalSize();
        progress("Manifest OK: " + files.size() + " files, " + (totalBytes / (1024 * 1024)) + " MB.");
        if (manifestOnly) {
            downloadCompleted = true;
            done("Manifest fetched OK (no content downloaded).");
            running = false;
            try { steamUser.logOff(); } catch (Throwable ignored) {}
            return;
        }

        // Resume: names already written in full during an earlier run of this same build.
        File doneListFile = new File(outDir, ".stormdroid_complete_" + GAME_DEPOT + "_" + Long.toUnsignedString(gid));
        Set<String> doneSet = loadDoneSet(doneListFile);

        final AtomicLong doneBytes = new AtomicLong(0);
        final AtomicInteger serverCursor = new AtomicInteger(serverIdx);
        final AtomicInteger lastPct = new AtomicInteger(-1);
        final AtomicLong lastEmit = new AtomicLong(0);
        final AtomicReference<Throwable> firstError = new AtomicReference<>();

        final List<FileData> pending = new ArrayList<>();
        for (FileData f : files) {
            String rel = sanitizeRel(f.getFileName());
            if (rel == null) continue;
            File outFile = new File(outDir, rel);
            if (f.getFlags().contains(EDepotFileFlag.Directory)) { outFile.mkdirs(); continue; }
            if (doneSet.contains(rel) && outFile.isFile() && outFile.length() == f.getTotalSize()) {
                doneBytes.addAndGet(f.getTotalSize());
                continue;
            }
            pending.add(f);
        }
        if (!doneSet.isEmpty())
            progress("Resuming — " + (files.size() - pending.size()) + " files already downloaded.");
        progress("Downloading " + pending.size() + " files with " + MAX_WORKERS + " workers…");

        final long fTotal = totalBytes;
        final byte[] fKey = depotKey;
        final List<Server> fServers = servers;
        final Client fCdn = cdn;
        final SteamContent fContent = content;
        final Map<String, String> fTokens = tokenCache;
        final File fOutDir = outDir, fDoneList = doneListFile;

        final java.util.concurrent.ConcurrentLinkedQueue<FileData> queue =
                new java.util.concurrent.ConcurrentLinkedQueue<>(pending);
        final java.util.concurrent.ConcurrentLinkedQueue<ChunkJob> chunks =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        final Set<OpenFile> open = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final AtomicInteger opening = new AtomicInteger(0);
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(MAX_WORKERS);
        final List<Thread> pool = new ArrayList<>(MAX_WORKERS);
        for (int w = 0; w < MAX_WORKERS; w++) {
            Thread t = new Thread(() -> {
                try {
                    while (running && firstError.get() == null) {
                        ChunkJob job = chunks.poll();
                        if (job != null) {
                            downloadChunk(job, open, fDoneList, fCdn, fContent, fServers, fKey, fTokens,
                                    serverCursor, doneBytes, fTotal, lastPct, lastEmit);
                            continue;
                        }
                        // Counted before the poll: a file being opened still brings chunks, and a
                        // worker leaving now would leave a big last file to fewer connections.
                        opening.incrementAndGet();
                        FileData f = queue.poll();
                        if (f != null) {
                            try {
                                openFile(f, fOutDir, fDoneList, chunks, open);
                            } finally {
                                opening.decrementAndGet();
                            }
                            continue;
                        }
                        if (opening.decrementAndGet() == 0 && chunks.isEmpty()) return;
                        Thread.sleep(20);
                    }
                } catch (InterruptedException cancelled) {
                    // The running flag says so.
                } catch (Throwable e) {
                    firstError.compareAndSet(null, e);
                } finally {
                    latch.countDown();
                }
            }, "sd-depot-dl-" + w);
            t.setDaemon(true);
            pool.add(t);
            t.start();
        }
        try {
            latch.await();
        } catch (InterruptedException cancelled) {
            running = false;
            for (Thread t : pool) t.interrupt();
            boolean drained = false;
            while (!drained) {
                try { latch.await(); drained = true; } catch (InterruptedException ignored) { }
            }
        }
        for (OpenFile o : open) o.close();   // what a failure or a cancel left half written
        if (firstError.get() != null) throw new Exception(describe(firstError.get()), firstError.get());
        if (!running) { done("Download cancelled."); return; }

        downloadCompleted = true;
        pack(outDir, zip);
    }

    /** The public branch's manifest for the game depot, from PICS; also records its build id. */
    private long resolveManifestGid() {
        try {
            SteamApps apps = steamClient.getHandler(SteamApps.class);
            long token = 0L;
            try {
                PICSTokensCallback tk = apps.picsGetAccessTokens(List.of(APP_ID), Collections.emptyList())
                        .toFuture().get(20, TimeUnit.SECONDS);
                Long t = tk.getAppTokens().get(APP_ID);
                if (t != null) token = t;
            } catch (Throwable ignored) { }
            AsyncJobMultiple.ResultSet<PICSProductInfoCallback> rs =
                    apps.picsGetProductInfo(List.of(new PICSRequest(APP_ID, token)), Collections.emptyList())
                            .toFuture().get(40, TimeUnit.SECONDS);
            for (PICSProductInfoCallback cb : rs.getResults()) {
                PICSProductInfo info = cb.getApps().get(APP_ID);
                if (info == null) continue;
                KeyValue depots = info.getKeyValues().get("depots");
                buildId = depots.get("branches").get("public").get("buildid").asLong(0L);
                KeyValue depot = depots.get(String.valueOf(GAME_DEPOT));
                String gid = depot.get("manifests").get("public").get("gid").asString();
                if (gid != null && !gid.isEmpty()) return Long.parseUnsignedLong(gid);
            }
        } catch (Throwable t) {
            Log.w(TAG, "resolveManifestGid failed", t);
        }
        return 0L;
    }

    private byte[] getDepotKey(SteamApps apps, int depot, int appId) {
        try {
            DepotKeyCallback dk = apps.getDepotDecryptionKey(depot, appId).toFuture().get(30, TimeUnit.SECONDS);
            if (dk.getResult() == EResult.OK && dk.getDepotKey() != null && dk.getDepotKey().length == 32)
                return dk.getDepotKey();
            Log.w(TAG, "depot key result=" + dk.getResult());
        } catch (Throwable t) {
            Log.e(TAG, "getDepotKey failed", t);
        }
        return null;
    }

    /** A file being written: open until its last chunk is in, so only a few are open at a time. */
    private static final class OpenFile {
        final String rel;
        final RandomAccessFile raf;
        final AtomicInteger chunksLeft;
        OpenFile(String rel, RandomAccessFile raf, int chunks) {
            this.rel = rel; this.raf = raf; this.chunksLeft = new AtomicInteger(chunks);
        }
        void close() {
            try { raf.close(); } catch (Exception ignored) { }
        }
    }

    private static final class ChunkJob {
        final OpenFile file;
        final ChunkData chunk;
        ChunkJob(OpenFile file, ChunkData chunk) { this.file = file; this.chunk = chunk; }
    }

    /** Sizes the file on disk and queues its chunks. */
    private void openFile(FileData f, File outDir, File doneListFile,
                          java.util.Queue<ChunkJob> chunks, Set<OpenFile> open) throws Exception {
        String rel = sanitizeRel(f.getFileName());
        if (rel == null) return;
        File outFile = new File(outDir, rel);
        File parent = outFile.getParentFile();
        if (parent != null) parent.mkdirs();

        RandomAccessFile raf = new RandomAccessFile(outFile, "rw");
        raf.setLength(f.getTotalSize());
        List<ChunkData> list = f.getChunks();
        if (list.isEmpty()) {
            try { raf.close(); } catch (Exception ignored) { }
            appendDone(doneListFile, rel);
            return;
        }
        OpenFile o = new OpenFile(rel, raf, list.size());
        open.add(o);
        for (ChunkData c : list) chunks.add(new ChunkJob(o, c));
    }

    /** One chunk, written at its offset. Runs on a pool thread. */
    private void downloadChunk(ChunkJob job, Set<OpenFile> open, File doneListFile, Client cdn,
                               SteamContent content, List<Server> servers, byte[] depotKey,
                               Map<String, String> tokenCache, AtomicInteger serverCursor,
                               AtomicLong doneBytes, long totalBytes,
                               AtomicInteger lastPct, AtomicLong lastEmit) throws Exception {
        ChunkData chunk = job.chunk;
        byte[] dest = new byte[Math.max(chunk.getCompressedLength(), chunk.getUncompressedLength())];
        int written = -1;
        Exception chunkErr = null;
        for (int t = 0; t < MAX_CHUNK_TRIES && written < 0; t++) {
            if (!running) return;
            // Round-robin per attempt: sticky servers would point every worker at one edge
            // node, and a retry would land on the node that just failed.
            Server srv = servers.get(Math.floorMod(serverCursor.getAndIncrement(), servers.size()));
            try {
                written = cdn.downloadDepotChunkFuture(GAME_DEPOT, chunk, srv, dest, depotKey, null,
                        cdnTokenFor(content, GAME_DEPOT, srv, tokenCache)).get(120, TimeUnit.SECONDS);
            } catch (Exception e) {
                chunkErr = e;
                Log.w(TAG, "chunk via " + srv.getHost() + " failed (try " + (t + 1) + "/"
                        + MAX_CHUNK_TRIES + "): " + describe(e));
                long now = System.currentTimeMillis();
                long prev = lastEmit.get();
                if (now - prev > 1500 && lastEmit.compareAndSet(prev, now))
                    progress("Steam CDN busy — retrying… (" + (doneBytes.get() / (1024 * 1024))
                            + " / " + (totalBytes / (1024 * 1024)) + " MB)");
                long backoff = Math.min(10000L, 500L * (1L << Math.min(t, 4)));
                try { Thread.sleep(backoff); } catch (InterruptedException ignored) { return; }
            }
        }
        if (written < 0) throw chunkErr != null ? chunkErr : new java.io.IOException("chunk download failed");
        // Positional: other workers write other chunks of this file at the same time.
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(dest, 0, written);
        long pos = chunk.getOffset();
        while (buf.hasRemaining()) pos += job.file.raf.getChannel().write(buf, pos);
        long total = doneBytes.addAndGet(written);

        long now = System.currentTimeMillis();
        int pct = totalBytes > 0 ? (int) (total * 100 / totalBytes) : 0;
        long prev = lastEmit.get();
        if (pct != lastPct.get() && now - prev > 500 && lastEmit.compareAndSet(prev, now)) {
            lastPct.set(pct);
            progress(pct + "%  (" + (total / (1024 * 1024)) + " / " + (totalBytes / (1024 * 1024)) + " MB)");
            if (listener != null) listener.onPercent(pct);
        }

        if (job.file.chunksLeft.decrementAndGet() == 0) {
            job.file.close();
            open.remove(job.file);
            if (running) appendDone(doneListFile, job.file.rel);
        }
    }

    private String cdnTokenFor(SteamContent content, int depot, Server s, Map<String, String> cache) {
        String host = s.getHost() != null ? s.getHost() : s.getVHost();
        if (host == null) return null;
        // Guarded rather than a ConcurrentHashMap: a null token is a real cached answer, and that map
        // forbids null values. The lock is held across the fetch on purpose — every worker wants the
        // same host's token at the same moment, and one request beats six identical ones.
        synchronized (cache) {
            if (cache.containsKey(host)) return cache.get(host);
            String token = null;
            try {
                CDNAuthToken tok = awaitDeferred(content.getCDNAuthToken(APP_ID, depot, host, GlobalScope.INSTANCE), 15000);
                if (tok != null && tok.getResult() == EResult.OK) token = tok.getToken();
            } catch (Throwable ignored) { }
            cache.put(host, token);
            return token;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T awaitDeferred(Deferred<T> d, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!d.isCompleted()) {
            if (System.currentTimeMillis() > deadline) throw new TimeoutException("deferred timed out");
            Thread.sleep(40);
        }
        return (T) d.getCompleted();
    }

    /** Relative paths already written in full (the resume marker). */
    private static Set<String> loadDoneSet(File f) {
        Set<String> out = new HashSet<>();
        if (f == null || !f.isFile()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) out.add(line);
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** Several workers finish files at once, so appending is synchronized and flushed at once. */
    private static synchronized void appendDone(File f, String rel) {
        try (FileWriter w = new FileWriter(f, true)) {
            w.write(rel);
            w.write('\n');
        } catch (Exception ignored) { }
    }

    private static String sanitizeRel(String name) {
        if (name == null) return null;
        String rel = name.replace('\\', '/');
        while (rel.startsWith("/")) rel = rel.substring(1);
        if (rel.isEmpty() || rel.contains("../")) return null;
        return rel;
    }

    /**
     * The downloaded game -> its zip in /Download/StormDroid, the archive an instance is created from
     * (written under a temporary name, so a half-written archive never passes for a finished one). The
     * work dir goes once the zip is complete.
     */
    private void pack(File work, File zip) {
        if (!new File(work, com.stormdroid.game.AtsGame.EXE).isFile()) {
            finish(null, "Download failed: no Against the Storm game files in it.");
            return;
        }
        File part = new File(zip.getPath() + ".part");
        try {
            progress("Download finished. Packing it into " + zip.getAbsolutePath()
                    + " (several GB, a few minutes; keep the app open)...");
            ZipUtil.zipDir(work, part, new ZipUtil.Progress() {
                private int lastPct = -1;
                private long lastEmit;

                @Override
                public void onBytes(long done, long total) {
                    int pct = total > 0 ? (int) (done * 100 / total) : 0;
                    long now = System.currentTimeMillis();
                    if (pct == lastPct || now - lastEmit < 500) return;
                    lastPct = pct;
                    lastEmit = now;
                    progress("Packing: " + pct + "%  (" + (done / (1024 * 1024)) + " / "
                            + (total / (1024 * 1024)) + " MB)");
                    if (listener != null) listener.onPercent(pct);
                }

                @Override
                public boolean isCancelled() { return cancelled; }
            });
            if (zip.exists() && !zip.delete()) throw new java.io.IOException("cannot replace " + zip.getName());
            if (!part.renameTo(zip)) throw new java.io.IOException("cannot rename " + part.getName());
        } catch (Throwable t) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            if (t instanceof ZipUtil.CancelledException) {
                // The downloaded files stay: Download again packs them without fetching anything.
                finish(null, "Download cancelled.");
                return;
            }
            Log.e(TAG, "packing the game failed", t);
            finish(null, "Packing the game failed: " + describe(t));
            return;
        }
        // The system file picker lists only what the media index knows.
        AppStorage.requireSingleton().publishToMediaIndex(zip, "application/zip");
        deleteRecursive(work);
        finish(zip, "Downloaded: " + zip.getAbsolutePath() + " (" + (zip.length() / (1024 * 1024)) + " MB)");
    }

    /** Terminal step: hand over the zip (if any), report, sign off. */
    private void finish(File zip, String message) {
        downloadInProgress = false;
        downloadCompleted = true;
        if (zip != null && listener != null) listener.onGameZip(zip);
        done(message);
        running = false;
        try { steamUser.logOff(); } catch (Throwable ignored) {}
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** The archive's version tag: Steam's build id of the branch, else the manifest id. */
    private String readVersionTag(long gid) {
        if (buildId > 0) return "build" + buildId;
        return "manifest" + Long.toUnsignedString(gid);
    }

    /** Exception class + message + first useful cause/frame — getMessage() alone is often null. */
    private static String describe(Throwable t) {
        if (t == null) return "null";
        StringBuilder sb = new StringBuilder(t.getClass().getSimpleName());
        if (t.getMessage() != null) sb.append(": ").append(t.getMessage());
        Throwable c = t.getCause();
        if (c != null && c != t) {
            sb.append("  <- ").append(c.getClass().getSimpleName());
            if (c.getMessage() != null) sb.append(": ").append(c.getMessage());
        }
        StackTraceElement[] st = t.getStackTrace();
        if (st != null && st.length > 0) sb.append("  @ ").append(st[0]);
        return sb.toString();
    }

    /** Approves via Steam Mobile push when possible; otherwise asks the UI for a Steam Guard code. */
    private class PushAuthenticator implements IAuthenticator {
        @Override
        public CompletableFuture<Boolean> acceptDeviceConfirmation() {
            Log.i(TAG, "acceptDeviceConfirmation -> true (Steam Mobile approval push)");
            return CompletableFuture.completedFuture(Boolean.TRUE);
        }
        @Override
        public CompletableFuture<String> getDeviceCode(boolean previousCodeWasIncorrect) {
            return listener != null
                    ? listener.requestSteamGuardCode(previousCodeWasIncorrect, null)
                    : CompletableFuture.completedFuture("");
        }
        @Override
        public CompletableFuture<String> getEmailCode(String email, boolean previousCodeWasIncorrect) {
            return listener != null
                    ? listener.requestSteamGuardCode(previousCodeWasIncorrect, email)
                    : CompletableFuture.completedFuture("");
        }
    }
}
