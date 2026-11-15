# NOTICE

This file records what SyxDroid is, what it does **not** contain, what it changes when it runs the
user's own copy of the game, and every third-party component it redistributes, with its licence.

SyxDroid is a non-commercial project. It is not sold, contains no advertising, and has no paid
features.

---

## 1. Not affiliated with Gamatron AB

**SyxDroid is not developed by, endorsed by, or affiliated with Gamatron AB in any way.**

*Songs of Syx* is the work of Gamatron AB. The name is used here only to describe what this
launcher is compatible with: nominative use, no claim of ownership or association.

## 2. What SyxDroid does not distribute

SyxDroid ships **no part of Songs of Syx**. This repository and the built application contain:

- no game code, compiled or otherwise: no `.class` files and no `SongsOfSyx.jar`;
- no game assets: no textures, sprites, sounds, music, shaders, maps, scripts or translations
  (`base/data.zip`, `base/locale.zip` and the rest stay with the player);
- no game binaries and no Steam files;
- no licence keys, no account credentials, no means of bypassing any purchase.

The user supplies the game themselves, from a copy they legally own (Steam, Windows or Linux
depot). On first start SyxDroid copies two parts of that copy, `SongsOfSyx.jar` and `base/`, from
a folder or zip the user picks into the app's private storage on the same device. It never uploads
or redistributes them. The optional in-app Steam download signs in to Steam with the login the
user types; it goes only to Steam, for that sign-in, and is not stored. The downloaded game is
saved as a zip in the user's own Download/SyxDroid folder. The launcher's icon, logo and banner are original artwork, not the game's.

## 3. What SyxDroid changes when it runs the user's copy

**Nothing is written to the game's files.** The game's classes are adapted in memory, while they
load, by a Java agent (`syxagent`). Each change can be switched off with a JVM argument:

| Class | Change | Why | Switch |
|---|---|---|---|
| `snake2d.Shader` | The GLSL that `getFile` returns has rectangle samplers turned into 2D samplers with normalised coordinates | OpenGL ES has no `sampler2DRect` | `-Dsyx.agent.shaders=false` |
| `snake2d.Texture`, `snake2d.TextureHolder` | `GL_TEXTURE_RECTANGLE` becomes `GL_TEXTURE_2D` | Same reason, to match the shaders | `-Dsyx.agent.rectTextures=false` |
| `snake2d.util.process.Proccesser` | Process-starting methods return `null` | Android has no `java` binary to start the desktop splash screen, error dialog or launcher in a child process | `-Dsyx.agent.noSpawn=false` |
| `snake2d.GraphicsCardGetter` | The constructor records a fixed text instead of opening a hidden probe window | The Android GLFW backend allows one window per process | `-Dsyx.agent.noProbeWindow=false` |
| `org.lwjgl.system.Library` (LWJGL, not the game) | `findResource` finds no native library on the class path | The game jar carries glibc builds of LWJGL's natives, which cannot load on Android; the bionic builds below are used instead | `-Dsyx.agent.noBundledNatives=false` |

