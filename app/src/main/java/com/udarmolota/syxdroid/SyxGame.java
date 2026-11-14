package com.udarmolota.syxdroid;

import android.content.Context;

import androidx.annotation.NonNull;

import com.udarmolota.syxdroid.game.GameInstance;
import com.udarmolota.syxdroid.game.GameInstanceManager;
import com.udarmolota.syxdroid.game.InstallationPreset;
import com.udarmolota.syxdroid.game.InstanceSettings;
import com.udarmolota.syxdroid.game.PresetManager;

import java.io.File;
import java.io.IOException;

/**
 * The one Songs of Syx instance SyxDroid plays.
 *
 * <p>Unlike Deephall, whose launcher this code base comes from, the game is never inside the APK:
 * the player imports their own copy (folder or zip) into this instance's game dir. {@link #prepare}
 * creates the instance the first time, checks that the game files are there, and gives it the
 * on-screen controls if it has none. When the files are missing it throws
 * {@link GameNotImportedException}, which the start screen answers by asking for the game.
 *
 * <p>What it never touches: the game's user files under the instance's home
 * ({@code .local/share/songsofsyx}: saves, mods, profile, the game's own settings) and the game
 * dir's controls/ once a layout is there.
 */
public final class SyxGame {
    public static final String INSTANCE_NAME = "SongsOfSyx";
    public static final String PRESET_NAME = "SongsOfSyx";

    private static final String ASSET_CONTROLS_DIR = "syx_controls";
    /** The built-in layout, also what the controls editor's Reset goes back to. */
    public static final String ASSET_CONTROLS_JSON = ASSET_CONTROLS_DIR + "/controls.json";

    /** The game dir has no SongsOfSyx.jar yet: the player has to import the game first. */
    public static final class GameNotImportedException extends IOException {
        GameNotImportedException() {
            super("Songs of Syx is not imported yet");
        }
    }

    private SyxGame() {}

    /** The instance, created and registered on first use. Never null. */
    @NonNull
    static GameInstance getOrCreateInstance() throws IOException {
        GameInstanceManager manager = GameInstanceManager.requireSingleton();
        GameInstance instance = manager.getInstanceByName(INSTANCE_NAME);
        if (instance != null) return instance;
        InstallationPreset preset = PresetManager.findByName(PRESET_NAME);
        if (preset == null) throw new IOException("Installation preset " + PRESET_NAME + " is missing");
        instance = new GameInstance(INSTANCE_NAME, preset);
        manager.registerInstance(instance);
        applyDefaultSettings(instance.settings());
        return instance;
    }

    /** Whether the player's game files are in place. */
    public static boolean isImported() {
        GameInstance instance = GameInstanceManager.requireSingleton().getInstanceByName(INSTANCE_NAME);
        return instance != null && instance.hasGameFiles();
    }

    /**
     * Makes the instance ready to launch. Runs on a worker thread; progress goes to listener.
     *
     * @throws GameNotImportedException when the player has not imported the game yet
     */
    static void prepare(@NonNull Context context, @NonNull TaskProgressListener listener) throws IOException {
        GameInstanceManager manager = GameInstanceManager.requireSingleton();
        GameInstance instance = getOrCreateInstance();

        if (!instance.hasGameFiles()) throw new GameNotImportedException();

        if (!instance.isInstallationFinished()) manager.markInstallationFinished(instance);

        installDefaultControls(context, new File(instance.getGamePath()));
    }

    /**
     * Starting values. Songs of Syx needs geometry shaders and a GL 3.3 core profile, which only
     * MobileGlues provides here (GL4ES has neither; PLAN.md, risks R1/R2).
     */
    private static void applyDefaultSettings(InstanceSettings settings) {
        settings.setRenderer(LauncherPreferences.Renderer.MOBILEGLUES_EXPERIMENTAL);
        // 0 = the lowest offered resolution, the game's 1280x768 minimum: the biggest UI
        // (RenderResolution.effectiveScale)
        settings.setRenderScale(0f);
        settings.setVibrateOnTouch(true);
        settings.setHudMode(InstanceSettings.HUD_OFF);
    }

    /**
     * Copies the built-in layout and its icons into game/controls when the instance has no layout
     * yet. A layout that is already there is the player's, edited or not, and stays.
     */
    private static void installDefaultControls(Context context, File gameDir) throws IOException {
        File controlsDir = new File(gameDir, "controls");
        File controlsJson = new File(controlsDir, "controls.json");
        if (controlsJson.isFile()) return;
        if (!controlsDir.isDirectory() && !controlsDir.mkdirs())
            throw new IOException("Failed to create directory " + controlsDir);
        copyDefaultControlIcons(context, new File(controlsDir, "icons"));
        // The layout goes last: it is what marks the controls as installed.
        FileUtils.copyAssetsToDisk(context, ASSET_CONTROLS_JSON, controlsJson.getPath());
    }

    /**
     * Puts the built-in layout's icons into iconsDir, over any of the same name. The player's own
     * custom icons have generated names and are left alone.
     */
    public static void copyDefaultControlIcons(Context context, File iconsDir) throws IOException {
        FileUtils.copyAssetsToDisk(context, ASSET_CONTROLS_DIR + "/icons", iconsDir.getPath());
    }
}
