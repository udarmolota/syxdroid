import java.util.Properties
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.android.application)
}

/**
 * Packs the Java side of the game runtime into assets/bundles/runtime.zip: the LWJGL 3.4.1 jars
 * (BSD, from Maven Central), syxboot.jar and syxagent.jar. InstallerService extracts it to
 * dependencies/runtime/ like the jre21 and libs bundles, and extracts it again when its CRC changes.
 *
 * <p>No game file is ever packed: the player imports their own Songs of Syx on the device.
 * Entries are STORED (jars are compressed already) with a fixed time, so the same jars always
 * make the same archive and the same CRC.
 */
abstract class PackGameRuntime : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val jars: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun pack() {
        val outDir = outputDir.get().asFile.resolve("bundles")
        outDir.deleteRecursively()
        outDir.mkdirs()
        val files = jars.files.sortedBy { it.name }
        ZipOutputStream(outDir.resolve("runtime.zip").outputStream().buffered(1 shl 20)).use { zip ->
            for (file in files) {
                val bytes = file.readBytes()
                val crc = CRC32().apply { update(bytes) }
                val entry = ZipEntry(file.name).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = bytes.size.toLong()
                    this.crc = crc.value
                    time = DOS_EPOCH_MS
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        logger.lifecycle("Packed runtime.zip: ${files.joinToString { it.name }}")
    }

    private companion object {
        // A fixed entry time keeps the archive byte-for-byte the same for the same jars.
        const val DOS_EPOCH_MS = 315_532_800_000L
    }
}

// The jars that go into runtime.zip. Not transitive: each LWJGL module depends only on the core
// jar, which is listed itself, and the natives-* classifier jars are glibc builds we never want.
val gameRuntime: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}

val packGameRuntime = tasks.register<PackGameRuntime>("packGameRuntime") {
    jars.from(gameRuntime)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

// Release signing values come from local.properties, or from environment variables of the same
// names (CI). Without all four the release build comes out unsigned, to be signed by hand.
fun signingValue(name: String): String? =
    localProperties.getProperty(name) ?: providers.environmentVariable(name).orNull?.takeIf { it.isNotEmpty() }

// Short commit hash of the working tree, with a "+" appended when there are uncommitted changes,
// so a bug report names the exact build. Falls back to "unknown" outside a git checkout or
// without git, so the build never depends on it.
val gitBuildId: String = try {
    val hash = providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
        workingDir = rootProject.projectDir
    }.standardOutput.asText.get().trim()
    val dirty = providers.exec {
        commandLine("git", "status", "--porcelain")
        workingDir = rootProject.projectDir
    }.standardOutput.asText.get().trim().isNotEmpty()
    if (hash.isEmpty()) "unknown" else hash + if (dirty) "+" else ""
} catch (e: Exception) {
    "unknown"
}

val hasSigningConfig = listOf(
    "RELEASE_STORE_FILE",
    "RELEASE_STORE_PASSWORD",
    "RELEASE_KEY_ALIAS",
    "RELEASE_KEY_PASSWORD"
).all { signingValue(it) != null }

android {
    namespace = "com.udarmolota.syxdroid"
    compileSdk = 35

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = file(signingValue("RELEASE_STORE_FILE")!!)
                storePassword = signingValue("RELEASE_STORE_PASSWORD")
                keyAlias = signingValue("RELEASE_KEY_ALIAS")
                keyPassword = signingValue("RELEASE_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.udarmolota.syxdroid"
        minSdk = 30
        targetSdk = 35
        versionCode = 11
        versionName = "0.1.1"

        buildConfigField("String", "GIT_BUILD_ID", "\"$gitBuildId\"")

        multiDexEnabled = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.add("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                // Align native .so segments to 16 KB pages (Android 15+ requirement).
                // NDK r27 doesn't enable this by default; the flag adds -Wl,-z,max-page-size=16384.
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                // Package libc++_shared.so: MobileGlues (from libs.tar.xz) links against it.
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            val outputImpl = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            outputImpl.outputFileName = "syxdroid-${variant.buildType.name}-${variant.versionName}.apk"
        }
    }

    buildTypes {
        release {
            // Signed only when the signing values are there; unsigned otherwise.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isDebuggable = true
        }
    }
    sourceSets {
        // Both build types are SyxDroid: they share its manifest overlay, name, icon and
        // shortcuts from src/syx/.
        for (buildType in listOf("debug", "release")) {
            getByName(buildType) {
                manifest.srcFile("src/syx/AndroidManifest.xml")
                res.srcDir("src/syx/res")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/{AL2.0,LGPL2.1}",
                "**/*.proto"
            )
        }
    }
    androidResources {
        // The runtime bundle (PackGameRuntime) is stored already: keep it uncompressed in the APK.
        noCompress += "runtime.zip"
    }
    ndkVersion = "27.3.13750724"
}

androidComponents {
    // Every variant carries the runtime bundle (LWJGL jars, SyxBoot, SyxAgent). Never game files.
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(packGameRuntime, PackGameRuntime::outputDir)
    }
}

dependencies {
    implementation(libs.gson)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    implementation(libs.commons.io)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.legacy.support.v4)

    // In-app Steam download of the player's own copy (SteamGameDownloader), as in RimDroid.
    // xz 1.11 above is the version JavaSteam 1.8.0 was built against. zstd-jni as an AAR brings
    // the arm64 native the depot chunks need; the plain jar has none for Android.
    implementation(libs.javasteam)
    implementation(libs.javasteam.depotdownloader)
    implementation(libs.bouncycastle)
    implementation(libs.protobuf.java)
    implementation(variantOf(libs.zstd.jni) { artifactType("aar") })

    gameRuntime(libs.lwjgl.core)
    gameRuntime(libs.lwjgl.glfw)
    gameRuntime(libs.lwjgl.jemalloc)
    gameRuntime(libs.lwjgl.openal)
    gameRuntime(libs.lwjgl.opengl)
    gameRuntime(libs.lwjgl.stb)
    gameRuntime(project(":syxboot"))
    gameRuntime(project(":syxagent"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