Before each start the launcher also writes two keys in the game's own settings file
(`.local/share/songsofsyx/settings/LauncherSettings.txt` in the app's storage): borderless screen
mode, and the game's UI scale set to 0 so that the render resolution chosen in the launcher decides
the UI size. The game itself is started in-process by `syxboot`, which calls the game's own
`init.MainProcess.main`.

## 4. SyxDroid's own licence

SyxDroid is built on the Zomdroid launcher and released under the MIT Licence; see
[LICENSE](LICENSE).

## 5. Third-party components

### GNU licences: source offer

| Component | Version | Licence |
|---|---|---|
| **OpenJDK** (Android build by [PojavLauncherTeam](https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch)), `assets/bundles/jre21.tar.xz` | 21.0.1 | GPL v2 **with the Classpath Exception** |
| **MobileGlues**, `libMobileGluesZomDroid.so` in `assets/bundles/libs.tar.xz`: the "astra" fork by [constantinrarinca3-sketch](https://github.com/constantinrarinca3-sketch/MobileGlues) (Willing-Upstairs-580, aka mepetz) of [MobileGL-Dev/MobileGlues](https://github.com/MobileGL-Dev/MobileGlues) | branch `zomdroid-pz-stable-astra-clean-candidate` @ 414c2c9 | LGPL v2.1 |
| **OpenAL Soft**, `libopenal.so` in `app/src/main/jniLibs` | 1.25.2 | LGPL v2 or later; some parts BSD-3-Clause |

The complete corresponding source for these components is available from their upstream projects
([OpenJDK](https://github.com/openjdk/jdk),
[MobileGlues fork](https://github.com/constantinrarinca3-sketch/MobileGlues/tree/zomdroid-pz-stable-astra-clean-candidate),
[OpenAL Soft](https://github.com/kcat/openal-soft)) and, for the Android OpenJDK build, from the
PojavLauncherTeam repository linked above. OpenAL Soft is the unmodified `1.25.2` tag, built with
NDK r27 for arm64-v8a (Android API 30, OpenSL ES backend, `c++_static`, 16 KB pages) and stripped.
On request we will provide the exact sources corresponding to the binaries shipped in a given
release; open an issue at <https://github.com/udarmolota/syxdroid/issues>.

These components are loaded dynamically and are not linked into SyxDroid's own code.

### Open-source components

| Component | Version / revision | Licence | Source |
|---|---|---|---|
| LWJGL: Java jars (core, GLFW, jemalloc, OpenAL, OpenGL, stb), packed into `runtime.zip` at build time | 3.4.1, from Maven Central | BSD-3-Clause | <https://github.com/LWJGL/lwjgl3> |
| LWJGL: bionic natives `liblwjgl.so`, `liblwjgl_opengl.so`, `liblwjgl_stb.so` in `libs.tar.xz` | 3.4.1, built by `build-lwjgl-3.4.1.sh` in [zomdroid-dependencies](https://github.com/udarmolota/zomdroid-dependencies) | BSD-3-Clause | <https://github.com/LWJGL/lwjgl3> |
| libffi (linked into `liblwjgl.so`) | 3.5.0 | MIT | <https://github.com/libffi/libffi> |
| stb (inside `liblwjgl_stb.so`) | as vendored by LWJGL 3.4.1 | MIT or public domain | <https://github.com/nothings/stb> |
| GLFW, `libglfw.so`: a copy of the [zomdroid-glfw](https://github.com/udarmolota/zomdroid-glfw) fork (Android "zomdroid" platform) in `app/src/main/cpp/glfw`, with SyxDroid's cursor-position fix | zomdroid-glfw @ a0496dd + local change | zlib/libpng | <https://github.com/glfw/glfw> |
| glslang, SPIRV-Cross (built into MobileGlues) | as built | BSD-3-Clause and others / Apache-2.0 | <https://github.com/KhronosGroup/glslang>, <https://github.com/KhronosGroup/SPIRV-Cross> |
| ASM (inside `syxagent.jar`) | 9.8 | BSD-3-Clause | <https://asm.ow2.io/> |
| liblinkernsbypass | as built | BSD-2-Clause, © 2021 Billy Laws | <https://github.com/bylaws/liblinkernsbypass> |
| Gson | 2.10.1 | Apache-2.0 | <https://github.com/google/gson> |
| Apache Commons IO | 2.18.0 | Apache-2.0 | <https://commons.apache.org/proper/commons-io/> |
| Apache Commons Compress | 1.27.1 | Apache-2.0 | <https://commons.apache.org/proper/commons-compress/> |
| XZ for Java | 1.11 | 0BSD | <https://tukaani.org/xz/java.html> |
| JavaSteam and its DepotDownloader (in-app Steam download) | 1.8.0 | MIT | <https://github.com/Longi94/JavaSteam> |
| Bouncy Castle (bcprov-jdk18on) | 1.83 | Bouncy Castle Licence (MIT-style) | <https://www.bouncycastle.org/> |
| Protocol Buffers (Java) | 4.31.1 | BSD-3-Clause | <https://github.com/protocolbuffers/protobuf> |
| zstd-jni (with the zstd library inside) | 1.5.7-6 | BSD-2-Clause (zstd: BSD-3-Clause) | <https://github.com/luben/zstd-jni> |
| Ktor client | 3.3.3 | Apache-2.0 | <https://github.com/ktorio/ktor> |
| OkHttp, Okio | 5.3.2, 3.16.4 | Apache-2.0 | <https://github.com/square/okhttp>, <https://github.com/square/okio> |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization, kotlinx-io, JetBrains annotations | 2.2.21, 1.10.2, 1.9.0, 0.8.0, 23.0.0 | Apache-2.0 | <https://github.com/JetBrains/kotlin>, <https://github.com/Kotlin> |
| Apache Commons Codec, Commons Lang | 1.17.1, 3.20.0 | Apache-2.0 | <https://commons.apache.org/> |
| SLF4J API | 2.0.17 | MIT | <https://www.slf4j.org/> |
| AndroidX, Material Components | see `gradle/libs.versions.toml` | Apache-2.0 | <https://developer.android.com/jetpack/androidx> |
| Android NDK runtime, `libc++_shared.so` | NDK r27 | Apache-2.0 with LLVM Exception | <https://llvm.org/> |

Some components carry an Apache-2.0 `NOTICE` of their own; those notices are preserved inside the
artefacts we redistribute and are reproduced by their upstream projects at the links above.

### Artwork

| Item | Author / licence |
|---|---|
| SyxDroid logo, launcher icon and banner (`sos_logo.jpg`, `sos_backgroud.jpg`, `ic_launcher_syx`, `banner_default`) | udarmolota; original artwork, not taken from the game |
| On-screen control icons `storm_*.png` | udarmolota, CC0 1.0 (see `assets/syx_controls/icons/LICENSE-storm-icons.txt`) |
| On-screen control icons `deephall_*.png` | Cut from Mountaincore's UI atlas, MIT (carried over from the Deephall launcher's controls) |

### Written for SyxDroid

| Component | Notes |
|---|---|
| `syxboot.jar` | The game JVM's main class; starts the user's `init.MainProcess` in-process |
| `syxagent.jar` | The Java agent described in section 3; embeds ASM, credited above |

## 6. Inspiration

SyxDroid's launcher comes from Zomdroid, whose approach was informed by
[Winlator](https://github.com/brunodev85/winlator), [Termux](https://github.com/termux/termux-app)
and [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher). No code was taken from
them.

---

## Corrections

If you hold rights in anything listed here and believe it is credited wrongly, licensed wrongly, or
should not be present at all, please open an issue at
<https://github.com/udarmolota/syxdroid/issues>. Attribution errors will be corrected and removal
requests acted on.
