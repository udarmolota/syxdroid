package com.udarmolota.syxdroid;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.provider.DocumentsContract;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.udarmolota.syxdroid.game.GameInstance;
import com.udarmolota.syxdroid.game.PresetManager;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Imports the player's own copy of Songs of Syx into the instance's game dir.
 *
 * <p>Two sources, both picked by the player through the system file picker:
 * <ul>
 *   <li>{@link #KIND_FOLDER}: a document tree (the game folder, or a folder up to two levels
 *       above it, e.g. Steam's {@code common});</li>
 *   <li>{@link #KIND_ZIP}: a zip of the game folder, with or without a wrapper folder.</li>
 * </ul>
 * The Windows Steam folder and the Linux depot both work: the jar and base/ are the same.
 *
 * <p>Only what the game needs is copied: {@code SongsOfSyx.jar} and {@code base/}. The bundled
 * desktop JRE, the native launchers, Steam files and {@code info/} (sources, mod guide) are left
 * out. Everything is copied into a staging folder first and checked; only then are the old game
 * files replaced, jar last, since the jar is what marks the game as imported. The player's saves
 * and mods live outside the game dir ({@code .local/share/songsofsyx} under the instance home)
 * and are never touched.
 */
final class SyxImporter {
    static final String KIND_FOLDER = "folder";
    static final String KIND_ZIP = "zip";

    private static final String GAME_JAR = PresetManager.SYX_GAME_JAR;
    private static final String BASE_DIR = "base";
    /** A class every game build has; its absence means the jar is not Songs of Syx. */
    private static final String MAIN_CLASS_ENTRY = "init/MainProcess.class";
    private static final String STAGING_DIR_NAME = "import-staging";
    /** How deep below the picked folder SongsOfSyx.jar is looked for. */
    private static final int MAX_FOLDER_DEPTH = 2;
    /** Head room on top of the game's own size: the JVM, logs and the game's first saves. */
    private static final long FREE_SPACE_MARGIN = 64L << 20;

    private SyxImporter() {}

    static void importGame(@NonNull Context context, @NonNull Uri uri, @NonNull String kind,
                           @NonNull GameInstance instance, @NonNull TaskProgressListener listener)
            throws IOException {
        File homeDir = new File(instance.getHomePath());
        File gameDir = new File(instance.getGamePath());
        File staging = new File(homeDir, STAGING_DIR_NAME);
        if (staging.exists()) FileUtils.deleteDirectory(staging);
        if (!staging.mkdirs()) throw new IOException("Failed to create " + staging);

        try {
            listener.onProgressUpdate(context.getString(R.string.syx_import_copying), -1, 0);
            if (KIND_FOLDER.equals(kind)) {
                copyFromTree(context, uri, staging, listener);
            } else if (KIND_ZIP.equals(kind)) {
                copyFromZip(context, uri, staging, listener);
            } else {
                throw new IOException("Unknown import kind " + kind);
            }

            listener.onProgressUpdate(context.getString(R.string.syx_import_checking), -1, 0);
            verify(staging);
            replaceGameFiles(staging, gameDir);
        } finally {
            if (staging.exists()) FileUtils.deleteDirectory(staging);
        }
    }

    /** Whether a path relative to the game root is one of the files we take. */
    private static boolean isWanted(String relPath) {
        return relPath.equals(GAME_JAR) || relPath.startsWith(BASE_DIR + "/");
    }

    // -------------------- folder (document tree) --------------------

    private static final class Doc {
        final String id;
        final String name;
        final boolean isDir;
        final long size;

        Doc(String id, String name, boolean isDir, long size) {
            this.id = id;
            this.name = name;
            this.isDir = isDir;
            this.size = size;
        }
    }

    private static final class Pending {
        final Doc doc;
        final String relPath;

        Pending(Doc doc, String relPath) {
            this.doc = doc;
            this.relPath = relPath;
        }
    }

    private static void copyFromTree(Context context, Uri treeUri, File staging,
                                     TaskProgressListener listener) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String rootId = DocumentsContract.getTreeDocumentId(treeUri);

        String gameRootId = findGameRoot(resolver, treeUri, rootId);
        if (gameRootId == null)
            throw new IOException(context.getString(R.string.syx_import_no_jar_in_folder));

        // Collect everything we take, with sizes, before copying anything: the total drives the
        // free-space check and the progress bar.
        List<Pending> files = new ArrayList<>();
        long total = 0;
        boolean hasBase = false;
        for (Doc child : listChildren(resolver, treeUri, gameRootId)) {
            if (!child.isDir && child.name.equals(GAME_JAR)) {
                files.add(new Pending(child, GAME_JAR));
                total += Math.max(child.size, 0);
            } else if (child.isDir && child.name.equals(BASE_DIR)) {
                hasBase = true;
                ArrayDeque<Pending> dirs = new ArrayDeque<>();
                dirs.add(new Pending(child, BASE_DIR));
                while (!dirs.isEmpty()) {
                    Pending dir = dirs.poll();
                    for (Doc d : listChildren(resolver, treeUri, dir.doc.id)) {
                        String rel = dir.relPath + "/" + d.name;
                        if (d.isDir) {
                            dirs.add(new Pending(d, rel));
                        } else {
                            files.add(new Pending(d, rel));
                            total += Math.max(d.size, 0);
                        }
                    }
                }
            }
        }
        if (!hasBase) throw new IOException(context.getString(R.string.syx_import_no_base));
        checkFreeSpace(context, staging, total);

        Progress progress = new Progress(listener, total);
        byte[] buffer = new byte[1 << 20];
        for (Pending f : files) {
            File out = safeTarget(staging, f.relPath);
            Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, f.doc.id);
            try (InputStream in = resolver.openInputStream(docUri)) {
                if (in == null) throw new IOException("Cannot open " + f.relPath);
                copy(in, out, buffer, progress);
            }
        }
    }

    /** The document id of the folder holding SongsOfSyx.jar, searching breadth-first, or null. */
    @Nullable
    private static String findGameRoot(ContentResolver resolver, Uri treeUri, String rootId) throws IOException {
        ArrayDeque<Pending> queue = new ArrayDeque<>();
        queue.add(new Pending(new Doc(rootId, "", true, 0), ""));
        while (!queue.isEmpty()) {
            Pending dir = queue.poll();
            int depth = dir.relPath.isEmpty() ? 0 : dir.relPath.split("/").length;
            List<Doc> children = listChildren(resolver, treeUri, dir.doc.id);
            for (Doc d : children) {
                if (!d.isDir && d.name.equals(GAME_JAR)) return dir.doc.id;
            }
            if (depth >= MAX_FOLDER_DEPTH) continue;
            for (Doc d : children) {
                if (d.isDir) queue.add(new Pending(d, dir.relPath.isEmpty() ? d.name : dir.relPath + "/" + d.name));
            }
        }
        return null;
    }

    private static List<Doc> listChildren(ContentResolver resolver, Uri treeUri, String parentId) throws IOException {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
        };
        List<Doc> out = new ArrayList<>();
        try (Cursor c = resolver.query(childrenUri, projection, null, null, null)) {
            if (c == null) throw new IOException("Cannot list folder " + parentId);
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                long size = c.isNull(3) ? -1 : c.getLong(3);
                if (id == null || name == null) continue;
                out.add(new Doc(id, name, DocumentsContract.Document.MIME_TYPE_DIR.equals(mime), size));
            }
        }
        return out;
    }

    // -------------------- zip --------------------

    private static void copyFromZip(Context context, Uri zipUri, File staging,
                                    TaskProgressListener listener) throws IOException {
        // Random access first: the central directory gives every name and size at once. Local
        // files and most providers hand out a seekable descriptor; some cloud providers do not,
        // and those get two streaming passes instead.
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(zipUri, "r")) {
            if (pfd != null) {
                try (FileInputStream fis = new FileInputStream(pfd.getFileDescriptor());
                     ZipFile zip = ZipFile.builder().setSeekableByteChannel(fis.getChannel()).get()) {
                    copyFromZipFile(context, zip, staging, listener);
                    return;
                } catch (IOException | RuntimeException notSeekable) {
                    // Fall through to streaming, but only if nothing was written yet.
                    if (hasAnyFile(staging)) throw notSeekable instanceof IOException
                            ? (IOException) notSeekable : new IOException(notSeekable);
                }
            }
        } catch (IOException | RuntimeException e) {
            if (hasAnyFile(staging)) throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
        copyFromZipStream(context, zipUri, staging, listener);
    }

    private static void copyFromZipFile(Context context, ZipFile zip, File staging,
                                        TaskProgressListener listener) throws IOException {
        List<String> names = new ArrayList<>();
        for (Enumeration<ZipArchiveEntry> e = zip.getEntries(); e.hasMoreElements(); ) {
            names.add(e.nextElement().getName());
        }
        String prefix = findZipPrefix(names);
        if (prefix == null) throw new IOException(context.getString(R.string.syx_import_no_jar_in_zip));

        List<ZipArchiveEntry> wanted = new ArrayList<>();
        long total = 0;
        for (Enumeration<ZipArchiveEntry> e = zip.getEntries(); e.hasMoreElements(); ) {
            ZipArchiveEntry entry = e.nextElement();
            String rel = relativeTo(prefix, entry.getName());
            if (rel == null || entry.isDirectory() || !isWanted(rel)) continue;
            wanted.add(entry);
            total += Math.max(entry.getSize(), 0);
        }
        checkFreeSpace(context, staging, total);

        Progress progress = new Progress(listener, total);
        byte[] buffer = new byte[1 << 20];
        for (ZipArchiveEntry entry : wanted) {
            File out = safeTarget(staging, relativeTo(prefix, entry.getName()));
            try (InputStream in = zip.getInputStream(entry)) {
                copy(in, out, buffer, progress);
            }
        }
    }

    private static void copyFromZipStream(Context context, Uri zipUri, File staging,
                                          TaskProgressListener listener) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        long zipSize;
        try {
            zipSize = FileUtils.queryFileSize(resolver, zipUri);
        } catch (RuntimeException e) {
            zipSize = -1;
        }

        // Pass 1: names only, to find where SongsOfSyx.jar sits.
        List<String> names = new ArrayList<>();
        try (InputStream raw = resolver.openInputStream(zipUri)) {
            if (raw == null) throw new IOException("Cannot open the zip");
            ZipArchiveInputStream zin = new ZipArchiveInputStream(new BufferedInputStream(raw, 1 << 20));
            ZipArchiveEntry entry;
            while ((entry = zin.getNextEntry()) != null) names.add(entry.getName());
        }
        String prefix = findZipPrefix(names);
        if (prefix == null) throw new IOException(context.getString(R.string.syx_import_no_jar_in_zip));
        // The unpacked size is not known up front here; the zip's own size is a fair floor.
        if (zipSize > 0) checkFreeSpace(context, staging, zipSize);

        // Pass 2: extract, with progress over the compressed bytes read.
        Progress progress = new Progress(listener, zipSize);
        byte[] buffer = new byte[1 << 20];
        try (InputStream raw = resolver.openInputStream(zipUri)) {
            if (raw == null) throw new IOException("Cannot open the zip");
            CountingInputStream counting = new CountingInputStream(new BufferedInputStream(raw, 1 << 20));
            ZipArchiveInputStream zin = new ZipArchiveInputStream(counting);
            ZipArchiveEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String rel = relativeTo(prefix, entry.getName());
                if (rel == null || entry.isDirectory() || !isWanted(rel)) continue;
                File out = safeTarget(staging, rel);
                copy(zin, out, buffer, null);
                progress.set(counting.count);
            }
        }
    }

    /**
     * The folder prefix (ending in "/", or "" at the top) of the shallowest SongsOfSyx.jar among
     * the names, or null when there is none.
     */
    @Nullable
    private static String findZipPrefix(List<String> names) {
        String best = null;
        for (String raw : names) {
            String name = raw.replace('\\', '/');
            if (!(name.equals(GAME_JAR) || name.endsWith("/" + GAME_JAR))) continue;
            String prefix = name.substring(0, name.length() - GAME_JAR.length());
            if (best == null || prefix.length() < best.length()) best = prefix;
        }
        return best;
    }

    /** name relative to prefix, or null when it is outside it. */
    @Nullable
    private static String relativeTo(String prefix, String rawName) {
        String name = rawName.replace('\\', '/');
        if (!name.startsWith(prefix)) return null;
        return name.substring(prefix.length());
    }

    // -------------------- shared --------------------

    /** The file for relPath inside root, refusing names that would escape it (zip slip). */
    private static File safeTarget(File root, String relPath) throws IOException {
        for (String part : relPath.split("/")) {
            if (part.equals("..")) throw new IOException("Refusing path " + relPath);
        }
        File out = new File(root, relPath);
        String rootPath = root.getCanonicalPath() + File.separator;
        if (!out.getCanonicalPath().startsWith(rootPath)) throw new IOException("Refusing path " + relPath);
        File parent = out.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Failed to create " + parent);
        return out;
    }

    private static void copy(InputStream in, File out, byte[] buffer, @Nullable Progress progress) throws IOException {
        try (OutputStream os = new FileOutputStream(out)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                os.write(buffer, 0, n);
                if (progress != null) progress.add(n);
            }
        }
    }

    private static void checkFreeSpace(Context context, File dir, long needed) throws IOException {
        long available = new StatFs(dir.getPath()).getAvailableBytes();
        if (available < needed + FREE_SPACE_MARGIN) {
            throw new IOException(context.getString(R.string.syx_import_no_space,
                    (needed + FREE_SPACE_MARGIN) >> 20, available >> 20));
        }
    }

    private static boolean hasAnyFile(File dir) {
        String[] list = dir.list();
        return list != null && list.length > 0;
    }

    /** The staged copy must be a real Songs of Syx: the jar has the game's main class, base/ has data.zip. */
    private static void verify(File staging) throws IOException {
        File jar = new File(staging, GAME_JAR);
        if (!jar.isFile()) throw new IOException(GAME_JAR + " was not copied");
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar)) {
            if (zip.getEntry(MAIN_CLASS_ENTRY) == null)
                throw new IOException(GAME_JAR + " has no " + MAIN_CLASS_ENTRY + ": not Songs of Syx?");
        }
        if (!new File(staging, BASE_DIR + "/data.zip").isFile())
            throw new IOException(BASE_DIR + "/data.zip is missing");
    }

    /**
     * Swaps the staged files in. The old jar goes first and the new jar comes last, so an
     * interruption anywhere in between leaves an instance that reads as "not imported" and asks
     * again, never a half-replaced game that tries to start.
     */
    private static void replaceGameFiles(File staging, File gameDir) throws IOException {
        if (!gameDir.isDirectory() && !gameDir.mkdirs()) throw new IOException("Failed to create " + gameDir);
        File oldJar = new File(gameDir, GAME_JAR);
        if (oldJar.exists() && !oldJar.delete()) throw new IOException("Failed to delete " + oldJar);
        File oldBase = new File(gameDir, BASE_DIR);
        if (oldBase.exists()) FileUtils.deleteDirectory(oldBase);

        if (!new File(staging, BASE_DIR).renameTo(oldBase))
            throw new IOException("Failed to move " + BASE_DIR + " into " + gameDir);
        if (!new File(staging, GAME_JAR).renameTo(oldJar))
            throw new IOException("Failed to move " + GAME_JAR + " into " + gameDir);
    }

    /** Progress in KiB, so a few hundred MB fit an int. Unknown total: an indeterminate bar. */
    private static final class Progress {
        private final TaskProgressListener listener;
        private final long total;
        private long done;

        Progress(TaskProgressListener listener, long total) {
            this.listener = listener;
            this.total = total;
        }

        void add(long n) {
            set(done + n);
        }

        void set(long value) {
            done = value;
            if (total > 0) {
                listener.onProgressUpdate(null, (int) (Math.min(done, total) >> 10), (int) (total >> 10));
            }
        }
    }

    private static final class CountingInputStream extends FilterInputStream {
        long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long s = super.skip(n);
            count += s;
            return s;
        }
    }
}
