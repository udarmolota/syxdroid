pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "syxdroid"
include(":app")
// The in-process entry point the game JVM starts (SyxBoot). Plain Java, packed into the app's
// runtime bundle, never dexed.
include(":syxboot")
// The Java agent that adapts the player's game classes at load time (shaders, child processes).
include(":syxagent")
 