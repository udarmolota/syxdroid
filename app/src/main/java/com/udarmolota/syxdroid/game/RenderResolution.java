package com.udarmolota.syxdroid.game;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * The render resolutions offered for Songs of Syx, worked out per screen (as in RimDroid).
 *
 * <p>The game surface is drawn at a fraction of the screen (the render scale) and stretched to
 * fill it. Lower means more FPS and a bigger UI, since the game lays its UI out in surface pixels;
 * native is sharpest. The floor keeps the surface at or above the game's own minimum of 1280x768
 * ({@code init.constant.C.MIN_WIDTH/MIN_HEIGHT}): below it the game's UI is not designed to fit.
 *
 * <p>Offered: native (100%), the floor, and one step half-way between them.
 */
public final class RenderResolution {
    public static final int GAME_MIN_WIDTH = 1280;
    public static final int GAME_MIN_HEIGHT = 768;
    /** Used when the screen size is not known yet: the floor of a 1080-line screen. */
    private static final int FALLBACK_FLOOR_PERCENT = 72;

    private RenderResolution() {}

    /** The smallest render scale, in percent, that keeps a w x h screen at or above 1280x768. */
    public static int floorPercent(int w, int h) {
        int longSide = Math.max(w, h), shortSide = Math.min(w, h);
        if (longSide <= 0 || shortSide <= 0) return FALLBACK_FLOOR_PERCENT;
        double need = Math.max((double) GAME_MIN_WIDTH / longSide, (double) GAME_MIN_HEIGHT / shortSide);
        return Math.max(1, Math.min(100, (int) Math.ceil(need * 100.0)));
    }

    /** The offered scales in percent, highest (native) first. */
    public static List<Integer> presetPercents(int w, int h) {
        int min = floorPercent(w, h);
        int mid = Math.max(min + 1, Math.min(99, (min + 100) / 2));
        TreeSet<Integer> set = new TreeSet<>();
        set.add(100);
        set.add(min);
        if (mid < 100) set.add(mid);
        return new ArrayList<>(set.descendingSet());
    }

    /**
     * The scale actually used: the stored one, raised to the floor and capped at native. A stored
     * value of 0 (the default of a new instance) therefore means "the floor": the biggest UI.
     */
    public static float effectiveScale(float stored, int w, int h) {
        float floor = floorPercent(w, h) / 100f;
        return Math.max(floor, Math.min(1f, stored));
    }

    /** The preset closest to the stored scale, for showing the current choice. */
    public static int nearestPreset(float stored, int w, int h) {
        int pct = Math.round(effectiveScale(stored, w, h) * 100f);
        int best = 100, bestD = Integer.MAX_VALUE;
        for (int p : presetPercents(w, h)) {
            int d = Math.abs(p - pct);
            if (d < bestD) { bestD = d; best = p; }
        }
        return best;
    }

    /** "2340×1080" for a scale in percent on a w x h screen (landscape). */
    public static String label(int percent, int w, int h) {
        int longSide = Math.max(w, h), shortSide = Math.min(w, h);
        return Math.round(longSide * percent / 100f) + "×" + Math.round(shortSide * percent / 100f);
    }
}
