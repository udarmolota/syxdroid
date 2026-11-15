package com.udarmolota.syxdroid;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.udarmolota.syxdroid.game.GameInstance;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import in.dragonbra.javasteam.depotdownloader.DepotDownloader;
import in.dragonbra.javasteam.depotdownloader.IDownloadListener;
import in.dragonbra.javasteam.depotdownloader.data.AppItem;
import in.dragonbra.javasteam.depotdownloader.data.DownloadItem;
import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.steam.authentication.AuthPollResult;
import in.dragonbra.javasteam.steam.authentication.AuthSessionDetails;
import in.dragonbra.javasteam.steam.authentication.CredentialsAuthSession;
import in.dragonbra.javasteam.steam.authentication.IAuthenticator;
import in.dragonbra.javasteam.steam.authentication.SteamAuthentication;
import in.dragonbra.javasteam.steam.handlers.steamapps.License;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.LicenseListCallback;
import in.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails;
import in.dragonbra.javasteam.steam.handlers.steamuser.SteamUser;
import in.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback;
import in.dragonbra.javasteam.steam.steamclient.SteamClient;
import in.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager;
import in.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback;
import in.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback;

/**
 * Downloads the player's own Songs of Syx from Steam to a zip in Download/SyxDroid, on the phone,
 * with no PC; instances are then made from that zip like from any other ("+").
 * A trimmed port of RimDroid's SteamDownloadSpike (JavaSteam + its DepotDownloader).
 *
 * <p>Sign-in: the player types their Steam login and password; Steam then asks for approval in the
 * Steam Mobile app (a push), or for a Steam Guard code, which {@link Listener} asks the player for.
 * The password is used for that one sign-in and the session token lives only in memory: nothing is
 * stored. A dropped connection reconnects with the token, without a second approval.
 *
 * <p>Download: the Linux depot of app 1162750 on the public branch, newest build. The jar and the
 * data are the same on every platform; Linux is the depot SyxDroid was developed against. The
 * depot also carries a desktop JRE (~150 MB) that this DepotDownloader version cannot skip, so it
 * is downloaded too but left out of the zip: only SongsOfSyx.jar and base/ go in.
 * The work folder sits in the app's own storage and is kept between attempts, so a download that
 * stops resumes where it was instead of starting again; it is deleted once the zip is written.
 */
public class SteamGameDownloader implements Runnable, IDownloadListener {

    private static final String TAG = "SyxDroid/SteamDL";

    public static final int SOS_APP_ID = 1162750;
    /** Songs of Syx's Linux depot (the one with the native Linux launcher; the jar is shared). */
    public static final int SOS_LINUX_DEPOT = 1162755;
    private static final String BRANCH = "public";

    private static final int MAX_DOWNLOAD_ATTEMPTS = 3;
    /** Re-tries of the first sign-in if the connection drops while the player approves it. */
    private static final int MAX_AUTH_ATTEMPTS = 3;
    private static final int DL_MAX_DOWNLOADS = 4;   // concurrent chunk downloads
    private static final int DL_MAX_DECOMPRESS = 2;  // concurrent chunk decompressions (memory)

    public interface Listener {
        /** Steam Guard code (only when the account is not approved through the Steam Mobile push). */
        CompletableFuture<String> requestSteamGuardCode(boolean previousWrong, String email);
        /** A line for the on-screen log. */
        void onProgress(String message);
        /** Overall progress 0..100. */
        void onPercent(int percent);
        /** The finished zip in Download/SyxDroid, just before onDone. */
        void onGameZip(android.net.Uri zip, String name, long size);
        /** Terminal message, success or error; exactly once. */
        void onDone(String message, boolean ok);
    }

    private final Context appContext;
    private final String username;
    private final String password;
    private final Listener listener;

    private SteamClient steamClient;
    private CallbackManager manager;
    private SteamUser steamUser;
    private List<License> licenseList;

    private volatile boolean running;
    private volatile boolean cancelled;
    private volatile boolean doneEmitted;
    private volatile Thread workerThread;
    private String accountName;
    private String refreshToken;                   // in memory only
    private int authAttempts;
    private int downloadAttempts;
    private volatile boolean downloadStarted;
    private volatile boolean downloadInProgress;
    private volatile boolean downloadCompleted;
    private volatile Throwable lastError;

    public SteamGameDownloader(Context context, String username, String password, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.username = username;
        this.password = password;
        this.listener = listener;
    }

    /** The resumable work folder: in the app's storage, next to the instances. */
    static File workDir() {
        return new File(AppStorage.requireSingleton().getHomePath(), "steam_download");
    }

