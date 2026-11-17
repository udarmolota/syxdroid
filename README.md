# SyxDroid

**SyxDroid** is an unofficial, community-developed launcher that runs
[Songs of Syx](https://store.steampowered.com/app/1162750/) on ARM64 Android devices, with GPU
rendering, editable touch controls and sound.

> [!NOTE]
> SyxDroid needs your own copy of Songs of Syx. Steam owners can download it right in the app; you
> can also import the game files as a ZIP.

> [!WARNING]
> **The app is an alpha.** The game runs from the main menu to a full city, with sound, saving and
> loading, but so far it has been tested on one phone. Back up saves you care about.

> [!NOTE]
> SyxDroid is **not developed by Gamatron AB** and is **not affiliated with them** in any way. It ships
> **no part of the game**: no code, no graphics, no shaders. See [NOTICE](NOTICE.md) for every
> third-party component and its licence.

## Features

- ✔️ Runs **Songs of Syx 0.71** on ARM64 phones, the game's own Java code unchanged — nothing is
  emulated or recompiled
- ✔️ **In-app download** — get the game from Steam (Steam Guard supported); the download is saved
  as a ZIP in `Download/SyxDroid`
- ✔️ **Mods from mod.io in the app** — browse [mod.io](https://mod.io/g/songsofsyx), open a mod
  and tap Download; they download in the background into `Download/SyxDroid`, no
  account needed, and you install them in Mods. Steam Workshop downloads come later
- ✔️ **Mods screen** (side menu) — switch mods on and off, drag them into load order, delete
  them, copy them to another instance, install a mod from a ZIP; mods with scripts are loaded too
- ✔️ **Editable on-screen touch controls** — move, resize, add buttons bound to any key or mouse
  action, pick the label colour, use your own icons; open the editor with **Controls** in the
  side menu
- ✔️ **Multiple instances** — each install is a card with its own settings and controls
- ✔️ **Game language** — any language your copy has, or the phone's language
- ✔️ **Render resolution** — lower it for more FPS and a larger interface
- ✔️ **Frame rate limit** — 30 / 60 / 90 / 120, 60 by default
- ✔️ **In-game display** — an FPS counter, or a bar with load, memory and temperature
- ✔️ **Saves / game settings / controls import & export** as ZIP
- ✔️ **Report a bug** — one tap collects everything we need into a ZIP
- ✔️ English and Russian

**Not tested yet:** physical keyboard and mouse, Mali and Xclipse GPUs, phones with less than
12 GB of RAM. Songs of Syx has no gamepad support of its own, so neither does SyxDroid.

## Project status & what to expect

SyxDroid is very young — started in **November 2026**, built by **one person**, and in active
development. A few honest notes:

- **Songs of Syx is a heavy simulation.** A fresh settlement is light; a city of thousands with
  wars and trade asks a lot more of the CPU. Launching on a phone is not the same as playing a
  late-game city comfortably on it.
- **So far it has been played on a Galaxy S25 Ultra** (Snapdragon, Adreno 830). Other phones should
  work if they meet the requirements below, but nobody has checked yet.
- **120 FPS heats the phone fast.** On a flagship, ten minutes at 120 FPS made it noticeably hot.
  60 is the default for a reason; 30 is fine for long sessions.

We'd rather set expectations honestly than overpromise.

## System requirements

- Android 11+, ARM64.
- A GPU with **OpenGL ES 3.2**: the game draws every sprite with geometry shaders. Adreno is tested;
  Mali G-series and Xclipse should be able to run it but are untested.
- RAM: the game itself uses about 1 GB. Tested on 12 GB; phones with 8 GB will probably be fine,
  less is unknown.
- About 1 GB of free storage for the game and the app.
- A copy of Songs of Syx you own (Steam, Windows or Linux).

## Installation

1. Download the APK from [Releases](https://github.com/udarmolota/syxdroid/releases) and install it.
2. Give SyxDroid the game:
   - **Steam:** open the **Game** page, sign in and download. Your login goes only to Steam and is
     not saved. When it's done, tap **Create new instance**.
   - **ZIP:** pack your game folder into a ZIP (on Steam: *Manage → Browse local files*), copy it to
     the phone and tap **+** on the home screen. It needs `SongsOfSyx.jar` and the `base` folder.
3. Tap the instance card to play. The first start takes longer: the game builds its textures once.

## Controls

- Tap to click. The left stick moves the camera, the right stick moves the cursor; the big round
  buttons are left and right click.
- The top row: **Esc**, **Ctrl**, and the game speeds — pause, 1, 2, 3.
- **V** hides or shows the controls; hold it to move the buttons around during the game.
  **KB** opens the keyboard.
- **Controls** in the side menu opens the full editor. **Reset** brings back the default layout.

## Getting the best performance

- **Keep the frame rate at 60** (instance settings). 120 looks smoother but doubles the work and the
  heat; 30 saves the most battery.
- **Lower the render resolution** if the game feels slow: more FPS, and the interface gets bigger.
- **Turn on your phone's game booster** (Samsung *Game Booster*, Xiaomi *Game Turbo* and so on) and
  close background apps.
- **Save before you switch apps.** Android may close a game left in the background to free memory.

## Roadmap

- [x] The game runs: graphics, sound, input, saving, clean exit
- [x] Steam download and multiple instances
- [x] On-screen controls with an editor
- [x] Game language, render resolution, frame rate limit
- [ ] Testing on Mali and Xclipse GPUs and on phones with less RAM
- [x] Mods screen and mod.io downloads
- [ ] Steam Workshop downloads
- [ ] In-app wiki
- [ ] Lower the frame rate automatically when the phone gets too hot
- [ ] Beta

## How it works

Songs of Syx is written in Java, so its own code runs unchanged on a Java runtime built for Android
(OpenJDK 21). SyxDroid supplies what Android lacks: a window and touch input
([GLFW](https://github.com/glfw/glfw) with an Android backend), graphics
([MobileGlues](https://github.com/MobileGL-Dev/MobileGlues) turns the game's OpenGL 3.3 into OpenGL
ES 3.2), sound ([OpenAL Soft](https://github.com/kcat/openal-soft)) and
[LWJGL](https://github.com/LWJGL/lwjgl3) built for Android.

A small Java agent, `syxagent`, adapts a few of the game's classes in memory while they load —
shaders and textures for OpenGL ES, no extra windows or processes, frame pacing. Nothing is written
to the game's files, and each change is listed in [NOTICE](NOTICE.md) and can be switched off.

## Build

- Android Studio with **JDK 21**
- `syxboot/` (the game's starter) and `syxagent/` (the agent) are built and packed into
  `runtime.zip` by Gradle
- `libs.tar.xz` holds prebuilt LWJGL natives and MobileGlues; versions and sources are in
  [NOTICE](NOTICE.md)

## Supporting development

This is an independent project. To help keep it going, contributions are welcome via
[Ko-Fi](https://ko-fi.com/udarmolota).

## Feedback

Please report problems or request features via
[GitHub Issues](https://github.com/udarmolota/syxdroid/issues). Right after the problem happens, tap
**Report a bug** in the app's side menu: send the ZIP by email from there, or save it and attach it
to your issue, so we can see what your device is doing.

Played on a phone that isn't listed above? **Tell us how it went** — reports from other GPUs and
smaller phones are exactly what we're missing.

## Credits & Third-Party Sources

- [OpenJDK](https://github.com/openjdk/jdk) — Android build by
  [PojavLauncherTeam](https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch)
- [MobileGlues](https://github.com/MobileGL-Dev/MobileGlues) by
  [MobileGL-Dev](https://github.com/MobileGL-Dev) — OpenGL→OpenGL ES; SyxDroid uses the
  [fork](https://github.com/constantinrarinca3-sketch/MobileGlues) kept by mepetz
- [LWJGL](https://github.com/LWJGL/lwjgl3), [GLFW](https://github.com/glfw/glfw),
  [OpenAL Soft](https://github.com/kcat/openal-soft)
- [JavaSteam](https://github.com/Longi94/JavaSteam) — the in-app Steam download
- [ASM](https://asm.ow2.io/) — inside `syxagent`
- [liblinkernsbypass](https://github.com/bylaws/liblinkernsbypass) — Android linker namespace access
- [Zomdroid](https://github.com/udarmolota/zomdroid) (MIT) — the launcher SyxDroid is built on;
  the bottom bar, import/export, projects and bug report pages come from
  [RimDroid](https://github.com/udarmolota/RimDroid)
