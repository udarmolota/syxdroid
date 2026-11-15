package com.udarmolota.syxdroid.fragments;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.udarmolota.syxdroid.GameDataTransfer;
import com.udarmolota.syxdroid.R;
import com.udarmolota.syxdroid.SyxGame;
import com.udarmolota.syxdroid.game.GameInstance;
import com.udarmolota.syxdroid.game.GameInstanceManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Every import and export in one screen (as in RimDroid): a card each for the saves, the game's
 * settings and the on-screen controls, with an Import and an Export button. Import picks the file
 * and asks once before it writes anything. Saves and settings are {@link GameDataTransfer} zips,
 * the controls the layout .json the controls editor uses.
 *
 * <p>The instance is picked once at the top; with no instance yet there is nothing to export or
 * import into, and the buttons say so.
 */
public class TransferFragment extends Fragment {

    private static final int NONE = 0, SAVES = 1, SETTINGS = 2, CONTROLS = 3;
    private static final String STATE_PENDING = "pending";
    private static final String[] ZIP_TYPES = {"application/zip", "application/x-zip-compressed", "application/octet-stream"};
    private static final String[] JSON_TYPES = {"application/json", "text/plain", "application/octet-stream"};

    /** What the open system picker is for; kept across a recreation of this screen. */
    private int pending = NONE;
    private static final String STATE_INSTANCE = "instance";
    private android.widget.Spinner spInstance;
    private final java.util.List<GameInstance> instances = new java.util.ArrayList<>();
    /** The instance an import or export in flight belongs to; kept across a recreation. */
    private String pendingInstance;

