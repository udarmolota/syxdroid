package com.udarmolota.syxdroid;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.udarmolota.syxdroid.databinding.ActivitySyxStartBinding;
import com.udarmolota.syxdroid.input.GamepadManager;

/**
 * SyxDroid's entry point: the only launcher activity of the debug and release builds.
 *
 * <p>Before the first import it explains that the game is not included and offers two pickers:
 * the game folder or a zip of it. Once there is a source, or when the game is already imported, it
 * asks InstallerService to prepare the game (PREPARE_BUNDLED_GAME: dependencies, the import when
 * there is one, then the instance; see {@link SyxImporter} and {@link SyxGame}), shows its
 * progress full screen, and starts GameActivity once it is done. On failure the error stays on
 * screen with a button into the launcher, which is hidden otherwise and still has the logs and the
 * bug report; if the game is still not imported, the pickers come back for another try.
 */
public class SyxStartActivity extends AppCompatActivity {
    private static final String LOG_TAG = SyxStartActivity.class.getName();
    private static final String STATE_RUN_TOKEN = "runToken";
    private static final String STATE_ERROR_TITLE = "errorTitle";
    private static final String STATE_ERROR_MESSAGE = "errorMessage";

    // The run this process has started. A token restored from a saved state but not equal to this
    // one belongs to a process that is gone, and its run died with it.
    private static long startedRunToken;

    private ActivitySyxStartBinding binding;
    private InstallerService installerService;
    private boolean isInstallerServiceBound;
    private long runToken;
    private boolean failed;
    private String errorTitle;
    private String errorMessage;