    private void progress(String m) {
        Log.i(TAG, m);
        listener.onProgress(m);
    }

    private void done(String m, boolean ok) {
        if (doneEmitted) return;
        doneEmitted = true;
        Log.i(TAG, "DONE (" + ok + "): " + m);
        listener.onDone(m, ok);
    }

    /** Stops the download: breaks the blocking wait and the connection loop. */
    public void cancel() {
        cancelled = true;
        running = false;
        downloadCompleted = true;            // no reconnect/retry from the disconnect handler
        Thread w = workerThread;
        if (w != null) w.interrupt();
        try { if (steamClient != null) steamClient.disconnect(); } catch (Throwable ignored) {}
        done(appContext.getString(R.string.steam_cancelled), false);
    }

    private static boolean fullBouncyCastleInstalled;

    /**
     * JavaSteam saves depot manifests with {@code MessageDigest.getInstance("SHA-1", "BC")}. Android's
     * own provider named "BC" is a trimmed BouncyCastle without SHA-1, and adding the full one
     * (bcprov) under the same name is ignored while that one exists. So the system "BC" is removed
     * and the full one appended (as in RimDroid). Appended, it has the lowest priority: it answers
     * only code that asks for "BC" by name, never default lookups (Conscrypt keeps doing HTTPS).
     */
    private static synchronized void installFullBouncyCastle() {
        if (fullBouncyCastleInstalled) return;
        try {
            java.security.Security.removeProvider("BC");
            java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
            fullBouncyCastleInstalled = true;
            Log.i(TAG, "Installed the full BouncyCastle as provider BC");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to install the full BouncyCastle provider", t);
        }
    }

