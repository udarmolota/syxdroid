package com.udarmolota.syxdroid;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Mods downloaded from the web (mod.io's file links), one after another on a background thread,
 * into the phone's Download/SyxDroid folder as ZIPs. Nothing is installed: the player installs them
 * in Mods ("Install ZIP"), so it is always clear what came from where and where it is.
 *
 * <p>Process-wide, like SteamDownloadState: the Mods online page adds downloads and shows the
 * progress while it is on screen, but leaving it (or the app) does not stop them; a foreground
 * notification keeps the process alive and shows the progress, and a last notification lists
 * what was saved.
 */
public final class ModDownloads {
    private static final String TAG = "SyxDroid/ModDownloads";
    private static final ModDownloads INSTANCE = new ModDownloads();
    public static ModDownloads get() { return INSTANCE; }
    private ModDownloads() {}

    /** The page showing the progress; null when it is not on screen. */
    public interface Listener {
        /** Main thread. status is null when nothing is downloading. */
        void onModDownloads(@Nullable String status);
    }

    private static final class Job {
        final String url, name, userAgent, cookies;
        Job(String url, String name, String userAgent, String cookies) {
            this.url = url; this.name = name; this.userAgent = userAgent; this.cookies = cookies;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    private final List<String> saved = new ArrayList<>(), failed = new ArrayList<>();
    private Context app;
    private Listener listener;
    private Thread worker;
    private volatile String status;
    private int total;

    public synchronized void setListener(@Nullable Listener l) {
        listener = l;
        if (l != null) l.onModDownloads(status);
    }

    public synchronized void clearListener(Listener l) { if (listener == l) listener = null; }

    public synchronized boolean isBusy() { return worker != null; }

    /** Adds a download; the first one starts the worker and the notification. */
    public synchronized void add(Context context, String url, String name, String userAgent, String cookies) {
        app = context.getApplicationContext();
        queue.add(new Job(url, safeName(name), userAgent, cookies));
        total++;
        if (worker == null) {
            saved.clear();
            failed.clear();
            try {
                DownloadKeepAliveService.start(app, app.getString(R.string.moddl_notification_start));
            } catch (RuntimeException e) {
                Log.w(TAG, "no foreground service; downloading anyway", e);
            }
            worker = new Thread(this::run, "syx-mod-downloads");
            worker.start();
        }
        publish(app.getString(R.string.moddl_status, done() + 1, total, queue.peek() != null ? queue.peek().name : name, 0));
    }

    private synchronized int done() { return saved.size() + failed.size(); }

    private void run() {
        while (true) {
            Job job;
            int index, count;
            synchronized (this) {
                job = queue.poll();
                if (job == null) {
                    finish();
                    return;
                }
                index = done() + 1;
                count = total;
            }
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(job.url).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(20000);
                c.setReadTimeout(60000);
                if (job.userAgent != null) c.setRequestProperty("User-Agent", job.userAgent);
                if (job.cookies != null) c.setRequestProperty("Cookie", job.cookies);
                try {
                    int code = c.getResponseCode();
                    if (code / 100 != 2) throw new java.io.IOException("HTTP " + code);
                    long size = c.getContentLengthLong();
                    final long[] last = {-1};
                    android.net.Uri uri;
                    try (java.io.InputStream in = c.getInputStream()) {
                        uri = DownloadsZip.save(app, job.name + ".zip", in, size, (d, t) -> {
                            int pct = t > 0 ? (int) (d * 100 / t) : -1;
                            if (pct != last[0]) {
                                last[0] = pct;
                                String s = app.getString(R.string.moddl_status, index, count, job.name, Math.max(pct, 0));
                                publish(s);
                                DownloadKeepAliveService.update(app, s, pct);
                            }
                        });
                    }
                    String file = DownloadsZip.displayName(app, uri);
                    synchronized (this) { saved.add(file != null ? file : job.name + ".zip"); }
                } finally {
                    c.disconnect();
                }
            } catch (Exception e) {
                Log.w(TAG, "download failed: " + job.url, e);
                synchronized (this) { failed.add(job.name + " — " + e.getMessage()); }
            }
        }
    }

    /** Called by the worker with the lock held, when the queue is empty. */
    private void finish() {
        worker = null;
        total = 0;
        DownloadKeepAliveService.stop(app);
        StringBuilder text = new StringBuilder();
        if (!saved.isEmpty()) {
            text.append(app.getString(R.string.moddl_done, DownloadsZip.FOLDER));
            for (String s : saved) text.append("\n• ").append(s);
        }
        if (!failed.isEmpty()) {
            if (text.length() > 0) text.append("\n\n");
            text.append(app.getString(R.string.moddl_failed));
            for (String s : failed) text.append("\n• ").append(s);
        }
        DownloadKeepAliveService.notifyDone(app,
                app.getString(R.string.moddl_done_title, saved.size()), text.toString());
        String summary = text.toString();
        publish(null);
        main.post(() -> android.widget.Toast.makeText(app, summary, android.widget.Toast.LENGTH_LONG).show());
    }

    private void publish(@Nullable String s) {
        status = s;
        main.post(() -> {
            Listener l;
            synchronized (this) { l = listener; }
            if (l != null) l.onModDownloads(status);
        });
    }

    /** A file name from the mod's title: no path characters, not empty. */
    private static String safeName(String name) {
        String n = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (n.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) n = n.substring(0, n.length() - 4);
        return n.isEmpty() ? "mod" : n;
    }
}
