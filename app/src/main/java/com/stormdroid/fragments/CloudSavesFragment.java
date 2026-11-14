package com.stormdroid.fragments;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.stormdroid.AppStorage;
import com.stormdroid.R;
import com.stormdroid.SteamCloudSpike;
import com.stormdroid.SteamDownloader;
import com.stormdroid.game.GameInstance;
import com.stormdroid.game.GameInstanceManager;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Steam Cloud saves — move saves between an instance on the phone and the copy Steam keeps in sync
 * with a PC. Sits beside "Steam Downloads" in the drawer and shares its session model: credentials
 * plus a Steam-Mobile approval, token in memory only, never stored.
 *
 * Ported from RimDroid. Against the Storm's saves are one set (progress, world, settlement), so they
 * move together and are asked about once: getting saves fetches every cloud save into a scratch folder
 * and hangs up, then (offline, unhurried) replaces the phone's set after one question, the old set
 * kept in a backup folder; sending replaces the cloud's set after one question. Choosing files up front
 * would mean holding the Steam session open while a human deliberates (the connection dies if the app
 * is backgrounded) and a second sign-in approval.
 */
public class CloudSavesFragment extends Fragment {

    /** Against the Storm's Steam app id. */
    private static final int APP_ID = 1336490;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextInputEditText etUser, etPass;
    private Spinner spInstance;
    private int instanceOffset;   // spinner position of the first instance (a prompt comes first with several)
    private Button btnGo;
    private TextView goNote, log;
    private ProgressBar progress;

    private List<GameInstance> instances;
    private boolean pullDirection = true;   // true = from cloud, false = to cloud

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_cloud_saves, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        etUser = view.findViewById(R.id.et_cloud_user);
        etPass = view.findViewById(R.id.et_cloud_pass);
        spInstance = view.findViewById(R.id.sp_cloud_instance);
        btnGo = view.findViewById(R.id.btn_cloud_go);
        goNote = view.findViewById(R.id.tv_cloud_go_note);
        progress = view.findViewById(R.id.pb_cloud);
        log = view.findViewById(R.id.tv_cloud_log);
        log.setMovementMethod(new ScrollingMovementMethod());

        // With several instances the picker starts on a prompt: a pre-selected default would let the
        // player move saves in or out of the wrong game without ever touching it. One is preselected.
        instances = GameInstanceManager.requireSingleton().getInstances();
        instanceOffset = com.stormdroid.InstanceSpinner.fill(spInstance, instances,
                getString(R.string.cloud_saves_no_instances));

