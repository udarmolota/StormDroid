package com.stormdroid.fragments;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.stormdroid.R;
import com.stormdroid.SteamDownloadState;
import com.stormdroid.SteamDownloader;
import com.stormdroid.StorageAccess;

import java.util.concurrent.CompletableFuture;

/**
 * "Download from Steam" screen (ported from ValDroid/RimDroid): the player's Steam login and an optional
 * manifest id; downloads the newest public build of Against the Storm and packs it into
 * /Download/StormDroid/AgainstTheStorm_build&lt;id&gt;.zip, with progress and a small console log. Nothing
 * else runs: "Create instance" then opens Add instance with that archive already chosen. The login is
 * sent to Steam (like DepotDownloader) and never stored. The download outlives this screen
 * (SteamDownloadState), and downloading again resumes a stopped download of the same build.
 */
public class DownloadFragment extends Fragment implements SteamDownloadState.View {

    private final Handler mainH = new Handler(Looper.getMainLooper());

    private EditText etUser, etPass, etManifest;
    private Button btnStart, btnCancel, btnCreateInstance;
    private ProgressBar progress;
    private TextView tvStatus;
    private android.content.Context appCtx;   // for the keep-alive service (valid even if detached)

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_download, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        appCtx = requireContext().getApplicationContext();
        etUser = v.findViewById(R.id.et_dl_user);
        etPass = v.findViewById(R.id.et_dl_pass);
        etManifest = v.findViewById(R.id.et_dl_manifest);
        btnStart = v.findViewById(R.id.btn_dl_start);
        btnCancel = v.findViewById(R.id.btn_dl_cancel);
        btnCreateInstance = v.findViewById(R.id.btn_dl_create_instance);
        btnCreateInstance.setOnClickListener(view -> createInstance());
        progress = v.findViewById(R.id.progress_dl);
        tvStatus = v.findViewById(R.id.tv_dl_status);
        tvStatus.setMovementMethod(new ScrollingMovementMethod());
        btnStart.setOnClickListener(view -> startGame());
        btnCancel.setOnClickListener(view -> confirmCancel());