    @Override
    public void run() {
        try {
            installFullBouncyCastle();
            steamClient = new SteamClient();
            manager = new CallbackManager(steamClient);
            steamUser = steamClient.getHandler(SteamUser.class);

            manager.subscribe(ConnectedCallback.class, this::onConnected);
            manager.subscribe(DisconnectedCallback.class, this::onDisconnected);
            manager.subscribe(LoggedOnCallback.class, this::onLoggedOn);
            manager.subscribe(LicenseListCallback.class, this::onLicenseList);

            running = true;
            progress(appContext.getString(R.string.steam_tip_network));
            progress("Connecting to Steam…");
            steamClient.connect();
            while (running) {
                manager.runWaitCallbacks(1000L);
            }
            // The import runs on the worker thread and can outlast the Steam connection.
            Thread w = workerThread;
            if (w != null && w != Thread.currentThread()) w.join();
            if (!doneEmitted) done(appContext.getString(R.string.steam_stopped), false);
        } catch (Throwable t) {
            Log.e(TAG, "SteamGameDownloader crashed", t);
            done("crash: " + describe(t), false);
        } finally {
            // Always tear the connection down: an orphaned SteamClient's reader thread throws an
            // uncaught exception when the network changes later, which takes the whole app down.
            try { if (steamClient != null) steamClient.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private void onConnected(ConnectedCallback cb) {
        try {
            if (refreshToken != null) {
                progress("Reconnected. Logging on with the current session…");
                logOnWithToken();
                return;
            }
            progress("Connected. Signing in as '" + username + "'…");
            authAttempts++;
            AuthSessionDetails details = new AuthSessionDetails();
            details.username = username;
            details.password = password;
            details.persistentSession = false;          // ephemeral token, nothing stored
            details.deviceFriendlyName = "SyxDroid";
            details.authenticator = new PushAuthenticator();

            CredentialsAuthSession session =
                    new SteamAuthentication(steamClient).beginAuthSessionViaCredentials(details).get();
            progress(appContext.getString(R.string.steam_approve_in_app));

            AuthPollResult poll = session.pollingWaitForResult().get();
            accountName = poll.getAccountName();
            refreshToken = poll.getRefreshToken();
            logOnWithToken();
        } catch (Throwable t) {
            Log.e(TAG, "Auth failed", t);
            // A CancellationException is the connection dropping while the player approved the
            // sign-in in the Steam app, not a real failure: reconnect and ask again.
            boolean connectionDropped =
                    (t instanceof java.util.concurrent.CancellationException)
                            || (t.getCause() instanceof java.util.concurrent.CancellationException);
            if (connectionDropped && !cancelled && authAttempts < MAX_AUTH_ATTEMPTS) {
                progress("Sign-in interrupted by a connection drop, reconnecting (attempt "
                        + authAttempts + "/" + MAX_AUTH_ATTEMPTS + ")…");
            } else {
                done(appContext.getString(R.string.steam_auth_failed, describe(t)), false);
                running = false;
            }
        }
    }

    private void logOnWithToken() {
        LogOnDetails lod = new LogOnDetails();
        lod.setUsername(accountName);
        lod.setAccessToken(refreshToken);
        lod.setLoginID(149);
        progress("Logging in…");
        steamUser.logOn(lod);
    }

    private void onDisconnected(DisconnectedCallback cb) {
        Log.i(TAG, "Disconnected (userInitiated=" + cb.isUserInitiated() + ")");
        if (cb.isUserInitiated() || downloadCompleted || downloadAttempts >= MAX_DOWNLOAD_ATTEMPTS
                || (refreshToken == null && authAttempts >= MAX_AUTH_ATTEMPTS)) {
            running = false;
            if (!downloadCompleted && !cb.isUserInitiated()) {
                done(appContext.getString(R.string.steam_connection_lost,
                        lastError != null ? describe(lastError) : ""), false);
            }
            return;
        }
        progress("Connection lost, reconnecting (attempt " + (downloadAttempts + 1) + ")…");
        downloadStarted = false;
        try { Thread.sleep(2000L); } catch (InterruptedException ignored) {}
        if (!running) return;
        steamClient.connect();   // onConnected reuses the token: no second approval
    }

    private void onLoggedOn(LoggedOnCallback cb) {
        if (cb.getResult() != EResult.OK) {
            done(appContext.getString(R.string.steam_auth_failed, String.valueOf(cb.getResult())), false);
            running = false;
            return;
        }
        progress("Logged on. Checking your games…");
    }

    private void onLicenseList(LicenseListCallback cb) {
        if (cb.getResult() != EResult.OK) {
            done("license list failed: " + cb.getResult(), false);
            running = false;
            return;
        }
        licenseList = cb.getLicenseList();
        if (downloadStarted || downloadInProgress || downloadCompleted) return;
        downloadStarted = true;
        Thread t = new Thread(this::download, "syx-depot-dl");
        workerThread = t;
        t.start();
    }

    private void download() {
        downloadInProgress = true;
        downloadAttempts++;
        lastError = null;
        File work = workDir();
        if (downloadAttempts == 1 && new File(work, ".DepotDownloader").exists())
            progress(appContext.getString(R.string.steam_resuming));
        progress("[attempt " + downloadAttempts + "] app " + SOS_APP_ID + ", depot " + SOS_LINUX_DEPOT
                + ", branch " + BRANCH);
        try (DepotDownloader dd = new DepotDownloader(steamClient, licenseList, /* debug */ false,
                /* useLanCache */ false, DL_MAX_DOWNLOADS, DL_MAX_DECOMPRESS)) {
            dd.addListener(this);
            AppItem item = new AppItem(
                    /* appId */ SOS_APP_ID,
                    /* installToGameNameDirectory */ false,
                    /* installDirectory */ work.getAbsolutePath(),   // absolute: required on Android
                    /* branch */ BRANCH,
                    /* branchPassword */ "",
                    /* downloadAllPlatforms */ false,
                    /* os */ "linux",
                    /* downloadAllArchs */ false,
                    /* osArch */ "64",
                    /* downloadAllLanguages */ false,
                    /* language */ "english",
                    /* lowViolence */ false,
                    /* depot */ List.of(SOS_LINUX_DEPOT));         // newest manifest of the branch
            dd.add(item);
            dd.finishAdding();
            // getCompletion().get(), not awaitCompletion(): the latter ignores the interrupt cancel()
            // sends, and the library's threads kept downloading after a cancel (RimDroid).
            dd.getCompletion().get();
            dd.removeListener(this);

            if (lastError == null) {
                downloadCompleted = true;
                importDownloaded(work);
            } else {
                progress("attempt " + downloadAttempts + " failed: " + describe(lastError));
                if (downloadAttempts >= MAX_DOWNLOAD_ATTEMPTS) {
                    done(appContext.getString(R.string.steam_download_failed, describe(lastError)), false);
                    running = false;
                    try { steamUser.logOff(); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Download attempt crashed", t);
            lastError = t;
            if (cancelled) return;
            if (downloadAttempts >= MAX_DOWNLOAD_ATTEMPTS) {
                done(appContext.getString(R.string.steam_download_failed, describe(t)), false);
                running = false;
                try { steamUser.logOff(); } catch (Throwable ignored) {}
            }
        } finally {
            downloadInProgress = false;
        }
    }

    /**
     * The finished depot becomes a zip in Download/SyxDroid, like every launcher of the series:
     * SongsOfSyx.jar and base/ only (the desktop JRE and launchers are left out). The player makes
     * instances from that zip, now (the button at the end of the download) or any time later with
     * "+". The work folder in the app's storage is deleted once the zip is complete.
     */
    private void importDownloaded(File work) {
        try {
            if (!new File(work, GAME_JAR).isFile()) {
                // Most often: the account does not own Songs of Syx, so there was nothing to fetch.
                finish(appContext.getString(R.string.steam_no_game_files), false);
                return;
            }
            progress(appContext.getString(R.string.steam_packing));
            String name = "SongsOfSyx_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmm",
                    java.util.Locale.US).format(new java.util.Date()) + ".zip";
            android.net.Uri zip = DownloadsZip.write(appContext, name, work, new String[]{GAME_JAR, BASE_DIR},
                    (done, total) -> listener.onPercent(total > 0 ? (int) (done * 100 / total) : 0));
            long size = DownloadsZip.size(appContext, zip);
            FileUtils.deleteDirectory(work);
            listener.onGameZip(zip, name, size);
            finish(appContext.getString(R.string.steam_done, DownloadsZip.FOLDER + "/" + name, size >> 20), true);
        } catch (Throwable t) {
            Log.e(TAG, "Packing the download failed", t);
            finish(appContext.getString(R.string.steam_download_failed, describe(t)), false);
        }
    }

    private static final String GAME_JAR = "SongsOfSyx.jar";
    private static final String BASE_DIR = "base";

    private void finish(String message, boolean ok) {
        downloadInProgress = false;
        downloadCompleted = true;
        done(message, ok);
        running = false;
        try { steamUser.logOff(); } catch (Throwable ignored) {}
    }

    // ---- IDownloadListener ----
    @Override public void onItemAdded(@NonNull DownloadItem item) { Log.i(TAG, "queued app " + item.getAppId()); }
    @Override public void onDownloadStarted(@NonNull DownloadItem item) { progress(appContext.getString(R.string.steam_downloading)); }
    @Override public void onDownloadCompleted(@NonNull DownloadItem item) { progress("Download completed."); }
    @Override public void onDownloadFailed(@NonNull DownloadItem item, @NonNull Throwable error) {
        Log.e(TAG, "download failed app " + item.getAppId(), error);
        lastError = error;
        progress("FAILED: " + describe(error));
    }
    @Override public void onStatusUpdate(@NonNull String message) { Log.i(TAG, message); }
    @Override public void onFileCompleted(int depotId, @NonNull String fileName, float depotPercentComplete) {
        Log.i(TAG, String.format("depot %d: %.1f%% (%s)", depotId, depotPercentComplete * 100f, fileName));
    }
    @Override public void onChunkCompleted(int depotId, float depotPercentComplete, long compressedBytes, long uncompressedBytes) {
        listener.onPercent(Math.round(depotPercentComplete * 100f));
    }
    @Override public void onDepotCompleted(int depotId, long compressedBytes, long uncompressedBytes) {
        progress("Depot done: " + (uncompressedBytes >> 20) + " MB");
    }

    /** Exception class + message + first cause: getMessage() alone is often null. */
    static String describe(Throwable t) {
        if (t == null) return "null";
        StringBuilder sb = new StringBuilder(t.getClass().getSimpleName());
        if (t.getMessage() != null) sb.append(": ").append(t.getMessage());
        Throwable c = t.getCause();
        if (c != null && c != t) {
            sb.append(" <- ").append(c.getClass().getSimpleName());
            if (c.getMessage() != null) sb.append(": ").append(c.getMessage());
        }
        return sb.toString();
    }

    /** Approves through the Steam Mobile push when possible; otherwise asks for a Steam Guard code. */
    private class PushAuthenticator implements IAuthenticator {
        @Override
        public CompletableFuture<Boolean> acceptDeviceConfirmation() {
            return CompletableFuture.completedFuture(Boolean.TRUE);
        }

        @Override
        public CompletableFuture<String> getDeviceCode(boolean previousCodeWasIncorrect) {
            return listener.requestSteamGuardCode(previousCodeWasIncorrect, null);
        }

        @Override
        public CompletableFuture<String> getEmailCode(String email, boolean previousCodeWasIncorrect) {
            return listener.requestSteamGuardCode(previousCodeWasIncorrect, email);
        }
    }
}