        MaterialButtonToggleGroup toggle = view.findViewById(R.id.toggle_cloud_dir);
        toggle.check(R.id.btn_dir_pull);
        toggle.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked) return;
            pullDirection = (checkedId == R.id.btn_dir_pull);
            btnGo.setText(pullDirection ? R.string.cloud_saves_pull : R.string.cloud_saves_push);
            goNote.setText(pullDirection ? R.string.cloud_saves_pull_note : R.string.cloud_saves_push_note);
        });
        btnGo.setEnabled(true);
        btnGo.setOnClickListener(v -> onGo());
    }

    private GameInstance chosenInstance() {
        return com.stormdroid.InstanceSpinner.selected(spInstance, instances, instanceOffset);
    }

    private File savesDirOf(GameInstance gi) {
        return SteamCloudSpike.savesDir(gi.getName());
    }

    /** Scratch folder the cloud copy lands in before anything touches the real saves. */
    private File pullTempDir(GameInstance gi) {
        return new File(AppStorage.requireSingleton().getCachePath(), "cloud_pull/" + gi.getName());
    }

    private void onGo() {
        GameInstance gi = chosenInstance();
        if (gi == null) {
            Toast.makeText(requireContext(), R.string.choose_instance_first, Toast.LENGTH_SHORT).show();
            return;
        }
        String u = text(etUser), p = text(etPass);
        if (u.isEmpty() || p.isEmpty()) {
            Toast.makeText(requireContext(), R.string.cloud_saves_need_login, Toast.LENGTH_SHORT).show();
            return;
        }
        busy(true);
        log.setText("");
        if (pullDirection) {
            final File temp = pullTempDir(gi);
            appendLog(getString(R.string.cloud_saves_log_connecting));
            new Thread(SteamCloudSpike.forPull(u, p, APP_ID, temp, savesDirOf(gi), new Callbacks() {
                @Override public void onDone(String message) {
                    ui.post(() -> {
                        appendLog("— " + message);
                        // Connection is closed by now: place the files at leisure.
                        placeAll(temp, savesDirOf(gi));
                    });
                }
            }), "CloudSavesPull").start();
        } else {
            appendLog(getString(R.string.cloud_saves_log_connecting));
            new Thread(SteamCloudSpike.forPush(u, p, APP_ID, gi.getName(), new Callbacks()),
                    "CloudSavesPush").start();
        }
    }

    // ===== placement: runs offline, after the Steam session is gone =====

    /**
     * Replace the phone's saves with the downloaded set, asking once when the phone has saves of its
     * own. The phone's set goes to a backup folder first; saves the cloud does not have are removed with
     * it, so the game never mixes the cloud's progress with an old settlement of the phone's.
     */
    private void placeAll(File temp, File savesDir) {
        List<File> pending = new ArrayList<>();
        File[] fs = temp.listFiles();
        if (fs != null) for (File f : fs) pending.add(f);
        if (pending.isEmpty()) { busy(false); return; }
        if (!savesDir.isDirectory() && !savesDir.mkdirs()) {
            appendLog("Cannot create " + savesDir);
            busy(false);
            return;
        }
        List<File> local = new ArrayList<>();
        File[] ls = savesDir.listFiles((d, n) -> SteamCloudSpike.isSave(n));
        if (ls != null) for (File f : ls) local.add(f);
        if (local.isEmpty()) {
            replaceSet(pending, local, savesDir);
            return;
        }
        long phone = 0, cloud = 0;
        for (File f : local) phone = Math.max(phone, f.lastModified());
        for (File f : pending) cloud = Math.max(cloud, f.lastModified());
        String msg = getString(R.string.cloud_saves_replace_msg, fmtDate(phone), fmtDate(cloud),
                getString(cloud > phone ? R.string.cloud_saves_clash_cloud_newer
                                        : R.string.cloud_saves_clash_local_newer));
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.cloud_saves_replace_title)
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton(R.string.cloud_saves_clash_replace, (d, w) -> replaceSet(pending, local, savesDir))
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    appendLog(getString(R.string.cloud_saves_replace_cancelled));
                    for (File f : pending) f.delete();
                    busy(false);
                })
                .show();
    }

    private void replaceSet(List<File> pending, List<File> local, File savesDir) {
        GameInstance gi = chosenInstance();
        com.stormdroid.CloudSyncState state = gi == null ? null : com.stormdroid.CloudSyncState.load(gi.getName());
        java.util.Set<String> incoming = new java.util.HashSet<>();
        for (File f : pending) incoming.add(f.getName());
        if (!local.isEmpty()) {
            File backup = new File(savesDir, "cloud_backup/" + new java.text.SimpleDateFormat(
                    "yyyyMMdd_HHmmss", java.util.Locale.US).format(new Date()));
            if (!backup.mkdirs()) {
                appendLog("Cannot create " + backup + ": nothing was replaced.");
                busy(false);
                return;
            }
            for (File f : local) {
                File to = new File(backup, f.getName());
                // Saves the cloud does not have move into the backup; the others are copied there.
                boolean ok = incoming.contains(f.getName()) ? copyFile(f, to) : f.renameTo(to);
                if (!ok) {
                    appendLog("Cannot back up " + f.getName() + ": nothing was replaced.");
                    busy(false);
                    return;
                }
            }
            appendLog(getString(R.string.cloud_saves_backup_done, backup.getAbsolutePath()));
        }
        int[] tally = {0, 0};
        for (File src : pending) copyInto(src, new File(savesDir, src.getName()), tally, state);
        if (state != null) state.save();
        appendLog(getString(R.string.cloud_saves_place_done, tally[0], tally[1]));
        busy(false);
    }

    private static boolean copyFile(File src, File dest) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            dest.setLastModified(src.lastModified());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Copy one downloaded save into place. Passing {@code state} records that this name now holds
     *  exactly what the cloud holds. */
    private void copyInto(File src, File dest, int[] tally, com.stormdroid.CloudSyncState state) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            dest.setLastModified(src.lastModified());   // keep the cloud's date, for later comparisons
            tally[0]++;
            appendLog(getString(R.string.cloud_saves_log_placed, dest.getName()));
            if (state != null) state.remember(dest.getName(), SteamCloudSpike.sha1Hex(dest));
            src.delete();
        } catch (Throwable t) {
            appendLog("FAILED " + dest.getName() + ": " + t);
        }
    }

    // ===== shared Steam plumbing =====

    private class Callbacks implements SteamCloudSpike.CloudListener {
        @Override public void onFileList(List<SteamCloudSpike.CloudFile> files) { /* unused */ }

        /** Sending would overwrite these on the PC — ask once, naming them with both dates. */
        @Override
        public CompletableFuture<Integer> resolvePushConflicts(List<SteamCloudSpike.CloudFile> clashes) {
            final CompletableFuture<Integer> fut = new CompletableFuture<>();
            ui.post(() -> {
                if (!isAdded()) { fut.complete(SteamCloudSpike.PUSH_CANCEL); return; }
                GameInstance gi = chosenInstance();
                File savesDir = gi == null ? null : savesDirOf(gi);
                StringBuilder sb = new StringBuilder();
                for (SteamCloudSpike.CloudFile cf : clashes) {
                    File local = savesDir == null ? null : new File(savesDir, cf.filename);
                    sb.append("\n• ").append(cf.filename)
                      .append("\n   ").append(getString(R.string.cloud_saves_clash_in_cloud,
                              fmtDate(cf.timestampMs)));
                    if (local != null && local.isFile())
                        sb.append("\n   ").append(getString(R.string.cloud_saves_clash_on_phone,
                                fmtDate(local.lastModified())));
                }
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.cloud_saves_push_confirm_title)
                        .setMessage(getString(R.string.cloud_saves_push_confirm_replace, sb.toString()))
                        .setCancelable(false)
                        .setPositiveButton(R.string.cloud_saves_push_replace,
                                (d, w) -> fut.complete(SteamCloudSpike.PUSH_REPLACE))
                        .setNegativeButton(android.R.string.cancel,
                                (d, w) -> fut.complete(SteamCloudSpike.PUSH_CANCEL))
                        .show();
            });
            return fut;
        }

        @Override
        public CompletableFuture<String> requestSteamGuardCode(boolean prevWrong, String email) {
            final CompletableFuture<String> fut = new CompletableFuture<>();
            ui.post(() -> {
                if (!isAdded()) { fut.complete(""); return; }
                final android.widget.EditText et = new android.widget.EditText(requireContext());
                et.setHint(email != null ? getString(R.string.cloud_saves_code_email, email)
                                         : getString(R.string.cloud_saves_code));
                et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(prevWrong ? R.string.cloud_saves_code_wrong : R.string.cloud_saves_code_title)
                        .setView(et)
                        .setCancelable(false)
                        .setPositiveButton(android.R.string.ok,
                                (d, w) -> fut.complete(et.getText().toString().trim()))
                        .show();
            });
            return fut;
        }

        @Override public void onProgress(String message) { ui.post(() -> appendLog(message)); }

        @Override public void onDone(String message) {
            ui.post(() -> { appendLog("— " + message); busy(false); });
        }
    }

    private void busy(boolean b) {
        if (!isAdded()) return;
        progress.setVisibility(b ? View.VISIBLE : View.GONE);
        btnGo.setEnabled(!b);
        // Hold the process at foreground-service priority for the whole operation. Android freezes a
        // backgrounded app and kills its Steam connection mid-transfer — the failure the game
        // downloader already hit — and signing in deliberately sends the user to Steam Mobile.
        android.content.Context appCtx = requireContext().getApplicationContext();
        if (b) com.stormdroid.DownloadKeepAliveService.start(appCtx, getString(R.string.cloud_saves_keepalive));
        else com.stormdroid.DownloadKeepAliveService.stop(appCtx);
    }

    private String text(TextInputEditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    private String fmtDate(long ms) {
        if (ms <= 0) return "?";
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(ms));
    }

    /** Append a line and keep the console scrolled to the bottom (so it never looks frozen). */
    private void appendLog(String line) {
        if (!isAdded() || log == null) return;
        log.append(line + "\n");
        final int scroll = log.getLayout() == null ? 0
                : log.getLayout().getLineTop(log.getLineCount()) - log.getHeight();
        if (scroll > 0) log.scrollTo(0, scroll);
    }
}