        // Re-attach to a download already running in the background, restoring the log, the progress
        // and the "busy" state.
        SteamDownloadState st = SteamDownloadState.get();
        st.setView(this);
        tvStatus.setText(st.getLog());
        scrollLogToBottom();
        boolean busy = st.isDownloading();
        setControlsEnabled(!busy);
        btnCancel.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy) {
            progress.setVisibility(View.VISIBLE);
            if (st.isIndeterminate() || st.getPercent() < 0) {
                progress.setIndeterminate(true);
            } else {
                progress.setIndeterminate(false);
                progress.setProgress(st.getPercent());
            }
        } else {
            progress.setVisibility(View.GONE);
        }
        showCreateInstance(!busy);
    }

    @Override
    public void onDestroyView() {
        SteamDownloadState.get().clearView(this);
        super.onDestroyView();
    }

    private void startGame() {
        if (SteamDownloadState.get().isDownloading()) return;
        if (text(etUser).isEmpty()) { etUser.setError(getString(R.string.download_required)); return; }
        if (etPass.getText().toString().isEmpty()) { etPass.setError(getString(R.string.download_required)); return; }

        long manifestId = 0L;   // 0 = the newest public build
        try {
            String mt = text(etManifest);
            if (!mt.isEmpty()) manifestId = Long.parseUnsignedLong(mt);
        } catch (NumberFormatException ignored) { /* blank/invalid -> default */ }

        // The archive goes into the public /Download/StormDroid folder: All-files access first.
        if (!StorageAccess.hasAllFilesAccess()) {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireActivity())
                    .setTitle(R.string.download_storage_title)
                    .setMessage(R.string.download_storage_message)
                    .setPositiveButton(R.string.download_storage_grant, (d, w) -> StorageAccess.requestAllFilesAccess(requireActivity()))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }

        SteamDownloadState st = SteamDownloadState.get();
        SteamDownloader dl = new SteamDownloader(text(etUser), etPass.getText().toString(),
                /* manifestOnly */ false, manifestId, st);
        st.begin(appCtx);
        st.onProgress(getString(R.string.download_space_note));
        st.setActive(dl);
        beginUi();
        new Thread(dl, "sd-download").start();
    }

    private void confirmCancel() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireActivity())
                .setMessage(R.string.download_cancel_confirm)
                .setPositiveButton(R.string.download_cancel, (d, w) -> SteamDownloadState.get().cancel())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---- Create an instance from the downloaded archive: Add instance with the file already chosen ----
    private void createInstance() {
        java.io.File zip = SteamDownloadState.get().getGameZip();
        if (zip == null || !zip.isFile()) { showCreateInstance(false); return; }
        Bundle args = new Bundle();
        args.putString(NewInstanceFragment.ARG_PRESELECTED_FILE, zip.getAbsolutePath());
        androidx.navigation.Navigation.findNavController(requireView()).navigate(R.id.action_install_instance, args);
    }

    /** The button shows while a downloaded game archive is there to create an instance from. */
    private void showCreateInstance(boolean allowed) {
        if (btnCreateInstance == null) return;
        java.io.File zip = allowed ? SteamDownloadState.get().getGameZip() : null;
        btnCreateInstance.setVisibility(zip != null ? View.VISIBLE : View.GONE);
        if (zip != null) btnCreateInstance.setText(getString(R.string.download_create_instance, zip.getName()));
    }

    private void beginUi() {
        showCreateInstance(false);
        setControlsEnabled(false);
        btnCancel.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);   // until the first real percent arrives
        tvStatus.setText(SteamDownloadState.get().getLog());
    }

    /** Enable/disable the start controls (everything except Cancel) while a download runs. */
    private void setControlsEnabled(boolean enabled) {
        btnStart.setEnabled(enabled);
        etUser.setEnabled(enabled);
        etPass.setEnabled(enabled);
        etManifest.setEnabled(enabled);
    }

    // ---- SteamDownloadState.View (callbacks already posted to the main thread by the state) ----
    @Override
    public void onLog(CharSequence fullLog) {
        if (tvStatus == null) return;
        tvStatus.setText(fullLog);
        scrollLogToBottom();
    }

    @Override
    public void onPercent(int percent, boolean indeterminate) {
        if (progress == null) return;
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(indeterminate);
        if (!indeterminate) progress.setProgress(Math.max(0, Math.min(100, percent)));
    }

    @Override
    public void onFinished(String message) {
        setControlsEnabled(true);
        if (progress != null) progress.setVisibility(View.GONE);
        if (btnCancel != null) btnCancel.setVisibility(View.GONE);
        showCreateInstance(true);
        if (isAdded()) Toast.makeText(requireContext().getApplicationContext(), message, Toast.LENGTH_LONG).show();
    }

    @Override
    public CompletableFuture<String> requestSteamGuardCode(boolean prevWrong, String email) {
        final CompletableFuture<String> fut = new CompletableFuture<>();
        mainH.post(() -> {
            if (!isAdded()) { fut.complete(""); return; }
            final EditText etCode = new EditText(requireActivity());
            etCode.setHint(email != null ? getString(R.string.download_guard_email_hint, email)
                    : getString(R.string.download_guard_hint));
            etCode.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireActivity())
                    .setTitle(prevWrong ? R.string.download_guard_wrong : R.string.download_guard_title)
                    .setView(etCode)
                    .setCancelable(false)
                    .setPositiveButton(android.R.string.ok, (d, w) -> fut.complete(etCode.getText().toString().trim()))
                    .show();
        });
        return fut;
    }

    private void scrollLogToBottom() {
        if (tvStatus == null) return;
        android.text.Layout layout = tvStatus.getLayout();
        if (layout != null) {
            int y = layout.getLineTop(tvStatus.getLineCount()) - tvStatus.getHeight();
            tvStatus.scrollTo(0, Math.max(0, y));
        }
    }

    private static String text(EditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }
}
