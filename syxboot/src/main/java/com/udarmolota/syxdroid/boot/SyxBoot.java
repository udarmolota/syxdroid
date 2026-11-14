package com.udarmolota.syxdroid.boot;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * The game JVM's main class on Android.
 *
 * <p>On the desktop, Songs of Syx never runs in the JVM that starts it: {@code init.Main} spawns
 * child {@code java} processes, first {@code launcher.Launcher} (settings and mods), then
 * {@code init.MainProcess} (the game). Android gives us one in-process JVM and no {@code java}
 * binary to spawn, so this class calls {@code init.MainProcess.main} directly. The game's own
 * launcher window is skipped on purpose: the Android settings screen writes the game's settings
 * file before the JVM starts (PLAN.md, decision 4).
 *
 * <p>The player's own SongsOfSyx.jar must be on the class path. The current directory is the
 * imported game folder (the native launcher chdirs there), which is where the game expects
 * {@code base/} to be.
 */
public final class SyxBoot {
    private static final String GAME_MAIN_CLASS = "init.MainProcess";

    private SyxBoot() {}

    public static void main(String[] args) throws Throwable {
        System.out.println("[SyxBoot] java " + System.getProperty("java.version")
                + ", os " + System.getProperty("os.name") + "/" + System.getProperty("os.arch")
                + ", max heap " + (Runtime.getRuntime().maxMemory() >> 20) + " MB");
        System.out.println("[SyxBoot] user.dir " + System.getProperty("user.dir"));
        System.out.println("[SyxBoot] user.home " + System.getProperty("user.home"));

        Class<?> mainProcess;
        try {
            mainProcess = Class.forName(GAME_MAIN_CLASS);
        } catch (ClassNotFoundException e) {
            System.err.println("[SyxBoot] " + GAME_MAIN_CLASS + " not found: is SongsOfSyx.jar on the class path?");
            throw e;
        }

        // init.MainProcess is package-private; both it and this class live in the unnamed module,
        // so setAccessible is enough.
        Method main = mainProcess.getDeclaredMethod("main", String[].class);
        main.setAccessible(true);
        System.out.println("[SyxBoot] starting " + GAME_MAIN_CLASS);
        try {
            main.invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            // Report the game's own exception, not the reflection wrapper.
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            System.err.println("[SyxBoot] the game ended with an exception:");
            cause.printStackTrace();
            throw cause;
        }
        System.out.println("[SyxBoot] " + GAME_MAIN_CLASS + ".main returned, exiting");
        // On the desktop the game runs in a child JVM that ends when main returns. Here the JVM
        // lives inside the app process, and the native launcher does not destroy it, so without
        // this the app would sit on the last frame after Quit. System.exit ends the whole app
        // process, like quitting Project Zomboid or Deephall does.
        System.exit(0);
    }
}
