package com.stormdroid.fragments;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.stormdroid.InstallerService;
import com.stormdroid.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** "Add instance": pick the game's ZIP (its Windows build), name the instance, install. */
public class NewInstanceFragment extends Fragment {

    /**
     * Optional navigation argument: absolute path of an archive to install, chosen already (the game
     * the Steam download packed). It goes to the installer by path, without the copy into the cache a
     * picked document needs.
     */
    public static final String ARG_PRESELECTED_FILE = "preselected_file";
    private File preselectedFile;

    // The instance name is a directory name inside every game path; keep it short (long paths have
    // bitten native code before) and leave a margin for work-profile user dirs (/data/user/<n>/...).
    private static final int MAX_NAME_LEN = 40;

    private EditText etInstanceName;
    private Button   btnPickZip;
    private Button   btnInstall;
    private TextView tvSelectedZip;
    private ProgressBar progressInstall;
    private TextView tvInstallStatus;

    private Uri selectedZipUri;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver installerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (InstallerService.BROADCAST_PROGRESS.equals(action)) {
                showProgress(intent.getStringExtra(InstallerService.EXTRA_MESSAGE),
                        intent.getIntExtra(InstallerService.EXTRA_PERCENT, -1));
            } else if (InstallerService.BROADCAST_DONE.equals(action)) {
                leave();
            } else if (InstallerService.BROADCAST_ERROR.equals(action)) {
                mainHandler.post(() -> {
                    btnInstall.setEnabled(true);
                    btnInstall.setText(R.string.install);
                    hideProgress();
                    String msg = intent.getStringExtra(InstallerService.EXTRA_MESSAGE);
                    etInstanceName.setError(msg != null ? msg : getString(R.string.error_name_required));
                });
            }
        }
    };

    private final ActivityResultLauncher<String[]> zipPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                selectedZipUri = uri;
                preselectedFile = null;
                tvSelectedZip.setText(uri.getLastPathSegment());
                // Deliberately do NOT auto-fill the name from the zip filename: repack zips carry very
                // long names. The field is pre-filled with a short free default in onViewCreated.
                if (etInstanceName.getText().toString().trim().isEmpty()) {
                    etInstanceName.setText(freeDefaultName());
                }
                btnInstall.setEnabled(true);
            });

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_new_instance, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        etInstanceName = view.findViewById(R.id.et_instance_name);
        btnPickZip     = view.findViewById(R.id.btn_pick_zip);
        btnInstall     = view.findViewById(R.id.btn_install);
        tvSelectedZip  = view.findViewById(R.id.tv_selected_zip);
        progressInstall = view.findViewById(R.id.progress_install);
        tvInstallStatus = view.findViewById(R.id.tv_install_status);

        btnInstall.setEnabled(false);

        String preselected = getArguments() == null ? null : getArguments().getString(ARG_PRESELECTED_FILE);
        if (preselected != null && !preselected.isEmpty() && new File(preselected).isFile()) {
            preselectedFile = new File(preselected);
            selectedZipUri = Uri.fromFile(preselectedFile);
            tvSelectedZip.setText(preselectedFile.getName());
            btnInstall.setEnabled(true);
        }

        // Pre-fill a short, always-fits default so most users just tap Install and never hit the
        // name-length limit; the field stays editable for anyone who wants a custom name.
        if (etInstanceName.getText().toString().trim().isEmpty()) {
            etInstanceName.setText(freeDefaultName());
        }

        btnPickZip.setOnClickListener(v -> zipPicker.launch(com.stormdroid.C.mime.GAME_ARCHIVE));
        btnInstall.setOnClickListener(v -> startInstall());

        IntentFilter f = new IntentFilter();
        f.addAction(InstallerService.BROADCAST_PROGRESS);
        f.addAction(InstallerService.BROADCAST_DONE);
        f.addAction(InstallerService.BROADCAST_ERROR);
        requireContext().registerReceiver(installerReceiver, f, Context.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        requireContext().unregisterReceiver(installerReceiver);
    }

    /** Set when an install ended while the launcher was in the background (see leave). */
    private boolean leaveOnResume;

    /**
     * Back to the launcher. Android refuses a navigation while the app is in the background (its
     * state is saved), and the screen then stayed on "Installing..." with the instance long done:
     * the return waits for onResume instead.
     */
    private void leave() {
        if (!isAdded() || getView() == null) return;
        if (isStateSaved()) { leaveOnResume = true; return; }
        Navigation.findNavController(requireView()).popBackStack();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (leaveOnResume) { leaveOnResume = false; leave(); }
    }

    /** The install's progress under the button: a bar (indeterminate when percent < 0) and a line. */
    private void showProgress(String message, int percent) {
        if (!isAdded() || progressInstall == null) return;
        progressInstall.setVisibility(View.VISIBLE);
        progressInstall.setIndeterminate(percent < 0);
        if (percent >= 0) progressInstall.setProgress(percent);
        if (message != null) {
            tvInstallStatus.setText(message);
            tvInstallStatus.setVisibility(View.VISIBLE);
        }
    }

    private void hideProgress() {
        if (progressInstall == null) return;
        progressInstall.setVisibility(View.GONE);
        tvInstallStatus.setVisibility(View.GONE);
    }

    /** Size of a picked document in bytes, or -1 when the provider does not say. */
    private static long documentSize(android.content.Context ctx, Uri uri) {
        try (android.database.Cursor c = ctx.getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) {}
        return -1;
    }

    /** The first default name with no existing instance directory. */
    private static String freeDefaultName() {
        return com.stormdroid.AppStorage.freeDefaultInstanceName();
    }

    private void startInstall() {
        if (selectedZipUri == null) return;
        String rawName = etInstanceName.getText().toString().trim();
        // Instance name = directory name = part of every game path: sanitize to a URL/path-safe name
        // up front (Unity's file:// URLs are not escaped everywhere).
        final String instanceName = rawName.replaceAll("[^A-Za-z0-9._-]+", "-")
                                           .replaceAll("^-+|-+$", "");
        if (instanceName.isEmpty()) {
            etInstanceName.setError(getString(R.string.error_name_required));
            return;
        }
        if (instanceName.length() > MAX_NAME_LEN) {
            etInstanceName.setError(getString(R.string.error_name_too_long, MAX_NAME_LEN));
            return;
        }
        // Refuse a name whose instance directory already exists, so we never install over (or beside)
        // an existing instance. InstallerService double-checks, but catching it here gives a clear
        // field error instead of a late broadcast failure.
        if (com.stormdroid.AppStorage.requireSingleton().getInstanceDir(instanceName).exists()) {
            etInstanceName.setError(getString(R.string.error_name_exists));
            return;
        }

        btnInstall.setEnabled(false);
        btnInstall.setText(R.string.installing);
        showProgress(getString(R.string.install_starting), -1);

        // Grab the context HERE, on the UI thread: copying the zip takes long enough for the user to
        // leave the screen, and requireContext() from the worker would then throw. The application
        // context outlives the fragment; UI touches go through mainHandler behind an isAdded() check.
        final android.content.Context appCtx = requireContext().getApplicationContext();
        if (preselectedFile != null) {
            InstallerService.startInstallInstance(appCtx, preselectedFile.getAbsolutePath(), instanceName);
            return;
        }
        new Thread(() -> {
            try {
                File cacheZip = new File(appCtx.getCacheDir(), "instance.zip");
                long size = documentSize(appCtx, selectedZipUri);
                try (InputStream in = appCtx.getContentResolver()
                        .openInputStream(selectedZipUri);
                     FileOutputStream out = new FileOutputStream(cacheZip)) {
                    byte[] buf = new byte[1 << 16];
                    int len;
                    long copied = 0, lastTime = 0;
                    while ((len = in.read(buf)) != -1) {
                        out.write(buf, 0, len);
                        copied += len;
                        long now = System.currentTimeMillis();
                        if (now - lastTime > 300) {
                            lastTime = now;
                            final int pct = size > 0 ? (int) Math.min(100, copied * 100 / size) : -1;
                            final String msg = appCtx.getString(R.string.install_copying, pct < 0 ? 0 : pct,
                                    copied / (1024 * 1024), size / (1024 * 1024));
                            mainHandler.post(() -> showProgress(msg, pct));
                        }
                    }
                }
                InstallerService.startInstallInstance(appCtx, cacheZip.getAbsolutePath(), instanceName);
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (!isAdded() || getView() == null) return;
                    btnInstall.setEnabled(true);
                    btnInstall.setText(R.string.install);
                    hideProgress();
                });
            }
        }).start();
    }
}
