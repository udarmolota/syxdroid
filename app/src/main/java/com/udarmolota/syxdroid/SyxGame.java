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
 * Songs of Syx instances: their names, how a new one is made from a zip of the player's own copy
 * ({@link #createInstanceFromZip}) and what it starts with ({@link #setUpNewInstance}).
 *
 * <p>The game is never inside the APK: each instance gets the player's own copy imported into its
 * game dir. ({@link #prepare} and {@link #INSTANCE_NAME} are the first, single-instance version,
 * still used by the old start screen.)
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

    /** A new instance's name: "SongsOfSyx", then "SongsOfSyx2", "SongsOfSyx3", ... (as in RimDroid). */
    public static String suggestInstanceName() {
        String base = INSTANCE_NAME;
        if (GameInstance.isUniqueName(base)) return base;
        for (int i = 2; i < 1000; i++) {
            String name = base + i;
            if (GameInstance.isUniqueName(name)) return name;
        }
        return base + System.currentTimeMillis();
    }

    /**
     * Creates a new instance from a zip of the game (picked by the player, or the Steam download in
     * Download/SyxDroid): registers it under the next free name and hands the import to
     * InstallerService (CREATE_GAME_INSTANCE), whose progress the launcher shows. Main thread.
     *
     * @return the new instance's name, or null if it could not be made
     */
    public static String createInstanceFromZip(@NonNull Context context, @NonNull android.net.Uri zip) {
        InstallationPreset preset = PresetManager.findByName(PRESET_NAME);
        GameInstance instance;
        try {
            instance = new GameInstance(suggestInstanceName(), preset);
        } catch (IOException e) {
            android.widget.Toast.makeText(context, String.valueOf(e.getMessage()), android.widget.Toast.LENGTH_LONG).show();
            return null;
        }
        GameInstanceManager.requireSingleton().registerInstance(instance);
        android.content.Intent intent = new android.content.Intent(context, InstallerService.class);
        intent.putExtra(InstallerService.EXTRA_COMMAND, InstallerService.Task.CREATE_GAME_INSTANCE.ordinal());
        intent.putExtra(InstallerService.EXTRA_GAME_INSTANCE_NAME, instance.getName());
        intent.putExtra(InstallerService.EXTRA_INSTALL_PRESET_NAME, preset.name);
        intent.putExtra(InstallerService.EXTRA_IMPORT_KIND, SyxImporter.KIND_ZIP);
        intent.putExtra(InstallerService.EXTRA_ARCHIVE_URI, zip);
        // The picker's read grant belongs to the activity; hand it to the service.
        intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startForegroundService(intent);
        return instance.getName();
    }

    /**
     * A new instance's starting point, once its game files are in: SyxDroid's settings (MobileGlues,
     * the lowest render resolution), the built-in on-screen controls, and "installed".
     */
    static void setUpNewInstance(@NonNull Context context, @NonNull GameInstance instance) throws IOException {
        applyDefaultSettings(instance.settings());
        installDefaultControls(context, new File(instance.getGamePath()));
        GameInstanceManager manager = GameInstanceManager.requireSingleton();
        if (!instance.isInstallationFinished()) manager.markInstallationFinished(instance);
    }

    /** Whether any instance plays this preset with its game files in place. */
    public static boolean anyInstanceReady() {
        for (GameInstance gi : GameInstanceManager.requireSingleton().getInstances()) {
            if (gi.hasGameFiles()) return true;
        }
        return false;
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
