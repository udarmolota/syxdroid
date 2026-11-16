package com.udarmolota.syxdroid.game;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.udarmolota.syxdroid.AppStorage;
import com.udarmolota.syxdroid.FileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installs Songs of Syx mods from a ZIP into an instance's mods folder. The ZIP may hold one mod
 * (its _Info.txt and V folders at the top, or inside one folder) or several mod folders side by
 * side. A mod is any folder with an _Info.txt or a V&lt;number&gt; folder (see {@link SyxMods}).
 * A mod already there with the same folder name is replaced.
 */
public final class SyxModImporter {
    private SyxModImporter() {}

    /** The names of the mods installed. */
    public static final class Result {
        public final List<String> installed = new ArrayList<>();
    }

    @NonNull
    public static Result install(Context context, GameInstance gi, Uri zip, @Nullable String fallbackName) throws IOException {
        File work = new File(AppStorage.requireSingleton().getCachePath(), "mod_import");
        FileUtils.deleteDirectory(work);
        if (!work.mkdirs()) throw new IOException("Failed to create " + work);
        try {
            try (InputStream in = context.getContentResolver().openInputStream(zip)) {
                if (in == null) throw new IOException("Cannot open " + zip);
                unzip(in, work);
            }
            Result r = new Result();
            File mods = SyxMods.modsDir(gi);
            if (!mods.isDirectory() && !mods.mkdirs()) throw new IOException("Failed to create " + mods);
            if (isMod(work)) {
                // The mod's files at the top of the ZIP: the folder takes the ZIP's (or the mod's) name.
                SyxMods.Mod m = SyxMods.read(work);
                String name = fallbackName != null && !fallbackName.isEmpty() ? fallbackName
                        : (m != null ? m.name : "mod");
                r.installed.add(moveIn(work, mods, name));
            } else {
                for (File dir : modDirs(work)) r.installed.add(moveIn(dir, mods, dir.getName()));
            }
            if (r.installed.isEmpty()) throw new IOException("no Songs of Syx mod in this ZIP (no _Info.txt or V folder)");
            return r;
        } finally {
            FileUtils.deleteDirectory(work);
        }
    }

    /** Moves a downloaded mod folder (the Workshop download) into the mods folder under this name. */
    public static String installFolder(File modDir, GameInstance gi, String name) throws IOException {
        File mods = SyxMods.modsDir(gi);
        if (!mods.isDirectory() && !mods.mkdirs()) throw new IOException("Failed to create " + mods);
        File root = isMod(modDir) ? modDir : null;
        if (root == null) {
            List<File> found = modDirs(modDir);
            if (found.size() == 1) root = found.get(0);
        }
        if (root == null) throw new IOException("no Songs of Syx mod in " + modDir.getName());
        return moveIn(root, mods, name);
    }

    static boolean isMod(File dir) {
        if (new File(dir, SyxMods.INFO_FILE).isFile()) return true;
        File[] kids = dir.listFiles(File::isDirectory);
        if (kids != null) for (File k : kids) if (k.getName().matches("V\\d+")) return true;
        return false;
    }

    /** The mod folders under dir, one or two levels down. */
    private static List<File> modDirs(File dir) {
        List<File> out = new ArrayList<>();
        File[] kids = dir.listFiles(File::isDirectory);
        if (kids == null) return out;
        for (File k : kids) {
            if (isMod(k)) {
                out.add(k);
            } else {
                File[] grand = k.listFiles(File::isDirectory);
                if (grand != null) for (File g : grand) if (isMod(g)) out.add(g);
            }
        }
        return out;
    }

    /** Moves src into mods/name, replacing a mod of the same folder name. Returns the folder name. */
    private static String moveIn(File src, File mods, String name) throws IOException {
        String folder = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (folder.isEmpty() || folder.equals(".") || folder.equals("..")) folder = "mod";
        File dest = new File(mods, folder);
        if (dest.exists() && !FileUtils.deleteDirectory(dest)) throw new IOException("Cannot replace " + dest);
        if (!src.renameTo(dest)) {
            copyTree(src, dest);
            FileUtils.deleteDirectory(src);
        }
        return folder;
    }

    private static void copyTree(File src, File dest) throws IOException {
        if (src.isDirectory()) {
            if (!dest.isDirectory() && !dest.mkdirs()) throw new IOException("Failed to create " + dest);
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) copyTree(k, new File(dest, k.getName()));
        } else {
            java.nio.file.Files.copy(src.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void unzip(InputStream in, File dest) throws IOException {
        String root = dest.getCanonicalPath() + File.separator;
        try (ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry e;
            byte[] buf = new byte[64 * 1024];
            while ((e = zin.getNextEntry()) != null) {
                File out = new File(dest, e.getName());
                // No path may leave the work folder ("../" entries)
                if (!out.getCanonicalPath().startsWith(root)) throw new IOException("Bad entry " + e.getName());
                if (e.isDirectory()) {
                    if (!out.isDirectory() && !out.mkdirs()) throw new IOException("Failed to create " + out);
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Failed to create " + parent);
                try (OutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = zin.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
        }
    }
}
