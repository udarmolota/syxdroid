package com.udarmolota.syxdroid.fragments;

import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.udarmolota.syxdroid.R;
import com.udarmolota.syxdroid.SteamDownloadState;

import java.util.concurrent.CompletableFuture;

/**
 * The Game page: download the player's own Songs of Syx from Steam (SteamGameDownloader), which is
 * then imported like a folder or zip would be. The download itself lives in
 * {@link SteamDownloadState}, so leaving this page does not stop it; coming back shows it again.
 * The password goes straight to the downloader and is not kept by this page.
 */
public class DownloadFragment extends Fragment implements SteamDownloadState.View {

    /** Set by the Workshop page: the items to download instead of the game, and where to. */
    public static final String ARG_MOD_IDS = "mod_ids";
    public static final String ARG_MOD_TITLES = "mod_titles";
    public static final String ARG_MOD_INSTANCE = "mod_instance";

    private long[] modIds;
    private String[] modTitles;
    private String modInstance;
    private TextView modsTv;

    private EditText userEt, passEt;
    private Button startBtn, cancelBtn;
    private LinearProgressIndicator progress;
    private TextView logTv;
    private ScrollView scroll;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_download, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        userEt = v.findViewById(R.id.dl_user_et);
        passEt = v.findViewById(R.id.dl_pass_et);
        startBtn = v.findViewById(R.id.dl_start_btn);
        cancelBtn = v.findViewById(R.id.dl_cancel_btn);
        progress = v.findViewById(R.id.dl_progress_lpi);
        logTv = v.findViewById(R.id.dl_log_tv);
        scroll = v.findViewById(R.id.dl_scroll);
        modsTv = v.findViewById(R.id.dl_mods_tv);

        Bundle args = getArguments();
        if (args != null && args.getLongArray(ARG_MOD_IDS) != null) {
            modIds = args.getLongArray(ARG_MOD_IDS);
            modTitles = args.getStringArray(ARG_MOD_TITLES);
            modInstance = args.getString(ARG_MOD_INSTANCE);
            // Once: coming back to this page later shows the game download again
            args.remove(ARG_MOD_IDS);
        }
        showModsBanner();

        startBtn.setOnClickListener(b -> start());
        cancelBtn.setOnClickListener(b -> SteamDownloadState.get().cancel());
    }

    @Override
    public void onStart() {
        super.onStart();
        SteamDownloadState state = SteamDownloadState.get();
        state.setView(this);
        CharSequence log = state.getLog();
        if (log.length() > 0) logTv.setText(log);
        updateButtons();
        if (state.isDownloading()) onPercent(state.getPercent(), state.isIndeterminate());
    }

    @Override
    public void onStop() {
        SteamDownloadState.get().clearView(this);
        super.onStop();
    }

    private void start() {
        String user = userEt.getText() == null ? "" : userEt.getText().toString().trim();
        String pass = passEt.getText() == null ? "" : passEt.getText().toString();
        if (user.isEmpty() || pass.isEmpty()) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.steam_need_credentials)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        // The password is handed over once and the field cleared: nothing keeps it on this page.
        passEt.setText("");
        logTv.setText("");
        if (modIds != null && modIds.length > 0) {
            SteamDownloadState.get().startMods(requireContext(), user, pass, modIds, modTitles, modInstance);
            modIds = null;
            showModsBanner();
        } else {
            SteamDownloadState.get().start(requireContext(), user, pass);
        }
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);
        updateButtons();
    }

    private void showModsBanner() {
        if (modsTv == null) return;
        if (modIds != null && modIds.length > 0) {
            modsTv.setText(getString(R.string.steam_mods_banner, modIds.length, modInstance));
            modsTv.setVisibility(View.VISIBLE);
        } else {
            modsTv.setVisibility(View.GONE);
        }
    }

    private void updateButtons() {
        boolean busy = SteamDownloadState.get().isDownloading();
        startBtn.setEnabled(!busy);
        cancelBtn.setEnabled(busy);
        userEt.setEnabled(!busy);
        passEt.setEnabled(!busy);
        if (!busy) progress.setVisibility(View.GONE);
    }

    // ---- SteamDownloadState.View (main thread) ----

    @Override
    public void onLog(CharSequence fullLog) {
        if (logTv == null) return;
        logTv.setText(fullLog);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    public void onPercent(int percent, boolean indeterminate) {
        if (progress == null) return;
        progress.setVisibility(View.VISIBLE);
        if (indeterminate || percent < 0) {
            progress.setIndeterminate(true);
        } else {
            progress.setIndeterminate(false);
            progress.setProgressCompat(percent, true);
        }
    }

    @Override
    public void onFinished(String message, boolean ok) {
        if (!isAdded()) return;
        updateButtons();
        MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(ok ? R.string.steam_done_title : R.string.steam_failed_title)
                .setMessage(message);
        android.net.Uri zip = SteamDownloadState.get().getLastZip();
        if (ok && SteamDownloadState.get().getLastModsInstance() != null) {
            dialog.setPositiveButton(R.string.steam_mods_open, (d, w) -> {
                        android.view.View root = getView();
                        if (root != null) androidx.navigation.Navigation.findNavController(root)
                                .navigate(R.id.action_open_mods);
                    })
                    .setNegativeButton(android.R.string.ok, null);
        } else if (ok && zip != null) {
            // A new instance from the zip just made, as in RimDroid; "+" can do it again any time.
            dialog.setPositiveButton(R.string.steam_create_instance, (d, w) -> {
                        android.app.Activity a = getActivity();
                        if (a instanceof com.udarmolota.syxdroid.LauncherActivity)
                            ((com.udarmolota.syxdroid.LauncherActivity) a).createInstanceFromZip(zip);
                    })
                    .setNegativeButton(R.string.steam_later, null);
        } else {
            dialog.setPositiveButton(android.R.string.ok, null);
        }
        dialog.show();
    }


    /** Called from the download thread: the dialog goes up on the main thread, the code comes back. */
    @Override
    public CompletableFuture<String> requestSteamGuardCode(boolean previousWrong, String email) {
        CompletableFuture<String> result = new CompletableFuture<>();
        android.app.Activity activity = getActivity();
        if (activity == null) {
            result.complete("");
            return result;
        }
        activity.runOnUiThread(() -> {
            if (!isAdded()) { result.complete(""); return; }
            EditText code = new EditText(requireContext());
            code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
            code.setSingleLine(true);
            int pad = Math.round(20 * getResources().getDisplayMetrics().density);
            android.widget.FrameLayout box = new android.widget.FrameLayout(requireContext());
            box.setPadding(pad, pad / 2, pad, 0);
            box.addView(code);
            String message = email != null
                    ? getString(R.string.steam_guard_email, email)
                    : getString(R.string.steam_guard_app);
            if (previousWrong) message = getString(R.string.steam_guard_wrong) + "\n\n" + message;
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.steam_guard_title)
                    .setMessage(message)
                    .setView(box)
                    .setCancelable(false)
                    .setPositiveButton(android.R.string.ok, (d, w) ->
                            result.complete(code.getText().toString().trim()))
                    .setNegativeButton(android.R.string.cancel, (d, w) -> {
                        result.complete("");
                        SteamDownloadState.get().cancel();
                    })
                    .show();
        });
        return result;
    }
}
