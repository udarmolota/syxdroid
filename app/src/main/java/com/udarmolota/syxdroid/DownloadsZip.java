package com.udarmolota.syxdroid;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a zip into the phone's Download/SyxDroid folder, where every launcher of the series puts
 * its downloads, through MediaStore: no storage permission is needed on Android 10+, and the file is
 * visible to file managers and to the system file picker ("+"). While it is being written it is
 * marked pending, so a half-written zip never shows up as a finished one.
 */
public final class DownloadsZip {
    /** Shown to the player: where the file is. */
    public static final String FOLDER = "Download/SyxDroid";

    public interface Progress {
        void update(long doneBytes, long totalBytes);
    }

    private DownloadsZip() {}

    /**
     * Zips the named top-level entries of root (files or folders) into Download/SyxDroid/name.
     * The data is mostly already compressed (data.zip, the jar), so entries are stored without
     * compression: as small as deflating would make it, at disk speed.
     */
    @NonNull
    public static Uri write(@NonNull Context context, @NonNull String name, @NonNull File root,
                            @NonNull String[] entries, @NonNull Progress progress) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SyxDroid");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Cannot create " + FOLDER + "/" + name);

        long total = 0;
        for (String e : entries) total += sizeOf(new File(root, e));
        long[] done = {0};
        boolean ok = false;
        try (OutputStream raw = resolver.openOutputStream(uri, "w")) {
            if (raw == null) throw new IOException("Cannot write " + FOLDER + "/" + name);
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(raw, 1 << 20))) {
                zip.setLevel(Deflater.NO_COMPRESSION);
                byte[] buffer = new byte[1 << 20];
                for (String e : entries) addTree(zip, new File(root, e), e, buffer, done, total, progress);
            }
            ok = true;
        } finally {
            if (!ok) {
                try { resolver.delete(uri, null, null); } catch (Exception ignored) {}
            }
        }
        values.clear();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
        return uri;
    }

    /** The size of a zip written above, or -1. */
    public static long size(@NonNull Context context, @NonNull Uri uri) {
        try (Cursor c = context.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) {}
        return -1;
    }

    private static void addTree(ZipOutputStream zip, File f, String path, byte[] buffer,
                                long[] done, long total, Progress progress) throws IOException {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids == null) return;
            java.util.Arrays.sort(kids);
            for (File k : kids) addTree(zip, k, path + "/" + k.getName(), buffer, done, total, progress);
            return;
        }
        if (!f.isFile()) return;
        zip.putNextEntry(new ZipEntry(path));
        try (InputStream in = new FileInputStream(f)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                zip.write(buffer, 0, n);
                done[0] += n;
                progress.update(done[0], total);
            }
        }
        zip.closeEntry();
    }

    private static long sizeOf(File f) {
        if (f.isFile()) return f.length();
        File[] kids = f.listFiles();
        long s = 0;
        if (kids != null) for (File k : kids) s += sizeOf(k);
        return s;
    }
}
