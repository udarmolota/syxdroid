// syxagent: a Java agent (-javaagent) for the game JVM. It changes the player's own Songs of Syx
// classes in memory as they load, never on disk (see SyxAgent.java). Plain Java on the launcher's
// JRE 21, packed into assets/bundles/runtime.zip by the app module, never dexed.
plugins {
    java
}

val bundled: Configuration by configurations.creating

dependencies {
    implementation(libs.asm)
    bundled(libs.asm)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.jar {
    archiveFileName.set("syxagent.jar")
    manifest {
        attributes(
            "Premain-Class" to "com.udarmolota.syxdroid.agent.SyxAgent",
            "Can-Redefine-Classes" to "false",
            "Can-Retransform-Classes" to "false"
        )
    }
    // A single jar: ASM goes inside it. Songs of Syx ships no ASM of its own, so no relocation.
    from(bundled.map { zipTree(it) }) {
        exclude("META-INF/**", "module-info.class")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