    private final ActivityResultLauncher<String[]> pickImport =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::confirmImport);
    private final ActivityResultLauncher<String> exportZip =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/zip"), this::export);
    private final ActivityResultLauncher<String> exportJson =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), this::export);

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_transfer, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        if (savedInstanceState != null) {
            pending = savedInstanceState.getInt(STATE_PENDING, NONE);
            pendingInstance = savedInstanceState.getString(STATE_INSTANCE);
        }
        spInstance = v.findViewById(R.id.sp_transfer_instance);
        instances.clear();
        instances.addAll(GameInstanceManager.requireSingleton().getInstances());
        java.util.List<String> names = new java.util.ArrayList<>();
        if (instances.isEmpty()) names.add(getString(R.string.no_instances));
        for (GameInstance gi : instances) names.add(gi.getName());
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                requireContext(), android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spInstance.setAdapter(adapter);
        v.findViewById(R.id.btn_transfer_saves_import).setOnClickListener(b -> startImport(SAVES));
        v.findViewById(R.id.btn_transfer_saves_export).setOnClickListener(b -> startExport(SAVES));
        v.findViewById(R.id.btn_transfer_settings_import).setOnClickListener(b -> startImport(SETTINGS));
        v.findViewById(R.id.btn_transfer_settings_export).setOnClickListener(b -> startExport(SETTINGS));
        v.findViewById(R.id.btn_transfer_controls_import).setOnClickListener(b -> startImport(CONTROLS));
        v.findViewById(R.id.btn_transfer_controls_export).setOnClickListener(b -> startExport(CONTROLS));
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_PENDING, pending);
        outState.putString(STATE_INSTANCE, pendingInstance);
    }

    /** The picked instance, or null (with a message) when there is none or it has no game yet. */
    @Nullable
    private GameInstance gameOrToast() {
        int i = spInstance == null ? -1 : spInstance.getSelectedItemPosition();
        GameInstance gi = (i >= 0 && i < instances.size()) ? instances.get(i) : null;
        if (gi == null || !gi.hasGameFiles()) {
            Toast.makeText(requireContext(), R.string.syx_transfer_no_game, Toast.LENGTH_LONG).show();
            return null;
        }
        pendingInstance = gi.getName();
        return gi;
    }

    /** The instance the open picker was started for. */
    @Nullable
    private GameInstance pendingGame() {
        return pendingInstance == null ? null
                : GameInstanceManager.requireSingleton().getInstanceByName(pendingInstance);
    }

    private static File controlsFile(GameInstance gi) {
        return new File(gi.getGamePath(), "controls/controls.json");
    }

    // ---- import ----

    private void startImport(int what) {
        if (gameOrToast() == null) return;
        pending = what;
        pickImport.launch(what == CONTROLS ? JSON_TYPES : ZIP_TYPES);
    }

    /** Importing overwrites what the game has: asked once, a stray tap must not do it. */
    private void confirmImport(@Nullable Uri uri) {
        int what = pending;
        pending = NONE;
        GameInstance gi = pendingGame();
        if (uri == null || gi == null || !isAdded()) return;
        String file = displayName(uri);
        if (file == null) file = uri.getLastPathSegment();
        int message = what == SAVES ? R.string.syx_transfer_saves_import_confirm
                : what == SETTINGS ? R.string.syx_transfer_settings_import_confirm
                : R.string.syx_transfer_controls_import_confirm;
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(getString(message, file))
                .setPositiveButton(R.string.transfer_import, (d, w) -> {
                    if (what == CONTROLS) importControls(uri, gi);
                    else importData(uri, gi, what == SAVES ? GameDataTransfer.SAVES : GameDataTransfer.CONFIG);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void importData(Uri uri, GameInstance gi, String part) {
        final Context app = requireContext().getApplicationContext();
        final File userDir = GameDataTransfer.userDir(gi);
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
                GameDataTransfer.Result r = GameDataTransfer.importZip(cacheZip, userDir, part);
                msg = r.ok() ? app.getString(R.string.syx_transfer_imported, android.text.TextUtils.join(" + ", r.items))
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

    private void importControls(Uri uri, GameInstance gi) {
        final Context app = requireContext().getApplicationContext();
        new Thread(() -> {
            String msg;
            try (InputStream in = app.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException("cannot open the file");
                String json = new String(readAll(in), StandardCharsets.UTF_8);
                // Only a layout that parses is stored, so a wrong file cannot leave the game without controls.
                new org.json.JSONArray(json);
                File target = controlsFile(gi);
                File dir = target.getParentFile();
                if (dir != null && !dir.isDirectory() && !dir.mkdirs())
                    throw new java.io.IOException("cannot create " + dir);
                try (OutputStream out = new FileOutputStream(target)) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                }
                msg = app.getString(R.string.syx_transfer_controls_imported);
            } catch (org.json.JSONException e) {
                msg = app.getString(R.string.controls_import_failed, "not a controls layout");
            } catch (Exception e) {
                msg = app.getString(R.string.controls_import_failed,
                        e.getMessage() == null ? "invalid layout file" : e.getMessage());
            }
            toastLater(msg);
        }, "controls-import").start();
    }

    // ---- export ----

    private void startExport(int what) {
        if (gameOrToast() == null) return;
        pending = what;
        String kind = what == SAVES ? "saves" : what == SETTINGS ? "settings" : "controls";
        String name = "syxdroid_" + kind + "_"
                + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date());
        if (what == CONTROLS) exportJson.launch(name + ".json");
        else exportZip.launch(name + ".zip");
    }

    private void export(@Nullable Uri uri) {
        final int what = pending;
        pending = NONE;
        final GameInstance gi = pendingGame();
        if (uri == null || gi == null || !isAdded()) return;
        final Context app = requireContext().getApplicationContext();
        new Thread(() -> {
            String msg;
            try (OutputStream out = app.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new java.io.IOException("cannot open the file");
                if (what == CONTROLS) {
                    // A game with no layout of its own yet plays the built-in one: export that.
                    File f = controlsFile(gi);
                    byte[] json;
                    if (f.isFile()) {
                        try (InputStream in = new java.io.FileInputStream(f)) { json = readAll(in); }
                    } else {
                        try (InputStream in = app.getAssets().open(SyxGame.ASSET_CONTROLS_JSON)) { json = readAll(in); }
                    }
                    out.write(json);
                    msg = app.getString(R.string.controls_exported);
                } else {
                    String part = what == SAVES ? GameDataTransfer.SAVES : GameDataTransfer.CONFIG;
                    GameDataTransfer.Result r = GameDataTransfer.export(GameDataTransfer.userDir(gi), out, part);
                    msg = r.ok() ? app.getString(R.string.transfer_exported, android.text.TextUtils.join(" + ", r.items),
                            (int) (r.bytes / 1024))
                            : app.getString(R.string.transfer_export_failed, r.error);
                }
            } catch (Exception e) {
                msg = app.getString(R.string.transfer_export_failed, String.valueOf(e.getMessage()));
            }
            toastLater(msg);
        }, "transfer-export").start();
    }

    // ---- helpers ----

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) bos.write(b, 0, n);
        return bos.toByteArray();
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
