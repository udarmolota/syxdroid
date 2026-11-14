# SyxDroid

**SyxDroid** is a launcher that runs [Songs of Syx](https://store.steampowered.com/app/1162750/) on
Android, using the player's own copy of the game.

> [!NOTE]
> Early development. Nothing runs yet: see [PLAN.md](PLAN.md) for the road to the first launch.

> [!NOTE]
> This application is **not developed by Gamatron AB** and is **not affiliated with them** in any way.
> SyxDroid ships **no part of the game**: no code, no assets, no shaders. You import your own legally
> owned copy (Steam, Windows or Linux depot) on the device. See [NOTICE](NOTICE.md) for every
> third-party component and its licence.

## How it works

Songs of Syx is Java bytecode, so it runs unchanged on an ARM64 JRE 21. SyxDroid supplies the
Android pieces around it:

- an in-process JVM (no `java` child processes; `SyxBoot` starts `init.MainProcess` directly);
- LWJGL 3.4.1 jars plus bionic natives (`liblwjgl`, `liblwjgl_opengl`, `liblwjgl_stb`);
- GLFW with an Android platform backend (touch, keyboard and gamepad input);
- MobileGlues for OpenGL 3.3 core on GLES 3.2 (the game needs geometry shaders);
- OpenAL Soft for sound.

## Layout

- `app/`: the Android launcher (a trimmed fork of Zomdroid/DeepDroid).
- `syxboot/`: the game JVM's main class. Plain Java, packed into `assets/bundles/runtime.zip` at
  build time together with the LWJGL jars from Maven Central.
- `app/src/main/assets/bundles/`: `jre21.tar.xz` (PojavLauncherTeam OpenJDK 21) and
  `libs.tar.xz` (LWJGL 3.4.1 natives and MobileGlues, prebuilt; see NOTICE).

## System requirements (target)

- Android 11+, arm64, GLES 3.2 (geometry shaders). First test device: Adreno 830.
- 8 GB RAM or more recommended; large cities need a large Java heap.

## Credits

Built on [Zomdroid](https://github.com/udarmolota/zomdroid) (MIT). Versions and licences of every
component are listed in [NOTICE](NOTICE.md).

- [OpenJDK](https://github.com/openjdk/jdk) (Android port by [PojavLauncherTeam](https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch))
- [LWJGL](https://github.com/LWJGL/lwjgl3)
- [GLFW](https://github.com/glfw/glfw)
- [MobileGlues](https://github.com/MobileGL-Dev/MobileGlues)
- [OpenAL Soft](https://github.com/kcat/openal-soft)
- [liblinkernsbypass](https://github.com/bylaws/liblinkernsbypass)
