package com.udarmolota.syxdroid.agent;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Static methods the transformed game classes call. Public, since game classes in other packages
 * call them; the agent jar is on the system class path, so the game's class loader sees them.
 */
public final class SyxHooks {
    private SyxHooks() {}

    /** Called by snake2d.Shader.getFile with the text it is about to return. */
    public static String shader(String source, String fileName, String append) {
        String fixed;
        try {
            fixed = ShaderFix.fix(source);
        } catch (RuntimeException e) {
            System.out.println(SyxAgent.TAG + "shader rewrite failed for " + fileName + ": " + e);
            return source;
        }
        if (fixed != source) System.out.println(SyxAgent.TAG + "shader " + fileName + ": sampler2DRect -> sampler2D");
        dump(fileName, source, fixed);
        return fixed;
    }

    /** Called instead of starting a child JVM (splash screen, error dialog, launcher relaunch). */
    public static void blockedProcess(Class<?> mainClass, String method) {
        System.out.println(SyxAgent.TAG + "not starting a process for "
                + (mainClass != null ? mainClass.getName() : "?") + " (Proccesser." + method + ")");
    }

    /** Deadline of the next frame for {@link #sync}, in System.nanoTime() units; 0 = none yet. */
    private static long nextFrame;

    /**
     * Called by snake2d.Sleeper.sync(fps) once per frame: waits until the next frame is due,
     * asleep the whole time. A frame that ran late is not waited for, and the schedule restarts
     * from it, so a slow stretch does not end in a burst of catch-up frames.
     */
    public static void sync(int fps) {
        if (fps <= 0) return;
        long period = 1_000_000_000L / fps;
        long now = System.nanoTime();
        if (nextFrame == 0 || now - nextFrame > period) {
            nextFrame = now + period;
            return;
        }
        long left;
        while ((left = nextFrame - System.nanoTime()) > 0) {
            java.util.concurrent.locks.LockSupport.parkNanos(left);
        }
        nextFrame += period;
    }

    private static void dump(String fileName, String before, String after) {
        String dir = SyxAgent.dumpShadersDir;
        if (dir == null || fileName == null) return;
        try {
            File d = new File(dir);
            if (!d.isDirectory() && !d.mkdirs()) return;
            String base = new File(fileName).getName();
            Files.write(new File(d, base + ".orig").toPath(), before.getBytes(StandardCharsets.UTF_8));
            if (after != before) Files.write(new File(d, base).toPath(), after.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // Debug aid only.
        }
    }
}
