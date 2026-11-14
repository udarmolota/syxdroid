package com.udarmolota.syxdroid.game;

import com.udarmolota.syxdroid.C;

import java.util.ArrayList;

public class PresetManager {
    private static final ArrayList<InstallationPreset> presets = new ArrayList<>();

    /** The player's own game jar; its presence in the game dir means the game is imported. */
    public static final String SYX_GAME_JAR = "SongsOfSyx.jar";

    static {
        // Songs of Syx: one fat jar (game, snake2d engine, LWJGL 3.4.1, steamworks4j, JNA) plus
        // base/ next to it. SyxBoot calls init.MainProcess.main in this JVM, skipping init.Main,
        // which would spawn child java processes (PLAN.md).
        //
        // Class path order matters: our LWJGL 3.4.1 jars come first (boot class path) so their
        // classes win over the copies inside SongsOfSyx.jar and match our bionic natives exactly.
        // The tutorial script jars in base/script are added by the game itself
        // (PATHS.SCRIPT().modClasspaths()) only for its child process, so they are listed here.
        presets.add(new InstallationPreset.Builder()
                .setName("SongsOfSyx")
                .setBuildVersion("syx")
                .setGameFileMarker(SYX_GAME_JAR)
                .setBootClassPathArray(new String[]{
                        C.deps.RUNTIME + "/syxboot.jar",
                        C.deps.RUNTIME + "/lwjgl-3.4.1.jar",
                        C.deps.RUNTIME + "/lwjgl-glfw-3.4.1.jar",
                        C.deps.RUNTIME + "/lwjgl-jemalloc-3.4.1.jar",
                        C.deps.RUNTIME + "/lwjgl-openal-3.4.1.jar",
                        C.deps.RUNTIME + "/lwjgl-opengl-3.4.1.jar",
                        C.deps.RUNTIME + "/lwjgl-stb-3.4.1.jar"
                })
                .setClassPathArray(new String[]{
                        SYX_GAME_JAR,
                        "base/script/000_Tutorial.jar",
                        "base/script/001_Tutorial.jar",
                        "."
                })
                .setLibraryPathArray(new String[]{
                        C.deps.LIBS_LWJGL_341,
                        C.deps.LIBS_ANDROID_ARM64_v8a
                })
                .setExtraJvmArgs(new String[]{
                        // The game's own defaults (launcher.LSettings JVM_ARGS2), minus -Xmx/-Xms,
                        // which come from the heap preset in the launcher settings.
                        "-Dfile.encoding=UTF-8",
                        "-XX:+UseCompressedOops",
                        // No AWT on Android: the game only uses Swing in child processes we never start.
                        "-Djava.awt.headless=true"
                })
                .setMainClassName("com/udarmolota/syxdroid/boot/SyxBoot")
                .build()
        );
    }

    public static ArrayList<InstallationPreset> getPresets() {
        return presets;
    }

    /** The preset registered under that name, or null. */
    public static InstallationPreset findByName(String name) {
        for (InstallationPreset preset : presets) {
            if (preset.name.equals(name)) return preset;
        }
        return null;
    }

    /** Indices into {@link #getPresets()}: the order the presets are registered in above. */
    public static final int PRESET_SYX = 0;

    /**
     * Which preset a set of game files calls for, from their paths: ZIP entry names, or paths
     * relative to a game folder. Tolerant of wrapper folders. -1 when nothing in them belongs to a
     * game the launcher knows.
     * <pre>
     *   SongsOfSyx.jar         -> Songs of Syx
     * </pre>
     */
    public static int detectPresetIndex(Iterable<String> paths) {
        for (String path : paths) {
            if (isSyxJar(path)) return PRESET_SYX;
        }
        return -1;
    }

    private static boolean isSyxJar(String name) {
        String n = name.replace('\\', '/');
        return n.equals(SYX_GAME_JAR) || n.endsWith("/" + SYX_GAME_JAR);
    }
}