    private final ActivityResultLauncher<Uri> pickFolder = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(),
            uri -> { if (uri != null) startRun(uri, SyxImporter.KIND_FOLDER); });

    private final ActivityResultLauncher<String[]> pickZip = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> { if (uri != null) startRun(uri, SyxImporter.KIND_ZIP); });

    private final ServiceConnection installerServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            installerService = ((InstallerService.LocalBinder) service).getService();
            isInstallerServiceBound = true;
            installerService.getTaskState().observe(SyxStartActivity.this,
                    SyxStartActivity.this::handleTaskState);
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            Log.e(LOG_TAG, "Connection to installer service has been lost");
            isInstallerServiceBound = false;
            installerService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySyxStartBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.startOpenLauncherMb.setOnClickListener(v -> openLauncher());
        binding.startImportFolderMb.setOnClickListener(v -> pickFolder.launch(null));
        // Some file managers label zips application/x-zip-compressed; */* would let anything through.
        binding.startImportZipMb.setOnClickListener(v -> pickZip.launch(new String[]{
                "application/zip", "application/x-zip-compressed", "application/x-zip"}));

        // LauncherActivity does this at app start, and GameActivity relies on it. The launcher is
        // skipped now, so it has to happen here.
        GamepadManager.loadCustomMapping(this);

        // New-version notification: the daily check, and once ever the permission it needs
        UpdateChecker.onAppStart(this);
        if (savedInstanceState == null) UpdateChecker.askForNotificationsOnce(this, 1);

        if (savedInstanceState != null) {
            if (savedInstanceState.containsKey(STATE_ERROR_MESSAGE)) {
                showError(savedInstanceState.getString(STATE_ERROR_TITLE),
                        savedInstanceState.getString(STATE_ERROR_MESSAGE));
                return;
            }
            long savedToken = savedInstanceState.getLong(STATE_RUN_TOKEN, 0);
            if (savedToken != 0 && savedToken == startedRunToken) runToken = savedToken;
        }

        if (runToken != 0) {
            // Recreated while our run is still going: follow it rather than start it again.
            bindService(new Intent(this, InstallerService.class), installerServiceConnection, 0);
        } else if (!SyxGame.isImported()) {
            showImportChoice();
        } else {
            startRun(null, null);
        }
    }

    /** Starts the prepare task, with an import first when importUri is given. */
    private void startRun(Uri importUri, String importKind) {
        failed = false;
        hideImportChoice();
        binding.startErrorSv.setVisibility(View.GONE);
        binding.startOpenLauncherMb.setVisibility(View.GONE);
        binding.startProgressLpi.setVisibility(View.VISIBLE);
        binding.startProgressLpi.setIndeterminate(true);
        binding.startTitleTv.setText(importUri != null ? R.string.syx_importing : R.string.deephall_preparing);

        runToken = SystemClock.elapsedRealtimeNanos();
        startedRunToken = runToken;
        Intent installerIntent = new Intent(this, InstallerService.class);
        installerIntent.putExtra(InstallerService.EXTRA_COMMAND,
                InstallerService.Task.PREPARE_BUNDLED_GAME.ordinal());
        installerIntent.putExtra(InstallerService.EXTRA_RUN_TOKEN, runToken);
        if (importUri != null) {
            installerIntent.putExtra(InstallerService.EXTRA_ARCHIVE_URI, importUri);
            installerIntent.putExtra(InstallerService.EXTRA_IMPORT_KIND, importKind);
            // The picker's read grant belongs to this activity's intent; pass it on to the service.
            installerIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        startForegroundService(installerIntent);
        // No BIND_AUTO_CREATE: the start above creates the service, and the binding connects as
        // soon as it exists, whichever of the two the system gets to first.
        if (!isInstallerServiceBound) {
            bindService(new Intent(this, InstallerService.class), installerServiceConnection, 0);
        }
    }

    private void showImportChoice() {
        binding.startTitleTv.setText(R.string.syx_import_title);
        binding.startMessageTv.setVisibility(View.GONE);
        binding.startProgressLpi.setVisibility(View.GONE);
        binding.startImportBodyTv.setVisibility(View.VISIBLE);
        binding.startImportButtonsLl.setVisibility(View.VISIBLE);
    }

    private void hideImportChoice() {
        binding.startImportBodyTv.setVisibility(View.GONE);
        binding.startImportButtonsLl.setVisibility(View.GONE);
    }

    private void handleTaskState(InstallerService.TaskState state) {
        if (state == null || installerService == null || failed) return;
        // Anything else is what an earlier task left in the service - including an earlier run of
        // this same task - and must not be read as this run finishing.
        if (installerService.getCurrentTask() != InstallerService.Task.PREPARE_BUNDLED_GAME
                || installerService.getCurrentRunToken() != runToken) return;

        if (state.isFinished) {
            releaseInstallerService();
            launchGame();
        } else if (state.isFinishedWithError) {
            releaseInstallerService();
            showError(state.title, state.message);
        } else {
            showProgress(state.message, state.progress, state.progressMax);
        }
    }

    private void showProgress(String message, int progress, int progressMax) {
        if (message != null) {
            binding.startMessageTv.setText(message);
            binding.startMessageTv.setVisibility(View.VISIBLE);
        }
        if (progress < 0) {
            binding.startProgressLpi.setIndeterminate(true);
        } else {
            binding.startProgressLpi.setIndeterminate(false);
            binding.startProgressLpi.setMax(progressMax);
            binding.startProgressLpi.setProgress(progress);
        }
    }

    private void showError(String title, String message) {
        failed = true;
        errorTitle = title != null ? title : getString(R.string.deephall_prepare_failed);
        errorMessage = message != null ? message : "";
        binding.startTitleTv.setText(errorTitle);
        binding.startMessageTv.setVisibility(View.GONE);
        binding.startProgressLpi.setVisibility(View.GONE);
        binding.startErrorTv.setText(errorMessage);
        binding.startErrorSv.setVisibility(View.VISIBLE);
        binding.startOpenLauncherMb.setVisibility(View.VISIBLE);
        // Still no game (a failed import, or files gone since): let the player pick again.
        if (!SyxGame.isImported()) binding.startImportButtonsLl.setVisibility(View.VISIBLE);
    }

    /**
     * Set by the Settings shortcut when the game is not ready yet: prepare it as usual, then open the
     * instance settings instead of starting the game, so the player never meets an empty launcher.
     */
    public static final String EXTRA_THEN_OPEN_SETTINGS = "com.udarmolota.syxdroid.extra.THEN_OPEN_SETTINGS";

    // Same intent and flags as LauncherFragment#launchGame. CLEAR_TASK takes this screen out of
    // the back stack, so Back from the game does not land here.
    private void launchGame() {
        if (getIntent().getBooleanExtra(EXTRA_THEN_OPEN_SETTINGS, false)) {
            Intent settings = new Intent(this, LauncherActivity.class);
            settings.setAction(LauncherActivity.ACTION_OPEN_SETTINGS);
            settings.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(settings);
            finish();
            return;
        }
        Intent intent = new Intent(this, GameActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra(GameActivity.EXTRA_GAME_INSTANCE_NAME, SyxGame.INSTANCE_NAME);
        startActivity(intent);
        finish();
    }

    private void openLauncher() {
        startActivity(new Intent(this, LauncherActivity.class));
        finish();
    }

    // Stops following the service and lets it stop, unless something has started it again since.
    private void releaseInstallerService() {
        InstallerService service = installerService;
        if (service != null) service.getTaskState().removeObservers(this);
        if (isInstallerServiceBound) {
            unbindService(installerServiceConnection);
            isInstallerServiceBound = false;
        }
        installerService = null;
        if (service != null) service.stopUnlessRestarted();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong(STATE_RUN_TOKEN, runToken);
        if (failed) {
            outState.putString(STATE_ERROR_TITLE, errorTitle);
            outState.putString(STATE_ERROR_MESSAGE, errorMessage);
        }
    }

    @Override
    protected void onDestroy() {
        // Leaving mid-run (Back, or the system) only drops the binding: the service keeps
        // working, and the next start picks the finished instance up.
        if (installerService != null) installerService.getTaskState().removeObservers(this);
        if (isInstallerServiceBound) {
            unbindService(installerServiceConnection);
            isInstallerServiceBound = false;
        }
        super.onDestroy();
    }
}
