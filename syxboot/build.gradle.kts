// SyxBoot: the main class the game JVM is started with (see SyxBoot.java). It runs on the
// launcher's own JRE 21 next to the player's SongsOfSyx.jar, so it is plain Java: compiled here,
// packed into assets/bundles/runtime.zip by the app module, never dexed.
plugins {
    java
}

tasks.withType<JavaCompile>().configureEach {
    // The game itself targets Java 16 (class version 60); 17 is the lowest LTS above it and well
    // below the JRE 21 we run on.
    options.release.set(17)
}

tasks.jar {
    archiveFileName.set("syxboot.jar")
    // Same bytes for the same sources, so the runtime bundle's CRC only changes with real changes.
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
