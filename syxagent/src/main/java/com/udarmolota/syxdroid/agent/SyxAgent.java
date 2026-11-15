package com.udarmolota.syxdroid.agent;

import java.lang.instrument.Instrumentation;

/**
 * SyxDroid's Java agent, started with {@code -javaagent:syxagent.jar} before the game.
 *
 * <p>It adapts the player's own Songs of Syx classes in memory while they load; nothing is written
 * back to the game files. Each change can be switched off with a system property (typed into the
 * launcher's JVM arguments), and each one logs what it did to stdout, which lands in native.log:
 * <ul>
 *   <li>{@code -Dsyx.agent.shaders=false}: leave the GLSL alone. Otherwise every shader the game
 *       loads through {@code snake2d.Shader.getFile} has its rectangle samplers turned into plain
 *       2D samplers ({@link ShaderFix}), because GLES has no {@code sampler2DRect}.</li>
 *   <li>{@code -Dsyx.agent.rectTextures=false}: keep {@code GL_TEXTURE_RECTANGLE}. Otherwise
 *       {@code snake2d.Texture} and {@code snake2d.TextureHolder} bind their textures as
 *       {@code GL_TEXTURE_2D}, matching the rewritten shaders.</li>
 *   <li>{@code -Dsyx.agent.noSpawn=false}: let the game start child processes. Otherwise
 *       {@code snake2d.util.process.Proccesser.exec/executeLwjgl} return null without starting
 *       anything: Android has no {@code java} binary to run, and the game handles a null process
 *       already (splash screen, error dialog, launcher relaunch).</li>
 *   <li>{@code -Dsyx.agent.noBundledNatives=false}: let LWJGL look for natives on the class
 *       path. Otherwise {@code org.lwjgl.system.Library.findResource} finds nothing: the game's
 *       fat jar carries LWJGL natives for linux/arm64, which are glibc builds, and LWJGL 3.4 would
 *       extract and load those before it ever looks at {@code org.lwjgl.librarypath}, where our
 *       bionic builds are.</li>
 *   <li>{@code -Dsyx.agent.noProbeWindow=false}: let the game open its probe window. Otherwise
 *       {@code snake2d.GraphicsCardGetter}'s constructor only records a fixed text: on the
 *       desktop it opens a hidden 300x300 window to read the GPU name for an error message, then
 *       calls glfwTerminate, and our Android GLFW allows exactly one window per process, so the
 *       game's real window would then fail ("window is null").</li>
 *   <li>{@code -Dsyx.agent.sleep=false}: keep the game's own frame wait. Otherwise
 *       {@code snake2d.Sleeper.sync(fps)} waits for the next frame in SyxHooks.sync, which sleeps
 *       the whole wait: the game's version spins in {@code Thread.yield()} for the last part of
 *       every frame, a busy core that only heats the phone.</li>
 *   <li>{@code -Dsyx.agent.dumpShaders=<dir>}: write each shader before and after the rewrite
 *       into that folder, for debugging.</li>
 * </ul>
 *
 * <p>The game is in Early Access and changes often. When a class or method we look for is not
 * there, the agent logs it and leaves the class unchanged instead of failing the start.
 */
public final class SyxAgent {
    static final String TAG = "[SyxAgent] ";

    static boolean shaders;
    static boolean rectTextures;
    static boolean noSpawn;
    static boolean noBundledNatives;
    static boolean noProbeWindow;
    static boolean sleep;
    static String dumpShadersDir;

    private SyxAgent() {}

    public static void premain(String args, Instrumentation inst) {
        shaders = flag("syx.agent.shaders");
        rectTextures = flag("syx.agent.rectTextures");
        noSpawn = flag("syx.agent.noSpawn");
        noBundledNatives = flag("syx.agent.noBundledNatives");
        noProbeWindow = flag("syx.agent.noProbeWindow");
        sleep = flag("syx.agent.sleep");
        dumpShadersDir = System.getProperty("syx.agent.dumpShaders");
        System.out.println(TAG + "loaded: shaders=" + shaders + " rectTextures=" + rectTextures
                + " noSpawn=" + noSpawn + " noBundledNatives=" + noBundledNatives
                + " noProbeWindow=" + noProbeWindow + " sleep=" + sleep + (dumpShadersDir != null ? " dumpShaders=" + dumpShadersDir : ""));
        inst.addTransformer(new SyxTransformer());
    }

    /** On unless the property is set to false. */
    private static boolean flag(String name) {
        return !"false".equalsIgnoreCase(System.getProperty(name, "true"));
    }
}
