package com.udarmolota.syxdroid;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CompletableFuture;

/**
 * Process-wide state of a Steam download (as in RimDroid). The download runs on its own thread and
 * outlives the Game page, so this holds the log, the progress and the running flag, and re-attaches
 * whichever page is on screen: the player can leave the page and come back without losing either.
 * It is the {@link SteamGameDownloader.Listener}; callbacks land here first, then go to the page.
 */
public final class SteamDownloadState implements SteamGameDownloader.Listener {

    private static final SteamDownloadState INSTANCE = new SteamDownloadState();
    public static SteamDownloadState get() { return INSTANCE; }
    private SteamDownloadState() {}

    /** The page currently showing the download; null when it is not on screen. */
    public interface View {
        void onLog(CharSequence fullLog);
        void onPercent(int percent, boolean indeterminate);
        void onFinished(String message, boolean ok);
        CompletableFuture<String> requestSteamGuardCode(boolean previousWrong, String email);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder log = new StringBuilder();
    private volatile boolean downloading;
    private volatile int percent = -1;
    private volatile boolean indeterminate = true;
    private View view;
    private Context appCtx;
    private volatile SteamGameDownloader active;
    /** A finished message no page has shown yet (the page was away). */
    private String pendingMessage;
    private boolean pendingOk;

    /** The zip the last download made in Download/SyxDroid, or null. */
    private volatile android.net.Uri lastZip;

    public boolean isDownloading() { return downloading; }
    public android.net.Uri getLastZip() { return lastZip; }
    public CharSequence getLog() { synchronized (log) { return log.toString(); } }
    public int getPercent() { return percent; }
    public boolean isIndeterminate() { return indeterminate; }

    /** Starts a download with these credentials; they go to the downloader and nowhere else. */
    public void start(Context context, String username, String password) {
        if (downloading) return;
        appCtx = context.getApplicationContext();
        synchronized (log) { log.setLength(0); }
        downloading = true;
        indeterminate = true;
        percent = -1;
        synchronized (this) { pendingMessage = null; }
        lastZip = null;
        DownloadKeepAliveService.start(appCtx, appCtx.getString(R.string.steam_notification));
        SteamGameDownloader d = new SteamGameDownloader(appCtx, username, password, this);
        active = d;
        new Thread(d, "syx-steam").start();
    }

    public void cancel() {
        SteamGameDownloader d = active;
        if (downloading && d != null) d.cancel();
    }

    /** Called on the main thread by the page when it appears or goes away. */
    public synchronized void setView(View v) {
        this.view = v;
        if (pendingMessage != null) main.post(this::deliverPending);
    }

    public synchronized void clearView(View v) { if (this.view == v) this.view = null; }

    private void appendLine(String line) {
        synchronized (log) {
            log.append(line).append('\n');
            if (log.length() > 40000) log.delete(0, log.length() - 30000);
        }
    }

    // ---- downloader callbacks (background threads) ----

    @Override
    public void onProgress(String message) {
        appendLine(message);
        main.post(() -> { View v = view; if (v != null) v.onLog(getLog()); });
    }

    @Override
    public void onPercent(int p) {
        percent = Math.max(0, Math.min(100, p));
        indeterminate = false;
        main.post(() -> { View v = view; if (v != null) v.onPercent(percent, false); });
    }

    @Override
    public void onGameZip(android.net.Uri zip, String name, long size) {
        lastZip = zip;
    }

    @Override
    public void onDone(String message, boolean ok) {
        appendLine((ok ? "✓ " : "✗ ") + message);
        downloading = false;
        active = null;
        synchronized (this) {
            pendingMessage = message;
            pendingOk = ok;
        }
        if (appCtx != null) DownloadKeepAliveService.stop(appCtx);
        main.post(this::deliverPending);
    }

    /** Delivers the result exactly once, now or when the page comes back. */
    private void deliverPending() {
        final View target;
        final String message;
        final boolean ok;
        synchronized (this) {
            target = view;
            message = pendingMessage;
            ok = pendingOk;
            if (target == null || message == null) return;
            pendingMessage = null;
        }
        target.onLog(getLog());
        target.onFinished(message, ok);
    }

    @Override
    public CompletableFuture<String> requestSteamGuardCode(boolean previousWrong, String email) {
        View v = this.view;
        if (v != null) return v.requestSteamGuardCode(previousWrong, email);
        return CompletableFuture.completedFuture("");
    }
}
