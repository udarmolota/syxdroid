package com.udarmolota.syxdroid;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Backup / restore of Songs of Syx's user data: the saves ({@code saves/}: saves, campaigns,
 * profile) and the game's settings ({@code settings/}: LauncherSettings.txt with options, language
 * and the mod list). Both live in the game's user folder,
 * {@code <instance home>/.local/share/songsofsyx/} ({@link #userDir}).
 *
 * <p>Export zips the requested folders; import extracts them back (restricted to the allowed
 * top-level folders, zip-slip guarded). Saves and settings are separable, so moving saves to
 * another phone does not carry this phone's settings. Taken from RimDroid.
 */
public final class GameDataTransfer {

    /** Top-level user-data folders. Kept separate so saves and settings can be moved independently. */
    public static final String SAVES  = "saves";     // saves, campaigns, profile
    public static final String CONFIG = "settings";  // LauncherSettings.txt: options, language, mod list

    /** The game's user folder of an instance: user.home is the instance home, the game adds the rest. */
    public static java.io.File userDir(com.udarmolota.syxdroid.game.GameInstance instance) {
        return new java.io.File(instance.getHomePath(), ".local/share/songsofsyx");
    }
    private static final String[] ALL_PARTS = { SAVES, CONFIG };

    public static final class Result {
        public final List<String> items = new ArrayList<>(); // which parts were handled
        public String error;
        public long bytes;
        public boolean ok() { return error == null && !items.isEmpty(); }
    }

    private GameDataTransfer() {}

    /** Zip the requested top-level folders of {@code userDir} into {@code rawOut}.
     *  @param parts which folders to include ({@link #SAVES} / {@link #CONFIG}); empty = both. */
    public static Result export(File userDir, OutputStream rawOut, String... parts) {
        String[] use = (parts == null || parts.length == 0) ? ALL_PARTS : parts;
        Result r = new Result();
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(rawOut))) {
            byte[] buf = new byte[1 << 16];
            for (String part : use) {
                File dir = new File(userDir, part);
                if (!dir.isDirectory()) continue;
                zipDir(dir, part, zos, buf, r);
                r.items.add(part);
            }
            if (r.items.isEmpty()) r.error = "Nothing to export (no " + String.join("/", use) + " yet).";
        } catch (IOException e) {
            r.error = msg(e);
        }
        return r;
    }

    private static void zipDir(File dir, String prefix, ZipOutputStream zos, byte[] buf, Result r)
            throws IOException {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        if (kids.length == 0) {                       // preserve empty dirs
            zos.putNextEntry(new ZipEntry(prefix + "/"));
            zos.closeEntry();
            return;
        }
        for (File f : kids) {
            String name = prefix + "/" + f.getName();
            if (f.isDirectory()) {
                zipDir(f, name, zos, buf, r);
            } else {
                zos.putNextEntry(new ZipEntry(name));
                try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
                    int n;
                    while ((n = in.read(buf)) > 0) { zos.write(buf, 0, n); r.bytes += n; }
                }
                zos.closeEntry();
            }
        }
    }

    /** Restore a backup zip into {@code userDir}.
     *  @param allowedParts which top-level folders to extract ({@link #SAVES} / {@link #CONFIG});
     *                      empty = both. Entries outside these folders are ignored. */
    public static Result importZip(File zipFile, File userDir, String... allowedParts) {
        Set<String> allow = new LinkedHashSet<>(java.util.Arrays.asList(
                (allowedParts == null || allowedParts.length == 0) ? ALL_PARTS : allowedParts));
        Result r = new Result();
        if (!userDir.exists() && !userDir.mkdirs()) { r.error = "Cannot create user data dir"; return r; }
        final String destCanon;
        try { destCanon = userDir.getCanonicalPath(); } catch (IOException e) { r.error = msg(e); return r; }

        Set<String> parts = new LinkedHashSet<>();
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            byte[] buf = new byte[1 << 16];
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                String top = name.contains("/") ? name.substring(0, name.indexOf('/')) : name;
                if (!allow.contains(top)) { zis.closeEntry(); continue; }   // ignore anything outside the allowed parts

                File out = new File(userDir, name);
                String oc = out.getCanonicalPath();
                if (!oc.equals(destCanon) && !oc.startsWith(destCanon + File.separator)) { // zip-slip guard
                    zis.closeEntry();
                    continue;
                }
                if (e.isDirectory()) {
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                        int n;
                        while ((n = zis.read(buf)) > 0) { os.write(buf, 0, n); r.bytes += n; }
                    }
                }
                parts.add(top);
                zis.closeEntry();
            }
            r.items.addAll(parts);
            if (parts.isEmpty()) r.error = "No saves/settings found in this zip.";
        } catch (IOException ex) {
            r.error = msg(ex);
        }
        return r;
    }

    private static String msg(Exception e) { return e.getMessage() != null ? e.getMessage() : "I/O error"; }
}
