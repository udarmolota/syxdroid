package com.udarmolota.syxdroid.game;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.udarmolota.syxdroid.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Songs of Syx's mods, as the game itself reads them (init.paths.PATHS / ModInfo, 0.71):
 *
 * <ul>
 *   <li>a mod is a folder in {@code <home>/.local/share/songsofsyx/mods/}: {@code _Info.txt}
 *       (NAME, DESC, VERSION, AUTHOR, INFO, all optional) and one folder per game version it
 *       supports, {@code V<major>} ({@code V71} for 0.71); without one the game refuses it;</li>
 *   <li>the mods that are on, in load order, are the {@code MODS: [ ... ]} list of the game's
 *       LauncherSettings.txt, by folder name. The desktop game sets it in its own launcher window,
 *       which SyxDroid skips, so this class is the only way to set it on Android;</li>
 *   <li>a mod's code is the .jar files in {@code V<major>/script/}. The desktop launcher puts them
 *       on the game JVM's class path before it starts the game (init.Main →
 *       PATHS.SCRIPT().modClasspaths()); SyxDroid starts the game directly, so
 *       {@link #scriptJars} gives GameInstance the same list.</li>
 * </ul>
 */
public final class SyxMods {
    private static final String TAG = "SyxMods";
    static final String MODS_DIR = ".local/share/songsofsyx/mods";
    static final String INFO_FILE = "_Info.txt";
    /** A file SyxDroid writes into a mod downloaded from the Workshop: its item id. */
    public static final String WORKSHOP_ID_FILE = ".workshop_id";
    private static final String KEY_MODS = "MODS";
    private static final String KEY_VERSION = "VERSION";
    /** What a new instance has before its first start: the game version of the bundled jar is unknown. */
    private static final int UNKNOWN_VERSION = -1;

    private static final Pattern VERSION_FOLDER = Pattern.compile("^V(\\d+)$");
    private static final Pattern INFO_LINE = Pattern.compile("^\\s*([A-Z_]+)\\s*:\\s*\"(.*)\"\\s*,?\\s*$");
    private static final Pattern VERSION_LINE = Pattern.compile("^\\s*VERSION\\s*:\\s*(\\d+)\\s*,?\\s*$");
    private static final Pattern MODS_START = Pattern.compile("^\\s*MODS\\s*:\\s*\\[(.*)$");
    private static final Pattern QUOTED = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    private SyxMods() {}

    /** One installed mod. */
    public static final class Mod {
        /** The folder name: what MODS lists and the game looks up. */
        public final String id;
        public final File folder;
        public final String name, description, version, author;
        /** The game versions it has a folder for (71 for V71), ascending. */
        public final List<Integer> gameVersions;
        public final boolean hasScripts;
        /** The Workshop item it came from, or 0. */
        public final long workshopId;

        Mod(String id, File folder, String name, String description, String version, String author,
            List<Integer> gameVersions, boolean hasScripts, long workshopId) {
            this.id = id;
            this.folder = folder;
            this.name = name;
            this.description = description;
            this.version = version;
            this.author = author;
            this.gameVersions = gameVersions;
            this.hasScripts = hasScripts;
            this.workshopId = workshopId;
        }

        /** Whether it has a folder for this game version (or the version is not known yet). */
        public boolean supports(int gameMajor) {
            return gameMajor < 0 || gameVersions.contains(gameMajor);
        }
    }

    public static File modsDir(GameInstance gi) {
        return new File(gi.getHomePath(), MODS_DIR);
    }

    private static File settingsFile(GameInstance gi) {
        return new File(gi.getHomePath(), SyxGameSettings.RELATIVE_PATH);
    }

    // ---- installed mods ----

    /** Every mod folder of the instance, by name. */
    @NonNull
    public static List<Mod> installed(GameInstance gi) {
        List<Mod> out = new ArrayList<>();
        File[] dirs = modsDir(gi).listFiles(File::isDirectory);
        if (dirs == null) return out;
        for (File d : dirs) {
            Mod m = read(d);
            if (m != null) out.add(m);
        }
        out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    public static Map<String, Mod> byId(List<Mod> mods) {
        Map<String, Mod> m = new LinkedHashMap<>();
        for (Mod mod : mods) m.put(mod.id, mod);
        return m;
    }

    /** The mod in this folder, or null when it has neither an _Info.txt nor a V folder. */
    @Nullable
    public static Mod read(File dir) {
        List<Integer> versions = new ArrayList<>();
        File[] kids = dir.listFiles(File::isDirectory);
        if (kids != null) {
            for (File k : kids) {
                Matcher m = VERSION_FOLDER.matcher(k.getName());
                if (m.matches()) versions.add(Integer.parseInt(m.group(1)));
            }
        }
        Collections.sort(versions);
        File info = new File(dir, INFO_FILE);
        if (!info.isFile() && versions.isEmpty()) return null;

        Map<String, String> fields = new LinkedHashMap<>();
        if (info.isFile()) {
            try {
                for (String line : Files.readAllLines(info.toPath(), StandardCharsets.UTF_8)) {
                    Matcher m = INFO_LINE.matcher(line);
                    if (m.matches()) fields.put(m.group(1), m.group(2).replace("\\n", "\n").replace("\\\"", "\""));
                }
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "could not read " + info, e);
            }
        }
        boolean scripts = false;
        for (int v : versions) {
            File[] jars = new File(dir, "V" + v + "/script").listFiles(f -> f.getName().endsWith(".jar"));
            if (jars != null && jars.length > 0) scripts = true;
        }
        long workshop = 0;
        File ws = new File(dir, WORKSHOP_ID_FILE);
        if (ws.isFile()) {
            try {
                workshop = Long.parseLong(new String(Files.readAllBytes(ws.toPath()), StandardCharsets.UTF_8).trim());
            } catch (IOException | NumberFormatException ignored) {
                // not a Workshop mod after all
            }
        }
        String name = fields.getOrDefault("NAME", "").trim();
        return new Mod(dir.getName(), dir, name.isEmpty() ? dir.getName() : name,
                fields.getOrDefault("DESC", "").trim(), fields.getOrDefault("VERSION", "").trim(),
                fields.getOrDefault("AUTHOR", "").trim(), versions, scripts, workshop);
    }

    /** Deletes the mod's folder; it also leaves the MODS list. */
    public static boolean delete(GameInstance gi, Mod m) {
        List<String> order = activeOrder(gi);
        if (order.remove(m.id)) writeOrder(gi, order);
        boolean ok = FileUtils.deleteDirectory(m.folder);
        if (!ok) Log.w(TAG, "could not delete " + m.folder);
        return ok;
    }

    // ---- the game version ----

    /**
     * The game's major version (71 for 0.71): from its jar (SyxGameVersion), which is there right
     * after the import; else as the game stored it in its settings (VERSION: major << 16 | minor),
     * which it writes only on a clean exit; else -1.
     */
    public static int gameMajor(GameInstance gi) {
        SyxGameVersion v = SyxGameVersion.of(gi);
        if (v != null) return v.major;
        File f = settingsFile(gi);
        if (!f.isFile()) return UNKNOWN_VERSION;
        try {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                Matcher m = VERSION_LINE.matcher(line);
                if (m.matches()) return (int) (Long.parseLong(m.group(1)) >> 16);
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "could not read the game version", e);
        }
        return UNKNOWN_VERSION;
    }

    /**
     * The mods that are on but have no folder for this game version (empty when the version is not
     * known yet, before the instance's first start).
     */
    @NonNull
    public static List<Mod> outdatedActive(GameInstance gi, int gameMajor) {
        List<Mod> out = new ArrayList<>();
        if (gameMajor < 0) return out;
        File mods = modsDir(gi);
        for (String id : activeOrder(gi)) {
            Mod m = read(new File(mods, id));
            if (m != null && !m.supports(gameMajor)) out.add(m);
        }
        return out;
    }

    /** "V65–V70", "V66" or "" : the game versions a mod has folders for. */
    public static String versionsText(Mod m) {
        if (m.gameVersions.isEmpty()) return "";
        int lo = m.gameVersions.get(0), hi = m.gameVersions.get(m.gameVersions.size() - 1);
        return lo == hi ? "V" + lo : "V" + lo + "–V" + hi;
    }

    // ---- MODS, the load order ----

    /** The mods that are on, in load order (folder names); empty when there is no list yet. */
    @NonNull
    public static List<String> activeOrder(GameInstance gi) {
        List<String> out = new ArrayList<>();
        File f = settingsFile(gi);
        if (!f.isFile()) return out;
        try {
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            int[] span = modsSpan(lines);
            if (span == null) return out;
            StringBuilder text = new StringBuilder();
            for (int i = span[0]; i <= span[1]; i++) text.append(lines.get(i)).append('\n');
            String body = text.substring(text.indexOf("[") + 1);
            int close = body.lastIndexOf(']');
            if (close >= 0) body = body.substring(0, close);
            Matcher m = QUOTED.matcher(body);
            while (m.find()) {
                String id = m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
                if (!id.isEmpty() && !out.contains(id)) out.add(id);
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "could not read MODS", e);
        }
        return out;
    }

    /** Writes the MODS list in the game's own layout; every other line is kept as it is. */
    public static boolean writeOrder(GameInstance gi, List<String> order) {
        File f = settingsFile(gi);
        try {
            List<String> lines = f.isFile()
                    ? new ArrayList<>(Files.readAllLines(f.toPath(), StandardCharsets.UTF_8))
                    : new ArrayList<>();
            List<String> block = new ArrayList<>();
            block.add(KEY_MODS + ": [");
            for (String id : order) block.add("\t\"" + id.replace("\\", "\\\\").replace("\"", "\\\"") + "\",");
            block.add("],");
            int[] span = modsSpan(lines);
            if (span == null) {
                lines.addAll(block);
            } else {
                for (int i = span[1]; i >= span[0]; i--) lines.remove(i);
                lines.addAll(span[0], block);
            }
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Failed to create " + dir);
            Files.write(f.toPath(), lines, StandardCharsets.UTF_8);
            return true;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "could not write MODS", e);
            return false;
        }
    }

    public static boolean setActive(GameInstance gi, String id, boolean on) {
        List<String> order = activeOrder(gi);
        boolean changed = on ? (!order.contains(id) && order.add(id)) : order.remove(id);
        return !changed || writeOrder(gi, order);
    }

    /** First and last line of the MODS block, or null when the file has none. */
    @Nullable
    private static int[] modsSpan(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = MODS_START.matcher(lines.get(i));
            if (!m.matches()) continue;
            if (m.group(1).contains("]")) return new int[]{i, i};
            for (int j = i + 1; j < lines.size(); j++) {
                if (lines.get(j).contains("]")) return new int[]{i, j};
            }
            return new int[]{i, lines.size() - 1};
        }
        return null;
    }

    // ---- the class path ----

    /**
     * The script jars of the mods that are on, in load order: what the game's own launcher adds to
     * the class path. Each mod uses its folder for this game version, or, before the first start
     * when the version is not known yet, its newest one.
     */
    @NonNull
    public static List<String> scriptJars(GameInstance gi) {
        List<String> out = new ArrayList<>();
        int major = gameMajor(gi);
        File mods = modsDir(gi);
        for (String id : activeOrder(gi)) {
            Mod m = read(new File(mods, id));
            if (m == null || m.gameVersions.isEmpty()) continue;
            int v = m.gameVersions.contains(major) ? major : m.gameVersions.get(m.gameVersions.size() - 1);
            File[] jars = new File(m.folder, "V" + v + "/script").listFiles(f -> f.getName().endsWith(".jar"));
            if (jars == null) continue;
            java.util.Arrays.sort(jars, (a, b) -> a.getName().compareTo(b.getName()));
            for (File j : jars) out.add(j.getAbsolutePath());
        }
        return out;
    }

    /** A folder name for a new mod that does not clash with one already there. */
    public static String freeFolderName(File modsDir, String wanted) {
        String base = wanted.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (base.isEmpty() || base.equals(".") || base.equals("..")) base = "mod";
        String name = base;
        for (int i = 2; new File(modsDir, name).exists(); i++) name = base + " (" + i + ")";
        return name;
    }

    /** Lower-case text for the search field. */
    public static String searchText(Mod m) {
        return (m.name + " " + m.author + " " + m.id).toLowerCase(Locale.ROOT);
    }
}
