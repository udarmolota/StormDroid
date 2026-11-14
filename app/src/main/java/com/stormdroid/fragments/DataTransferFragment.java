package com.stormdroid.fragments;

import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.stormdroid.GameDataTransfer;
import com.stormdroid.R;
import com.stormdroid.game.GameInstance;
import com.stormdroid.game.GameInstanceManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Import and export, one screen (as in Zomdroid): a card for an instance's saves and a card for the
 * game's settings, each with Import and Export. The zip format is GameDataTransfer's. Importing
 * replaces files, so it is confirmed first.
 */
public class DataTransferFragment extends Fragment {

    private String kind = GameDataTransfer.SAVES;   // what the file picker in flight is for
    private Spinner spInstance;
    private int instanceOffset;   // spinner position of the first instance (a prompt comes first with several)
    private final List<GameInstance> instances = new ArrayList<>();
    private GameInstance exportFor;   // the instance an export in flight belongs to

    private final ActivityResultLauncher<String[]> pickImport =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::confirmImport);

    private final ActivityResultLauncher<String> pickExport =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/zip"), this::export);

    private boolean saves() {
        return !GameDataTransfer.SETTINGS.equals(kind);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_data_transfer, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        spInstance = v.findViewById(R.id.sp_transfer_instance);
        GameInstanceManager.requireSingleton().reload();
        instances.clear();
        instances.addAll(GameInstanceManager.requireSingleton().getInstances());
        instanceOffset = com.stormdroid.InstanceSpinner.fill(spInstance, instances, getString(R.string.transfer_no_instances));

        // The game's settings belong to the app, not to an instance: the picker is only for saves.
        v.findViewById(R.id.btn_saves_import).setOnClickListener(b -> startImport(GameDataTransfer.SAVES));
        v.findViewById(R.id.btn_settings_import).setOnClickListener(b -> startImport(GameDataTransfer.SETTINGS));
        v.findViewById(R.id.btn_saves_export).setOnClickListener(b -> startExport(GameDataTransfer.SAVES));
        v.findViewById(R.id.btn_settings_export).setOnClickListener(b -> startExport(GameDataTransfer.SETTINGS));
        v.findViewById(R.id.btn_cloud_open).setOnClickListener(b ->
                androidx.navigation.Navigation.findNavController(b).navigate(R.id.action_open_cloud_saves));
    }

    private void startImport(String what) {
        kind = what;
        if (saves() && current() == null) { askForInstance(); return; }
        pickImport.launch(new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
    }

    private void startExport(String what) {
        kind = what;
        GameInstance gi = saves() ? current() : null;
        if (saves() && gi == null) { askForInstance(); return; }
        exportFor = gi;
        String who = saves() ? "_" + gi.getName().replaceAll("[^A-Za-z0-9._-]", "_") : "";
        pickExport.launch("stormdroid_" + kind + who + "_"
                + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date())
                + ".zip");
    }

    private void askForInstance() {
        if (!instances.isEmpty()) Toast.makeText(requireContext(), R.string.choose_instance_first, Toast.LENGTH_SHORT).show();
    }

    @Nullable
    private GameInstance current() {
        return com.stormdroid.InstanceSpinner.selected(spInstance, instances, instanceOffset);
    }

    /** Importing replaces files: asked first, a stray tap must not do it. */
    private void confirmImport(@Nullable Uri uri) {
        GameInstance gi = current();
        if (uri == null || (saves() && gi == null) || !isAdded()) return;
        String file = displayName(uri);
        if (file == null) file = uri.getLastPathSegment();
        String message = saves() ? getString(R.string.saves_transfer_import_confirm, file, gi.getName())
                : getString(R.string.settings_transfer_import_confirm, file);
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(message)
                .setPositiveButton(R.string.transfer_import, (d, w) -> importData(uri, gi))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void importData(Uri uri, @Nullable GameInstance gi) {
        final android.content.Context app = requireContext().getApplicationContext();
        final File instanceDir = gi != null ? new File(gi.getGamePath()) : null;
        final String part = kind;
        new Thread(() -> {
            String msg;
            File cacheZip = new File(app.getCacheDir(), "import_data.zip");
            try (InputStream in = app.getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(cacheZip)) {
                if (in == null) throw new java.io.IOException("cannot open the file");
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.close();
                GameDataTransfer.Result r = GameDataTransfer.importZip(app, cacheZip, instanceDir, part);
                msg = r.ok() ? app.getString(R.string.transfer_imported, r.files)
                        : app.getString(R.string.transfer_import_failed, r.error);
            } catch (Exception e) {
                msg = app.getString(R.string.transfer_import_failed, String.valueOf(e.getMessage()));
            } finally {
                //noinspection ResultOfMethodCallIgnored
                cacheZip.delete();
            }
            toastLater(msg);
        }, "data-import").start();
    }

    private void export(@Nullable Uri uri) {
        final GameInstance gi = exportFor;
        if (uri == null || (saves() && gi == null) || !isAdded()) return;
        final android.content.Context app = requireContext().getApplicationContext();
        final File instanceDir = gi != null ? new File(gi.getGamePath()) : null;
        final String part = kind;
        new Thread(() -> {
            String msg;
            try (OutputStream out = app.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new java.io.IOException("cannot open the file");
                GameDataTransfer.Result r = GameDataTransfer.export(app, instanceDir, out, part);
                msg = r.ok() ? app.getString(R.string.transfer_exported, r.files, (int) (r.bytes / 1024))
                        : app.getString(R.string.transfer_export_failed, r.error);
            } catch (Exception e) {
                msg = app.getString(R.string.transfer_export_failed, String.valueOf(e.getMessage()));
            }
            toastLater(msg);
        }, "data-export").start();
    }

    private void toastLater(String msg) {
        android.app.Activity a = getActivity();
        if (a != null) a.runOnUiThread(() -> Toast.makeText(a, msg, Toast.LENGTH_LONG).show());
    }

    @Nullable
    private String displayName(Uri uri) {
        try (Cursor c = requireContext().getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {}
        return null;
    }
}
