package com.udarmolota.syxdroid.game;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Edits Songs of Syx's own settings file before the game starts.
 *
 * <p>The game keeps its launcher settings in
 * {@code <user.home>/.local/share/songsofsyx/settings/LauncherSettings.txt}, one
 * {@code KEY: value,} line per setting (see {@code launcher.LSettings}). Keys it does not find
 * take their defaults, so a file with only our keys is valid too. Only the keys below are
 * touched; every other line, including the ones the player sets in the game's own options, is
 * kept as it is.
 *
 * <p>What is set, and why:
 * <ul>
 *   <li>{@code SCREEN_MODE: 0} (borderless, {@code LSettings.screenModeBorderLess}) on every
 *       start: the only mode that fits the Android surface; windowed and fullscreen are desktop
 *       modes.</li>
 *   <li>{@code WIDOW_SCALE: 0} (sic, the game's key) on every start: the game's own UI scale of
 *       borderless mode ({@code screen / (1 + 0.05 * scale)}) is switched off, because the
 *       launcher's render resolution (RenderResolution) already sets the size the game draws at,
 *       and with it the UI size, as in RimDroid. Both together would only stack.</li>
 * </ul>
 */
public final class SyxGameSettings {
    private static final String TAG = "SyxGameSettings";
    static final String RELATIVE_PATH = ".local/share/songsofsyx/settings/LauncherSettings.txt";

    static final String KEY_SCREEN_MODE = "SCREEN_MODE";
    static final String KEY_SCALE = "WIDOW_SCALE";
    static final int SCREEN_MODE_BORDERLESS = 0;
    /** The game's own UI scale off: the render resolution decides the UI size. */
    static final int GAME_SCALE_OFF = 0;

    private static final Pattern INT_LINE = Pattern.compile("^(\\s*)([A-Z0-9_]+)(\\s*:\\s*)(-?\\d+)(\\s*,?\\s*)$");

    private SyxGameSettings() {}

    /** Applies the settings above to the instance whose home is homePath. Never throws. */
    public static void apply(String homePath) {
        File file = new File(homePath, RELATIVE_PATH);
        try {
            List<String> lines = file.isFile()
                    ? new ArrayList<>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8))
                    : new ArrayList<>();

            Map<String, Integer> found = new LinkedHashMap<>();
            Map<String, Integer> lineOf = new LinkedHashMap<>();
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = INT_LINE.matcher(lines.get(i));
                if (m.matches()) {
                    found.put(m.group(2), Integer.parseInt(m.group(4)));
                    lineOf.put(m.group(2), i);
                }
            }

            boolean changed = false;
            changed |= put(lines, lineOf, KEY_SCREEN_MODE, SCREEN_MODE_BORDERLESS);
            changed |= put(lines, lineOf, KEY_SCALE, GAME_SCALE_OFF);

            if (!changed) return;
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs())
                throw new IOException("Failed to create " + dir);
            Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
            Log.i(TAG, "updated " + file + ": " + KEY_SCREEN_MODE + "=" + SCREEN_MODE_BORDERLESS
                    + ", " + KEY_SCALE + "=" + GAME_SCALE_OFF + " (was " + found.get(KEY_SCALE) + ")");
        } catch (IOException | RuntimeException e) {
            // The game still starts with its own defaults; only the UI is smaller.
            Log.w(TAG, "could not update " + file, e);
        }
    }

    /** Sets key to value, replacing its line or appending one. Returns whether anything changed. */
    private static boolean put(List<String> lines, Map<String, Integer> lineOf, String key, int value) {
        Integer index = lineOf.get(key);
        String line = key + ": " + value + ",";
        if (index == null) {
            lines.add(line);
            lineOf.put(key, lines.size() - 1);
            return true;
        }
        if (lines.get(index).trim().equals(line)) return false;
        lines.set(index, line);
        return true;
    }
}
